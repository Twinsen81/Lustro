@file:Suppress("TooGenericExceptionCaught")

package io.github.twinsen81.lustro

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.internal.DebugTabRegistry
import io.github.twinsen81.lustro.internal.LustroTokenStore
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drain-while-in-flight lifecycle test. When the app goes to the background,
 * [Lustro.onBackground] must close the listening socket at once, so that a new
 * connection is refused and another app with Lustro can bind the port, and DRAIN:
 * WAIT for any in-flight `/api/v1` request to finish (inFlightCount to 0) before
 * closing its connection, within the 5s budget. The drain runs on an internal
 * thread so [Lustro.onBackground] (a MAIN-thread callback) returns immediately.
 *
 * The test drives the real lifecycle seam ([Lustro.onBackground]) using a tab
 * whose `handle()` blocks on a release latch: onBackground returns at once, the
 * port refuses new connections while the handler is parked (the async drain is
 * waiting), and once released, the request gets its answer and the drain ends.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LustroDrainTest {
    @get:Rule
    val uncaught = UncaughtExceptionRecorder()

    private val client =
        OkHttpClient.Builder()
            .followRedirects(false)
            .callTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

    private lateinit var app: Application
    private lateinit var owner: FakeLifecycleOwner
    private val registry: LifecycleRegistry get() = owner.registry
    private val started = mutableListOf<Lustro>()

    private class FakeLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override fun getLifecycle(): Lifecycle = registry
    }

    /**
     * A tab whose `handle()` signals when it has started serving, then BLOCKS on
     * [release] so the test can hold a request in-flight across a background drain.
     */
    private class BlockingTab(
        private val started: CountDownLatch,
        private val release: CountDownLatch,
        val inFlight: AtomicInteger,
    ) : DebugTab() {
        override val id: String = "block"
        override val title: String = "Block"
        override val icon: String = "B"

        override fun renderContent(): String = "<div>block</div>"

        override fun handle(request: DebugRequest): DebugResponse {
            inFlight.incrementAndGet()
            started.countDown()
            try {
                // Park here until the test releases us (or the 5s drain budget would
                // be exceeded). A handler is "in-flight" the whole time.
                release.await(10, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                inFlight.decrementAndGet()
            }
            return DebugResponse.ok("{\"done\":true}")
        }
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        owner = FakeLifecycleOwner()
        registry.currentState = Lifecycle.State.CREATED
    }

    @After
    fun tearDown() {
        started.forEach { runCatching { it.stop() } }
        started.clear()
    }

    /** Sends `GET /api/v1/block/slow` on its own thread; [code] is the status of its answer, or -1. */
    private inner class SlowRequest(port: Int, token: String) {
        @Volatile
        var code = -1
        private val thread =
            Thread {
                runCatching {
                    client.newCall(
                        Request.Builder()
                            .url("http://127.0.0.1:$port/api/v1/block/slow")
                            .header("Authorization", "Bearer $token")
                            .get()
                            .build(),
                    ).execute().use { code = it.code }
                }
            }.apply { isDaemon = true; start() }

        fun join() = thread.join(TimeUnit.SECONDS.toMillis(5))
    }

    private fun refusesConnections(port: Int): Boolean =
        try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1_000) }
            false
        } catch (_: ConnectException) {
            true
        }

    /** A port that was free a moment ago, for a test that binds the same port again. */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun awaitDrainEnd(lustro: Lustro) {
        val deadline = System.currentTimeMillis() + 6_000
        while (lustro.isDraining() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
    }

    @Test
    fun `background closes the listening socket at once and the drain finishes the in-flight request`() {
        val handlerStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val inFlight = AtomicInteger(0)
        val tab = BlockingTab(handlerStarted, release, inFlight)

        val tabRegistry = DebugTabRegistry().apply { addTab(tab) }
        val config = DebugConfig.builder().serverPort(0).build()
        val lustro = Lustro(app, config, tabRegistry, registry).also { started.add(it) }

        assertEquals(LustroStatus.ENABLED, lustro.start())
        registry.currentState = Lifecycle.State.STARTED // foreground -> bind
        assertTrue("socket should bind on foreground", lustro.isBound())
        val port = lustro.boundPort()

        // The test-side token store reads the same private prefs file as the
        // runtime, so it yields the same always-on token (Bearer auth).
        val token = LustroTokenStore(app).token()

        // Fire the slow request; it parks inside handle() until we release it.
        val request = SlowRequest(port, token)
        assertTrue("handler did not start in time", handlerStarted.await(5, TimeUnit.SECONDS))
        assertEquals("exactly one request in-flight", 1, inFlight.get())

        // Background the app for real (fires onStop -> onBackground). The drain+close
        // runs on an internal thread, so this transition must RETURN IMMEDIATELY (the
        // real callback is on the main thread): it must not block for the in-flight
        // request. Backgrounding via the lifecycle (not a bare onBackground() call)
        // also lets the drain see that we are NOT foregrounded, so it closes rather
        // than rebinding.
        val onBackgroundCall = System.currentTimeMillis()
        registry.currentState = Lifecycle.State.CREATED
        val onBackgroundElapsed = System.currentTimeMillis() - onBackgroundCall
        assertTrue(
            "backgrounding must not block on the drain (took ${onBackgroundElapsed}ms)",
            onBackgroundElapsed < 1_000,
        )

        // The listening socket is closed at once: another app's client gets no answer
        // from this server, and another app can bind the port. The drain still waits
        // for the in-flight request.
        assertFalse("the server must stop listening at once", lustro.isBound())
        assertTrue("a new connection must be refused while draining", refusesConnections(port))
        Thread.sleep(300)
        assertTrue("the drain must wait for the in-flight request", lustro.isDraining())
        assertEquals("request still in-flight while draining", 1, inFlight.get())

        // Now let the handler finish. Its request gets its answer on the connection
        // that the server accepted before, and the drain ends within the 5s budget.
        val drainStart = System.currentTimeMillis()
        release.countDown()
        request.join()
        assertEquals("the in-flight request must get its answer", 200, request.code)
        awaitDrainEnd(lustro)
        val drainElapsed = System.currentTimeMillis() - drainStart
        assertTrue("drain must finish within the 5s budget (took ${drainElapsed}ms)", drainElapsed < 5_000)
        assertFalse(lustro.isDraining())
        assertEquals("no requests should remain in-flight after drain", 0, inFlight.get())
        assertFalse("socket must be closed after the drain", lustro.isBound())
    }

    @Test
    fun `a foreground during the drain binds a new server on the same port, and the end of the drain keeps it`() {
        val handlerStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val tabRegistry = DebugTabRegistry().apply { addTab(BlockingTab(handlerStarted, release, AtomicInteger())) }
        val port = freePort()
        val config = DebugConfig.builder().serverPort(port).build()
        val lustro = Lustro(app, config, tabRegistry, registry).also { started.add(it) }
        assertEquals(LustroStatus.ENABLED, lustro.start())
        registry.currentState = Lifecycle.State.STARTED
        assertEquals(port, lustro.boundPort())
        val token = LustroTokenStore(app).token()

        val request = SlowRequest(port, token)
        try {
            assertTrue("handler did not start in time", handlerStarted.await(5, TimeUnit.SECONDS))
            registry.currentState = Lifecycle.State.CREATED
            registry.currentState = Lifecycle.State.STARTED

            // The old server still holds the connection of its in-flight request, and
            // the new one listens on the same port.
            assertTrue("a foreground during the drain must bind at once", lustro.isBound())
            assertEquals(port, lustro.boundPort())
            assertTrue(lustro.isDraining())
        } finally {
            release.countDown()
        }
        request.join()
        assertEquals("the in-flight request must get its answer", 200, request.code)
        awaitDrainEnd(lustro)

        assertTrue("the end of the old drain must not close the new server", lustro.isBound())
        assertEquals(port, lustro.boundPort())
        val code =
            client.newCall(
                Request.Builder().url("http://127.0.0.1:$port/api/v1/_meta").header("Authorization", "Bearer $token").build(),
            ).execute().use { it.code }
        assertEquals("the new server answers", 200, code)
    }

    /** A tab whose handler ignores interrupts and cancellation until [release] opens. */
    private class StuckTab(private val release: CountDownLatch) : DebugTab() {
        override val id: String = "stuck"
        override val title: String = "Stuck"
        override val icon: String = "S"

        override fun handle(request: DebugRequest): DebugResponse {
            while (true) {
                try {
                    release.await()
                    break
                } catch (_: InterruptedException) {
                    // Ignore it, like work that doesn't check for interrupts.
                }
            }
            return DebugResponse.ok("{\"done\":true}")
        }
    }

    @Test
    fun `background drain waits for a timed-out handler that is still running`() {
        val release = CountDownLatch(1)
        val tabRegistry = DebugTabRegistry().apply { addTab(StuckTab(release)) }
        val config = DebugConfig.builder().serverPort(0).requestTimeoutMs(200).build()
        val lustro = Lustro(app, config, tabRegistry, registry).also { started.add(it) }
        assertEquals(LustroStatus.ENABLED, lustro.start())
        registry.currentState = Lifecycle.State.STARTED
        val port = lustro.boundPort()
        val token = LustroTokenStore(app).token()

        try {
            val code =
                client.newCall(
                    Request.Builder()
                        .url("http://127.0.0.1:$port/api/v1/stuck/slow")
                        .header("Authorization", "Bearer $token")
                        .get()
                        .build(),
                ).execute().use { it.code }
            assertEquals(504, code)

            // The handler is still running after its 504, so the drain must wait for it.
            registry.currentState = Lifecycle.State.CREATED
            Thread.sleep(300)
            assertFalse("the server stops listening at once", lustro.isBound())
            assertTrue("the drain must wait while the timed-out handler runs", lustro.isDraining())
        } finally {
            release.countDown()
        }

        awaitDrainEnd(lustro)
        assertFalse("the drain must end once the handler returns", lustro.isDraining())
    }

    /** Delegates to [delegate], except that [getCurrentState] throws [failure] once it is set. */
    private class FailingLifecycle(private val delegate: Lifecycle) : Lifecycle() {
        @Volatile
        var failure: Throwable? = null

        override fun getCurrentState(): State = failure?.let { throw it } ?: delegate.currentState

        override fun addObserver(observer: LifecycleObserver) = delegate.addObserver(observer)

        override fun removeObserver(observer: LifecycleObserver) = delegate.removeObserver(observer)
    }

    @Test
    fun `an Error during the background drain stays on the drain thread`() {
        val lifecycle = FailingLifecycle(registry)
        val config = DebugConfig.builder().serverPort(0).build()
        val lustro = Lustro(app, config, DebugTabRegistry(), lifecycle).also { started.add(it) }
        assertEquals(LustroStatus.ENABLED, lustro.start())
        registry.currentState = Lifecycle.State.STARTED
        assertTrue(lustro.isBound())

        // After closing the socket, the drain reads the lifecycle state to decide
        // whether to rebind; that read is where this Error surfaces.
        val failure = NotImplementedError("simulated")
        lifecycle.failure = failure
        registry.currentState = Lifecycle.State.CREATED

        val deadline = System.currentTimeMillis() + 5_000
        while (ShadowLog.getLogsForTag("Lustro").none { it.throwable === failure } &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(10)
        }
        assertTrue(
            "the drain logs the Error it contained",
            ShadowLog.getLogsForTag("Lustro").any { it.throwable === failure },
        )
        assertFalse(lustro.isBound())
        uncaught.assertNothingUncaught()
    }
}
