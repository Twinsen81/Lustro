package io.github.twinsen81.lustro.network

import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.MediaType
import io.github.twinsen81.lustro.internal.network.NetworkSendRequestImpl
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [OkHttpSender]: the response body is read only up to the cap (never
 * buffered in full), and the call is cancelled when its timeout elapses even if
 * the server stalls mid-body.
 */
class OkHttpSenderTest {
    private lateinit var server: MockWebServer

    // No read timeout, so only the sender's call timeout can end a stalled read.
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun get(path: String): NetworkSendRequest =
        NetworkSendRequestImpl(
            url = server.url(path).toString(),
            method = "GET",
            headers = Headers.EMPTY,
            body = null,
            contentType = null,
        )

    private fun zeros(byteCount: Long): Buffer = Buffer().write(ByteArray(byteCount.toInt()))

    @Test
    fun `returns status, headers, and the whole body when it fits under the cap`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setHeader("X-Trace", "abc")
                .setBody("""{"ok":true}"""),
        )

        val result = OkHttpSender(client, maxResponseBodyBytes = 1024, callTimeoutMs = 0).send(get("/small"))

        assertTrue(result.isSuccess)
        assertEquals(201, result.statusCode)
        assertEquals("abc", result.headers.get("X-Trace"))
        assertEquals(MediaType.parse("application/json"), result.contentType)
        assertArrayEquals("""{"ok":true}""".toByteArray(), result.body)
    }

    @Test(timeout = 20_000)
    fun `reads at most the cap and does not wait for the rest of the body`() {
        // 1 MiB at 16 KiB/s would take ~64 s to read in full.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/octet-stream")
                .setBody(zeros(1024L * 1024))
                .throttleBody(16L * 1024, 1, TimeUnit.SECONDS),
        )

        val started = System.nanoTime()
        val result = OkHttpSender(client, maxResponseBodyBytes = 4096, callTimeoutMs = 0).send(get("/big"))
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertTrue(result.errorMessage, result.isSuccess)
        assertEquals(200, result.statusCode)
        assertEquals(4096, result.body?.size)
        assertEquals(MediaType.parse("application/octet-stream"), result.contentType)
        assertTrue("send took ${elapsedMs}ms", elapsedMs < 5_000)
    }

    @Test
    fun `the public constructor caps the body at 256 KB`() {
        server.enqueue(MockResponse().setBody(zeros(1024L * 1024)))

        val result = OkHttpSender(client).send(get("/big"))

        assertTrue(result.errorMessage, result.isSuccess)
        assertEquals(256 * 1024, result.body?.size)
    }

    @Test(timeout = 20_000)
    fun `a response stalled mid-body is cancelled when the call timeout elapses`() {
        // One byte, then silence for 3 s: only the call timeout can end the read.
        server.enqueue(MockResponse().setBody("xy").throttleBody(1, 3, TimeUnit.SECONDS))

        val started = System.nanoTime()
        val result = OkHttpSender(client, maxResponseBodyBytes = 1024, callTimeoutMs = 300).send(get("/stall"))
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertFalse(result.isSuccess)
        assertEquals("timeout", result.errorMessage)
        assertTrue("send took ${elapsedMs}ms", elapsedMs < 2_000)
    }

    @Test(timeout = 20_000)
    fun `a shorter call timeout configured on the client is kept`() {
        server.enqueue(MockResponse().setBody("xy").throttleBody(1, 3, TimeUnit.SECONDS))
        val tightClient = client.newBuilder().callTimeout(300, TimeUnit.MILLISECONDS).build()

        val started = System.nanoTime()
        val result = OkHttpSender(tightClient, maxResponseBodyBytes = 1024, callTimeoutMs = 60_000).send(get("/stall"))
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertFalse(result.isSuccess)
        assertTrue("send took ${elapsedMs}ms", elapsedMs < 2_000)
    }
}
