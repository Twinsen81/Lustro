@file:Suppress("TooGenericExceptionCaught")

package io.github.twinsen81.lustro

import android.app.Application
import androidx.lifecycle.Lifecycle
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drain-while-in-flight lifecycle test. When the app goes to
 * the background, [Lustro.onBackground] must DRAIN: stop accepting new requests
 * but WAIT for any in-flight `/api/v1` request to finish (inFlightCount → 0)
 * before closing the socket, within the 5s budget. The drain runs on an internal
 * thread so [Lustro.onBackground] (a MAIN-thread callback) returns immediately.
 *
 * The test drives the real lifecycle seam ([Lustro.onBackground]) and the
 * limiter's in-flight counter (via the loopback socket's liveness) using a tab
 * whose `handle()` blocks on a release latch: onBackground returns at once, and
 * while the handler is parked the socket stays up (the async drain is waiting);
 * once released, the drain finishes and the socket closes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LustroDrainTest {
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
        override val lifecycle: Lifecycle get() = registry
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

    @Test
    fun `background drain waits for the in-flight request before closing the socket`() {
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
        val requestThread =
            Thread {
                runCatching {
                    client.newCall(
                        Request.Builder()
                            .url("http://127.0.0.1:$port/api/v1/block/slow")
                            .header("Authorization", "Bearer $token")
                            .get()
                            .build(),
                    ).execute().use { it.body?.string() }
                }
            }.apply { isDaemon = true; start() }

        // Wait until the handler is actually in-flight.
        assertTrue(
            "handler did not start in time",
            handlerStarted.await(5, TimeUnit.SECONDS),
        )
        assertEquals("exactly one request in-flight", 1, inFlight.get())

        // Background the app for real (fires onStop -> onBackground). The drain+close
        // now runs on an internal thread, so this transition must RETURN IMMEDIATELY
        // (the real callback is on the main thread): it must not block for the
        // in-flight request. Backgrounding via the lifecycle (not a bare
        // onBackground() call) also lets the drain see that we are NOT foregrounded,
        // so it closes rather than rebinding.
        val onBackgroundCall = System.currentTimeMillis()
        registry.currentState = Lifecycle.State.CREATED
        val onBackgroundElapsed = System.currentTimeMillis() - onBackgroundCall
        assertTrue(
            "backgrounding must not block on the drain (took ${onBackgroundElapsed}ms)",
            onBackgroundElapsed < 1_000,
        )

        // While the handler is parked, the async drain must NOT close the socket: it
        // is WAITING for the in-flight request to finish. Prove it stays bound.
        Thread.sleep(300)
        assertTrue("socket must still be bound while draining", lustro.isBound())
        assertEquals("request still in-flight while draining", 1, inFlight.get())

        // Now let the handler finish. The drain should observe inFlight -> 0 and
        // close the socket, all within the 5s budget.
        val drainStart = System.currentTimeMillis()
        release.countDown()

        // Poll for the async close to complete.
        while (lustro.isBound() && System.currentTimeMillis() - drainStart < 6_000) {
            Thread.sleep(10)
        }
        val drainElapsed = System.currentTimeMillis() - drainStart
        assertTrue(
            "drain must finish within the 5s budget (took ${drainElapsed}ms)",
            drainElapsed < 5_000,
        )
        assertEquals("no requests should remain in-flight after drain", 0, inFlight.get())
        assertFalse("socket must be closed after the drain", lustro.isBound())

        requestThread.join(TimeUnit.SECONDS.toMillis(5))
    }
}
