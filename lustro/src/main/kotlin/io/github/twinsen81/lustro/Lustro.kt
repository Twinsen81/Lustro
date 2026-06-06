@file:Suppress("TooGenericExceptionCaught")

package io.github.twinsen81.lustro

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.twinsen81.lustro.internal.DebugAssetLoader
import io.github.twinsen81.lustro.internal.DebugTabRegistry
import io.github.twinsen81.lustro.internal.LustroServer
import io.github.twinsen81.lustro.internal.LustroTokenStore
import io.github.twinsen81.lustro.internal.network.NetworkCaptureProvider
import io.github.twinsen81.lustro.network.NetworkDebugTab
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import okhttp3.Interceptor

/**
 * The Lustro debug runtime facade.
 *
 * Build one with [builder], register [DebugTab]s, and [start] it to ARM the
 * loopback debug server. Obtain an OkHttp interceptor with [networkInterceptor]
 * to feed captured traffic into the registered [NetworkDebugTab] (if any).
 *
 * Lifecycle: [start] registers a process-lifecycle observer
 * and returns [LustroStatus.ENABLED] once armed. The socket binds when the app is
 * in the foreground (and re-binds on each foreground); when the app goes to the
 * background the server DRAINS in-flight requests and closes the socket. [stop]
 * disarms (removes the observer) and closes the socket. Both are idempotent.
 *
 * Binding: the socket binds to [DebugConfig.bindAddress]
 * (default `127.0.0.1`; `0.0.0.0` opts into LAN exposure) on
 * [DebugConfig.serverPort]. If that port is taken, [DebugConfig.bindFallback]
 * controls whether the server retries on an OS-assigned ephemeral port or refuses
 * to start.
 */
public class Lustro internal constructor(
    application: Application,
    private val config: DebugConfig,
    private val registry: DebugTabRegistry,
    // Injectable so tests can drive binding without the real ProcessLifecycleOwner.
    // Production passes the process lifecycle.
    private val lifecycle: Lifecycle = ProcessLifecycleOwner.get().lifecycle,
) {
    private val assetLoader = DebugAssetLoader(application.applicationContext)

    // Always-on token store backed by a private SharedPreferences file. The token
    // is materialised lazily and surfaced on the machine-parseable startup log line
    // emitted after each successful bind.
    private val tokenStore = LustroTokenStore(application.applicationContext)

    @Volatile
    private var server: LustroServer? = null

    // ARMED = start() ran and the lifecycle observer is registered. Distinct from
    // "bound": the server can be armed but unbound while backgrounded.
    @Volatile
    private var armed: Boolean = false

    @Volatile
    private var captureEnabled: Boolean = true

    // Single daemon thread that runs the background drain+close off the main
    // thread (the ProcessLifecycleOwner fires onStop on the main thread). Serial
    // by construction, so overlapping background events drain in order.
    private val drainExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "lustro-drain").apply { isDaemon = true }
        }

    // The process-lifecycle observer installed by start(); foreground binds,
    // background drains+closes. Held so stop() can remove it.
    private val lifecycleObserver =
        object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                onForeground()
            }

            override fun onStop(owner: LifecycleOwner) {
                onBackground()
            }
        }

    // Cached so networkInterceptor() returns a stable instance and so start()
    // can hand the bound host:port to the tab for self-request detection.
    private val networkProvider: NetworkCaptureProvider? =
        registry.sortedTabs.filterIsInstance<NetworkCaptureProvider>().firstOrNull()

    /**
     * Returns an OkHttp application interceptor that feeds captured traffic into
     * the registered [NetworkDebugTab]. When no network tab is registered, returns
     * a pass-through interceptor.
     */
    public fun networkInterceptor(): Interceptor {
        val provider = networkProvider ?: return Interceptor { it.proceed(it.request()) }
        return provider.createInterceptor { captureEnabled }
    }

    /**
     * ARMS the debug server: freezes the tab registry and registers the
     * process-lifecycle observer that binds the socket on foreground and drains it
     * on background. Returns [LustroStatus.ENABLED] once armed, or
     * [LustroStatus.DISABLED] if arming fails. When the process is already at least
     * [Lifecycle.State.STARTED], the socket binds immediately. Idempotent.
     */
    public fun start(): LustroStatus {
        synchronized(this) {
            if (armed) return LustroStatus.ENABLED
            return try {
                registry.start()
                notifyTabs { it.onStart() }
                lifecycle.addObserver(lifecycleObserver)
                armed = true
                // Robolectric/tests and any process already foregrounded won't fire
                // a fresh onStart, so bind eagerly when already at least STARTED.
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    bind()
                }
                LustroStatus.ENABLED
            } catch (e: Exception) {
                Log.w(TAG, "Failed to arm the debug server", e)
                armed = false
                LustroStatus.DISABLED
            }
        }
    }

    /** DISARMS the server: removes the lifecycle observer and closes the socket. Idempotent. */
    public fun stop() {
        synchronized(this) {
            if (!armed) return
            try {
                lifecycle.removeObserver(lifecycleObserver)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing lifecycle observer", e)
            }
            closeServer()
            notifyTabs { it.onStop() }
            armed = false
        }
    }

    /**
     * Foreground lifecycle hook: (re-)binds the socket.
     * Internal so unit/Robolectric tests can drive binding without a real
     * [ProcessLifecycleOwner] dispatch.
     */
    internal fun onForeground() {
        synchronized(this) {
            if (!armed) return
            bind()
        }
    }

    /**
     * Background lifecycle hook: DRAINS in-flight requests
     * (up to [DRAIN_TIMEOUT_MS]) and closes the socket. Internal for tests.
     *
     * The drain+close runs on [drainExecutor] so this returns IMMEDIATELY: the
     * ProcessLifecycleOwner dispatches `onStop` on the MAIN thread and a slow
     * in-flight request must never block the UI for the drain budget. We capture
     * the exact server instance to drain so a rapid re-foreground that rebinds a
     * fresh server is never closed by this drain (see [drainAndClose]).
     */
    internal fun onBackground() {
        val toClose =
            synchronized(this) {
                if (!armed) return
                server ?: return
            }
        try {
            drainExecutor.execute { drainAndClose(toClose) }
        } catch (e: RejectedExecutionException) {
            // Executor already shut down (stop() ran). Fall back to a synchronous
            // close so the socket still tears down.
            Log.w(TAG, "Drain executor rejected the background close; closing inline", e)
            drainAndClose(toClose)
        }
    }

    /** True once the socket is bound and listening. Exposed for tests. */
    internal fun isBound(): Boolean = server?.isAlive == true

    /** The actually-bound port, or `-1` when unbound. Exposed for tests. */
    internal fun boundPort(): Int = server?.listeningPort ?: -1

    /**
     * Binds the loopback socket, honouring the port-collision policy. When
     * [DebugConfig.serverPort] is taken: with [DebugConfig.bindFallback] true,
     * retry on an ephemeral port (`0`) and use the OS-assigned port; otherwise log
     * a WARN and leave the server unbound. Emits the machine-parseable startup log
     * after a successful bind. Caller holds the monitor.
     */
    private fun bind() {
        if (server?.isAlive == true) return
        val bound = tryBind(config.serverPort) ?: fallbackBind()
        if (bound == null) {
            server = null
            return
        }
        server = bound
        val actualPort = bound.listeningPort
        // Hand the ACTUAL bind host:port to the network tab so its Send panel can
        // reject self-requests precisely (the ephemeral port differs from config).
        (networkProvider as? NetworkDebugTab)?.bindTo(config.bindAddress, actualPort)
        logEndpoint(actualPort)
    }

    /** Attempts the configured-port bind; returns the live server or `null` on collision. */
    private fun tryBind(port: Int): LustroServer? {
        val srv = newServer(port)
        return try {
            srv.start(NanoSocketReadTimeoutMs, false)
            srv
        } catch (e: Exception) {
            Log.w(TAG, "Failed to bind debug server on ${config.bindAddress}:$port", e)
            srv.shutdownLimiter()
            null
        }
    }

    /** Applies the [DebugConfig.bindFallback] policy after a configured-port collision. */
    private fun fallbackBind(): LustroServer? {
        if (!config.bindFallback) {
            Log.w(
                TAG,
                "Debug server port ${config.serverPort} is taken and bindFallback=false; not starting",
            )
            return null
        }
        // Ephemeral port 0 → the OS assigns a free port; we read it back via
        // listeningPort and surface it on the startup log.
        return tryBind(EPHEMERAL_PORT)
    }

    private fun newServer(port: Int): LustroServer =
        LustroServer(
            hostname = config.bindAddress,
            port = port,
            registry = registry,
            assetLoader = assetLoader,
            tokenStore = tokenStore,
            allowedOrigins = config.allowedOrigins,
            maxRequestBodyBytes = config.maxRequestBodyBytes,
            maxConcurrentRequests = config.maxConcurrentRequests,
            requestQueueCapacity = config.requestQueueCapacity,
            requestTimeoutMs = config.requestTimeoutMs,
        )

    /**
     * Emits the single machine-parseable endpoint-discovery line. Tag `LustroToken`,
     * level INFO. The CLI/agent parses host, port, and
     * token from this line — it is the single source of truth for discovery.
     */
    private fun logEndpoint(port: Int) {
        Log.i(
            ENDPOINT_LOG_TAG,
            "Lustro ready endpoint=http://${config.bindAddress}:$port token=${tokenStore.token()}",
        )
    }

    /**
     * Closes the socket immediately (no drain). Used by [stop] for an explicit
     * disarm; the background path drains asynchronously via [drainAndClose].
     * Caller holds the monitor.
     */
    private fun closeServer() {
        val srv = server ?: return
        try {
            srv.stop()
            srv.shutdownLimiter()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping debug server", e)
        }
        server = null
    }

    /**
     * Drains in-flight requests on [toClose] and closes it, OFF the monitor so a
     * concurrent foreground is not blocked for the drain budget. Only the final
     * state reconciliation takes the monitor: if [server] still points at the
     * instance we just closed (no re-foreground rebound a new one), null it and —
     * when we are still armed and foregrounded — rebind so a background→foreground
     * straddling a slow drain ends up bound again. If a newer server was bound in
     * the meantime, we leave [server] untouched (we only ever close the instance
     * we captured).
     */
    private fun drainAndClose(toClose: LustroServer) {
        try {
            toClose.beginDrain()
            awaitInFlightDrain(toClose)
            toClose.stop()
            toClose.shutdownLimiter()
        } catch (e: Exception) {
            Log.w(TAG, "Error draining/closing debug server", e)
        }
        synchronized(this) {
            if (server === toClose) {
                server = null
                if (armed && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    bind()
                }
            }
        }
    }

    /** Spins up to [DRAIN_TIMEOUT_MS] for the server's in-flight request count to hit 0. */
    private fun awaitInFlightDrain(srv: LustroServer) {
        val deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS
        while (srv.inFlightCount() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(DRAIN_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private inline fun notifyTabs(action: (DebugTab) -> Unit) {
        for (tab in registry.sortedTabs) {
            try {
                action(tab)
            } catch (e: Exception) {
                Log.w(TAG, "Tab ${tab.id} lifecycle callback failed", e)
            }
        }
    }

    /** Factory for [Lustro]. */
    public companion object {
        private const val TAG = "Lustro"

        // Machine-parseable endpoint-discovery log tag. The
        // CLI/agents grep for this tag to learn host:port + token.
        private const val ENDPOINT_LOG_TAG = "LustroToken"

        // NanoHTTPD's per-connection socket read timeout. The per-REQUEST timeout
        // is enforced separately by RequestLimiter inside the server.
        private const val NanoSocketReadTimeoutMs = 30_000

        // Ephemeral-port sentinel: bind(0) lets the OS assign a free port.
        private const val EPHEMERAL_PORT = 0

        // Background drain budget: wait this long for in-flight requests to
        // finish before force-closing the socket.
        private const val DRAIN_TIMEOUT_MS = 5_000L
        private const val DRAIN_POLL_MS = 25L

        /** Returns a new [Builder] bound to [application]. */
        @JvmStatic
        public fun builder(application: Application): Builder = Builder(application)
    }

    /** Builder for [Lustro]; register tabs and apply a [DebugConfig], then [build]. */
    public class Builder internal constructor(
        private val application: Application,
    ) {
        private var config: DebugConfig = DebugConfig.DEFAULT
        private val registry = DebugTabRegistry()

        /** Applies the given [config]. */
        public fun config(config: DebugConfig): Builder = apply { this.config = config }

        /**
         * Registers a [tab]. Throws [IllegalArgumentException] immediately when the
         * tab's id is invalid.
         */
        public fun addTab(tab: DebugTab): Builder = apply { registry.addTab(tab) }

        /** Builds the [Lustro] runtime. */
        public fun build(): Lustro {
            // Push DebugConfig into the registered NetworkDebugTab (config-injection
            // seam) so it honours the configured ring cap, body cap, and relative
            // Send Request base instead of its standalone factory defaults.
            registry.sortedTabs.filterIsInstance<NetworkDebugTab>().forEach { tab ->
                tab.applyConfig(
                    maxCaptureTransactions = config.maxCaptureTransactions,
                    maxBodyCaptureBytes = config.maxBodyCaptureBytes,
                    appServerBaseUrl = config.appServerBaseUrl,
                    captureBudgetBytes = config.captureBudgetBytes,
                )
            }
            return Lustro(application, config, registry)
        }
    }
}
