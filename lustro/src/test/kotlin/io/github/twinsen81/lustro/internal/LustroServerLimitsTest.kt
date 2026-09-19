package io.github.twinsen81.lustro.internal

import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugTab
import io.github.twinsen81.lustro.UncaughtExceptionRecorder
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end (over loopback HTTP) tests for the bounded-dispatch limits in
 * [LustroServer]: 413 (body too large), 503 (concurrency + queue saturated,
 * slots held by timed-out handlers included), 504 (per-request timeout,
 * which cancels the request), and connections that end mid-request. The server
 * is constructed directly with tiny caps so the limits are reachable from a
 * unit test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LustroServerLimitsTest {
    @get:Rule
    val uncaught = UncaughtExceptionRecorder()

    private val client =
        OkHttpClient.Builder()
            .followRedirects(false)
            // Generous client read timeout so the SERVER's limits (not the client)
            // produce the observed status codes.
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

    private var server: LustroServer? = null
    private lateinit var token: String

    /** A tab whose handler can block on a latch (to fill the concurrency gate). */
    private class BlockingTab(
        private val onEnter: () -> Unit = {},
        private val gate: CountDownLatch? = null,
    ) : DebugTab() {
        override val id: String = "sample"
        override val title: String = "Sample"
        override val icon: String = "S"

        override fun handle(request: DebugRequest): DebugResponse? {
            onEnter()
            // Wait for release; cooperatively bail if the timeout interrupts us.
            try {
                gate?.await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return DebugResponse.ok("{\"ok\":true}")
        }
    }

    /**
     * A tab whose handler blocks until [release] opens and ignores interrupts.
     * With [honourCancel] it opens [release] itself once its request is cancelled.
     */
    private class StuckTab(
        private val release: CountDownLatch,
        private val honourCancel: Boolean,
    ) : DebugTab() {
        override val id: String = "sample"
        override val title: String = "Sample"
        override val icon: String = "S"

        val returnedCancelled = CountDownLatch(1)

        override fun handle(request: DebugRequest): DebugResponse {
            if (honourCancel) request.onCancel { release.countDown() }
            while (true) {
                try {
                    release.await()
                    break
                } catch (_: InterruptedException) {
                    // Ignore it, like work that doesn't check for interrupts.
                }
            }
            if (request.isCancelled) returnedCancelled.countDown()
            return DebugResponse.ok("{\"ok\":true}")
        }
    }

    private fun startServer(
        tab: DebugTab,
        maxRequestBodyBytes: Long = 1L * 1024 * 1024,
        maxConcurrent: Int = 16,
        queueCapacity: Int = 64,
        timeoutMs: Long = 30_000,
    ): Int {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val registry = DebugTabRegistry().apply { addTab(tab); start() }
        val tokenStore = LustroTokenStore(context)
        token = tokenStore.token()
        val srv =
            LustroServer(
                hostname = "127.0.0.1",
                port = 0,
                registry = registry,
                assetLoader = DebugAssetLoader(context),
                tokenStore = tokenStore,
                allowedOrigins = emptyList(),
                maxRequestBodyBytes = maxRequestBodyBytes,
                maxConcurrentRequests = maxConcurrent,
                requestQueueCapacity = queueCapacity,
                requestTimeoutMs = timeoutMs,
            )
        srv.start(2000, false)
        server = srv
        return srv.listeningPort
    }

    @After
    fun tearDown() {
        server?.stop()
        server?.shutdownLimiter()
    }

    private fun port(): Int = server!!.listeningPort

    private fun postBytes(path: String, body: ByteArray): Response =
        client.newCall(
            Request.Builder()
                .url("http://127.0.0.1:${port()}$path")
                .post(body.toRequestBody())
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .build(),
        ).execute()


    @Test
    fun `request body over the cap is rejected with enveloped 413`() {
        startServer(BlockingTab(), maxRequestBodyBytes = 1024)
        val body = ByteArray(4096) { 'a'.code.toByte() }
        postBytes("/api/v1/sample/clear", body).use { resp ->
            assertEquals(413, resp.code)
            assertEquals("payload_too_large", JSONObject(resp.body!!.string()).getString("error"))
        }
    }

    @Test
    fun `request body at the cap is accepted`() {
        startServer(BlockingTab(), maxRequestBodyBytes = 4096)
        val body = ByteArray(4096) { 'a'.code.toByte() }
        postBytes("/api/v1/sample/echo", body).use { resp ->
            // Not a 413; the tab returns 200 (or 404 for an unknown subpath) — the
            // point is the body was NOT rejected for size.
            assertTrue("must not be a 413", resp.code != 413)
        }
    }



    @Test
    fun `concurrency plus queue saturation yields enveloped 503`() {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val inHandler = AtomicInteger(0)
        val tab =
            BlockingTab(
                onEnter = {
                    inHandler.incrementAndGet()
                    entered.countDown()
                },
                gate = gate,
            )
        // 1 active permit + 1 queued slot: the 3rd concurrent request overflows.
        startServer(tab, maxConcurrent = 1, queueCapacity = 1)

        val pool = Executors.newFixedThreadPool(3)
        val codes = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val done = CountDownLatch(3)
        try {
            repeat(3) {
                pool.submit {
                    try {
                        postBytes("/api/v1/sample/clear", ByteArray(0)).use { codes.add(it.code) }
                    } catch (_: Exception) {
                        codes.add(-1)
                    } finally {
                        done.countDown()
                    }
                }
            }
            // Make sure the first request is actually executing (holds the permit),
            // so the other two contend for the single queue slot.
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // Give the queued/overflow requests a moment to reach the gate.
            Thread.sleep(300)
            gate.countDown() // release the blocked handler(s)
            assertTrue(done.await(10, TimeUnit.SECONDS))
        } finally {
            gate.countDown()
            pool.shutdownNow()
        }
        assertTrue("at least one request must be rejected with 503: $codes", codes.contains(503))
    }



    @Test
    fun `a client that closes mid-request is not served and its connection ends`() {
        startServer(BlockingTab())
        Socket("127.0.0.1", port()).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write("GET /tab/sample HTTP/1.1\r\nHost: localhost\r\n".toByteArray())
            socket.shutdownOutput()
            // NanoHTTPD alone would answer the partial request, then answer it again
            // and again on a thread that never ends.
            assertEquals(-1, socket.getInputStream().read())
        }
        client.newCall(Request.Builder().url("http://127.0.0.1:${port()}/tab/sample").build()).execute().use {
            assertEquals(200, it.code)
        }
    }

    @Test
    fun `handler exceeding the timeout yields enveloped 504`() {
        // Handler blocks forever; a 200ms timeout must convert it to a 504.
        val tab = BlockingTab(gate = CountDownLatch(1))
        startServer(tab, timeoutMs = 200)
        postBytes("/api/v1/sample/clear", ByteArray(0)).use { resp ->
            assertEquals(504, resp.code)
            assertEquals("timeout", JSONObject(resp.body!!.string()).getString("error"))
        }
    }

    @Test
    fun `a timed-out request is cancelled`() {
        val tab = StuckTab(CountDownLatch(1), honourCancel = true)
        startServer(tab, timeoutMs = 200)
        postBytes("/api/v1/sample/query", ByteArray(0)).use { resp ->
            assertEquals(504, resp.code)
        }
        assertTrue(
            "the handler saw its request cancelled and returned",
            tab.returnedCancelled.await(5, TimeUnit.SECONDS),
        )
    }

    @Test
    fun `a timed-out handler keeps its slot until it returns`() {
        val release = CountDownLatch(1)
        startServer(StuckTab(release, honourCancel = false), maxConcurrent = 1, queueCapacity = 0, timeoutMs = 200)
        try {
            postBytes("/api/v1/sample/query", ByteArray(0)).use { assertEquals(504, it.code) }
            postBytes("/api/v1/sample/query", ByteArray(0)).use { resp ->
                assertEquals(503, resp.code)
                assertEquals("unavailable", JSONObject(resp.body!!.string()).getString("error"))
            }
        } finally {
            release.countDown()
        }

        // Once the timed-out handler returns, its slot serves requests again.
        val deadline = System.currentTimeMillis() + 5_000
        var code: Int
        do {
            code = postBytes("/api/v1/sample/query", ByteArray(0)).use { it.code }
        } while (code != 200 && System.currentTimeMillis() < deadline)
        assertEquals(200, code)
    }

    @Test
    fun `an Error from a cancel action is logged and the client still gets its 504`() {
        val failure = NotImplementedError("simulated")
        val returned = CountDownLatch(1)
        val tab =
            object : DebugTab() {
                override val id: String = "sample"
                override val title: String = "Sample"
                override val icon: String = "S"

                override fun handle(request: DebugRequest): DebugResponse {
                    val cancelled = CountDownLatch(1)
                    request.onCancel { throw failure }
                    request.onCancel { cancelled.countDown() }
                    while (true) {
                        try {
                            cancelled.await()
                            break
                        } catch (_: InterruptedException) {
                            // Wait for the cancel actions instead.
                        }
                    }
                    returned.countDown()
                    return DebugResponse.ok("{\"ok\":true}")
                }
            }
        startServer(tab, timeoutMs = 200)

        postBytes("/api/v1/sample/query", ByteArray(0)).use { assertEquals(504, it.code) }

        assertTrue("the later action still ran", returned.await(5, TimeUnit.SECONDS))
        assertTrue(
            "the server logs the action's Error",
            ShadowLog.getLogsForTag("LustroServer").any { it.throwable === failure },
        )
        uncaught.assertNothingUncaught()
    }
}
