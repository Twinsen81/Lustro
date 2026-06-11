package io.github.twinsen81.lustro.internal

import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugTab
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end (over loopback HTTP) tests for the bounded-dispatch limits in
 * [LustroServer]: 413 (body too large), 503 (concurrency +
 * queue saturated), and 504 (per-request timeout). The server is constructed
 * directly with tiny caps so the limits are reachable from a unit test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LustroServerLimitsTest {
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

    // region 413

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

    // endregion

    // region 503 concurrency + queue overflow

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

    // endregion

    // region 504 per-request timeout

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

    // endregion
}
