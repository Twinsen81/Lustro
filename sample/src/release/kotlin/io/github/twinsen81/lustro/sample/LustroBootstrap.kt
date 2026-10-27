package io.github.twinsen81.lustro.sample

import android.app.Application
import android.content.Context
import io.github.twinsen81.lustro.DebugConfig
import io.github.twinsen81.lustro.ExperimentalPlatformCapture
import io.github.twinsen81.lustro.Lustro
import io.github.twinsen81.lustro.LustroStatus
import io.github.twinsen81.lustro.logs.LogsDebugTab
import io.github.twinsen81.lustro.network.NetworkDebugTab
import io.github.twinsen81.lustro.network.OkHttpSender
import io.github.twinsen81.lustro.prefs.PrefsDataStore
import io.github.twinsen81.lustro.prefs.PrefsDebugTab
import io.github.twinsen81.lustro.prefs.PrefsFile
import io.github.twinsen81.lustro.prefs.ValuePolicy
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import kotlinx.coroutines.flow.StateFlow
import okhttp3.WebSocket

/**
 * Release-variant Lustro bootstrap.
 *
 * This file lives in `src/release` and compiles against `:lustro-noop`, the
 * release-safe no-op facade. It exposes the SAME object/function signatures as
 * the `src/debug` [LustroBootstrap], so [SampleApplication] (in `src/main`)
 * compiles unchanged under both variants.
 *
 * Building against the no-op `Lustro.builder` / `NetworkDebugTab.create` here is
 * what preserves the cross-variant parity gate: if the no-op facade signatures
 * ever drift from the real `:lustro` runtime, ONE of the two variants fails to
 * compile. The release variant registers NO custom tab — [SampleFlagsTab] is a
 * debug-only `DebugTab` and does not exist in this source set. It registers the
 * no-op [LogsDebugTab] from `:lustro-logs-noop` and the no-op [PrefsDebugTab]
 * from `:lustro-prefs-noop`, which checks the plugin pairs' facades the same way. Everything here
 * is a no-op at runtime: nothing binds, nothing is captured, and
 * [Lustro.networkInterceptor] returns a pass-through interceptor.
 */
// This src/release stub deliberately calls the Lustro API against :lustro-noop to
// drive the cross-variant facade-parity gate. No suppression is needed: the check
// flags DebugTab subclasses outside src/debug, and this file declares none.
public object LustroBootstrap {
    @Volatile
    private var lustro: Lustro? = null

    /**
     * No-op equivalent of the debug bootstrap. Builds Lustro against the no-op
     * facade and returns its pass-through interceptor. Releasing apps add this
     * interceptor unconditionally; in the no-op build it forwards every request
     * unchanged, so there is no release overhead and no debug server.
     */
    @OptIn(ExperimentalPlatformCapture::class)
    public fun start(app: Application, appOkHttpClient: () -> OkHttpClient): Interceptor {
        val instance =
            Lustro.builder(app)
                .config(
                    DebugConfig.builder()
                        .serverPort(CONSOLE_PORT)
                        .build(),
                )
                .addTab(
                    // Mirror the debug bootstrap's experimental opt-in so the same
                    // overload is compile-tested against the no-op facade (the
                    // cross-variant parity gate). Inert here: nothing is captured.
                    NetworkDebugTab.create(
                        senders =
                            listOf(
                                OkHttpSender.create("httpbingo.org", baseUrl = "https://httpbingo.org", client = appOkHttpClient),
                                OkHttpSender.create("Any host", client = appOkHttpClient),
                            ),
                        capturePlatformHttp = true,
                    ),
                )
                // The no-op half of :lustro-logs, with the same create() as the
                // debug bootstrap's: it reads no logs.
                .addTab(LogsDebugTab.create())
                // The no-op half of :lustro-prefs, with the same files and store as
                // the debug bootstrap's: it reads none of them.
                .addTab(
                    PrefsDebugTab.create(
                        app,
                        files =
                            listOf(
                                PrefsFile(DemoPrefs.SETTINGS, app.getSharedPreferences(DemoPrefs.SETTINGS, Context.MODE_PRIVATE), ValuePolicy.VISIBLE),
                                PrefsFile(
                                    DemoPrefs.SESSION,
                                    app.getSharedPreferences(DemoPrefs.SESSION, Context.MODE_PRIVATE),
                                    ValuePolicy.KEYS_ONLY,
                                    readOnly = true,
                                ),
                            ),
                        dataStores = listOf(PrefsDataStore(DemoDataStore.APP_STATE, DemoDataStore.appState(app))),
                    ),
                )
                .build()

        val interceptor = instance.networkInterceptor()
        lustro = instance
        val status: LustroStatus = instance.start()
        android.util.Log.i("LustroSample", "Lustro start() returned $status")
        return interceptor
    }

    /**
     * No-op equivalent of the debug bootstrap's factory: the no-op facade
     * returns [client] itself, so release sockets are plain OkHttp sockets.
     */
    public fun webSocketFactory(client: OkHttpClient): WebSocket.Factory = lustro?.webSocketFactory(client) ?: client

    /** No-op stop; the no-op build never binds a socket. Idempotent. */
    public fun stop() {
        // No-op: the release facade never started a server.
    }

    /** No-op restart; the no-op build never binds a socket. Idempotent. */
    public fun restart() {
        // No-op: the release facade never starts a server.
    }

    internal fun extraDemoRequests(base: String): List<DemoRequestSpec> = emptyList()

    /** False in this variant: there is no server, so the app hides its controls. */
    internal const val CONSOLE_AVAILABLE: Boolean = false

    /** Always false: the no-op build never binds a socket. */
    internal fun isRunning(): Boolean = false

    /** Null: the release build has no Feature Flags tab, so the app uses its defaults. */
    internal fun flags(): StateFlow<List<SampleFlag>>? = null

    /** No-op: the release build has no flags to set. */
    internal fun setFlag(id: String, enabled: Boolean) {
        // No-op: there is no Feature Flags tab in this variant.
    }
}
