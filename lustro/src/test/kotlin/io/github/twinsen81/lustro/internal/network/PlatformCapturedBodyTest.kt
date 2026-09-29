package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.MediaType
import io.github.twinsen81.lustro.network.CapturedBody
import io.github.twinsen81.lustro.network.CapturedResponse
import io.github.twinsen81.lustro.network.MockRule
import io.github.twinsen81.lustro.network.NetworkCaptureSink
import io.github.twinsen81.lustro.network.NoOpNetworkCaptureFilter
import io.github.twinsen81.lustro.network.TransactionId
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [platformCapturedBody], which turns the bytes platform
 * `HttpURLConnection` capture tees off a stream into a body. The tee stops
 * writing at the cap, so a buffer at the cap means the body was cut.
 */
class PlatformCapturedBodyTest {
    private val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    private val json = """{"id":7,"note":"héllo"}"""

    @Test
    fun `an image body is kept as bytes`() {
        val body = platformCapturedBody(png, maxBodySize = 64, contentType = MediaType.parse("image/png"), contentEncoding = emptyList())

        assertArrayEquals(png, body.bytes)
        assertNull(body.text)
        assertFalse(body.truncated)
        assertEquals(8L, body.byteSize)
    }

    @Test
    fun `an image body that filled the tee is reported truncated`() {
        val body = platformCapturedBody(png, maxBodySize = 8, contentType = MediaType.parse("image/jpeg; q=1"), contentEncoding = emptyList())

        assertArrayEquals(png, body.bytes)
        assertTrue(body.truncated)
    }

    @Test
    fun `other bodies are decoded as text, typed or not`() {
        for (type in listOf("application/json", "image/svg+xml", "application/octet-stream", null)) {
            val body =
                platformCapturedBody("{}".toByteArray(), maxBodySize = 64, contentType = type?.let { MediaType.parse(it) }, contentEncoding = emptyList())

            assertEquals(type, "{}", body.text)
            assertNull(type, body.bytes)
        }
    }

    @Test
    fun `a gzip body is inflated, keeping its size on the wire`() {
        val compressed = gzip(json)

        val body = platformCapturedBody(compressed, maxBodySize = 1024, contentType = MediaType.parse("application/json"), contentEncoding = listOf("gzip"))

        assertEquals(json, body.text)
        assertFalse(body.truncated)
        assertEquals(compressed.size.toLong(), body.byteSize)
    }

    @Test
    fun `a body in a coding capture can't undo is not kept, but its size is`() {
        val body = platformCapturedBody(ByteArray(40) { 7 }, maxBodySize = 1024, contentType = MediaType.parse("application/json"), contentEncoding = listOf("br"))

        assertNull(body.text)
        assertNull(body.bytes)
        assertFalse(body.truncated)
        assertEquals(40L, body.byteSize)
    }

    @Test
    fun `a gzip response the app asked for itself is captured as its JSON`() {
        val server = MockWebServer()
        val compressed = gzip(json)
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setHeader("Content-Encoding", "gzip")
                .setBody(Buffer().write(compressed)),
        )
        server.start()
        try {
            val sink = RecordingSink()
            val connection = open(sink, server.url("/orders").toString())
            connection.setRequestProperty("Accept-Encoding", "gzip")

            val received = connection.inputStream.use { it.readBytes() }

            // The app still gets the compressed bytes it asked for.
            assertArrayEquals(compressed, received)
            val body = sink.completions.last().body!!
            assertEquals(json, body.text)
            assertEquals(compressed.size.toLong(), body.byteSize)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a gzip request body is captured as its JSON`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("ok"))
        server.start()
        try {
            val sink = RecordingSink()
            val compressed = gzip(json)
            val connection = open(sink, server.url("/orders").toString())
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Content-Encoding", "gzip")

            connection.outputStream.use { it.write(compressed) }
            assertEquals(200, connection.responseCode)

            assertArrayEquals(compressed, server.takeRequest().body.readByteArray())
            val body = sink.requestBodies.single()!!
            assertEquals(json, body.text)
            assertEquals(compressed.size.toLong(), body.byteSize)
        } finally {
            server.shutdown()
        }
    }

    // Wraps the JVM's own HTTP stack the way install() wraps the platform's.
    private fun open(sink: NetworkCaptureSink, url: String): HttpURLConnection {
        val jvmStack =
            object : URLStreamHandler() {
                override fun openConnection(u: URL): URLConnection = URL(u.toExternalForm()).openConnection()
            }
        val handler =
            HttpUrlConnectionCapture.CapturingStreamHandler(
                real = jvmStack,
                sink = sink,
                isPaused = { false },
                captureFilter = SafeCaptureFilter(NoOpNetworkCaptureFilter),
                maxBodySize = 1024,
                secure = false,
            )
        return URL(null, url, handler).openConnection() as HttpURLConnection
    }

    private class RecordingSink : NetworkCaptureSink {
        val requestBodies = mutableListOf<CapturedBody?>()
        val completions = mutableListOf<CapturedResponse>()

        override fun beginRequest(
            url: String,
            method: String,
            headers: Headers,
            requestBody: CapturedBody?,
            contentType: MediaType?,
        ): TransactionId {
            requestBodies += requestBody
            return TransactionId("tx-${requestBodies.size}")
        }

        override fun findMockRule(url: String, method: String): MockRule? = null

        override fun completeRequest(id: TransactionId, response: CapturedResponse) {
            completions += response
        }

        override fun failRequest(id: TransactionId, durationMs: Long, error: String) = Unit
    }
}
