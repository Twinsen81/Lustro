package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.MediaType
import io.github.twinsen81.lustro.network.CapturedBody
import io.github.twinsen81.lustro.network.CapturedResponse
import io.github.twinsen81.lustro.network.MockRule
import io.github.twinsen81.lustro.network.NetworkCaptureFilter
import io.github.twinsen81.lustro.network.NetworkCaptureRequest
import io.github.twinsen81.lustro.network.NetworkCaptureSink
import io.github.twinsen81.lustro.network.TransactionId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the capture filter on the platform `HttpURLConnection` path. The
 * capturing handler wraps a stand-in for the platform's own connection, so no
 * process-wide handler is installed.
 */
class HttpUrlConnectionCaptureFilterTest {
    @Test
    fun `a request the filter skips is neither recorded nor copied`() {
        val sink = RecordingSink()
        val platform = FakeHandler()
        val connection = open(sink, platform) { false }
        connection.requestMethod = "POST"
        connection.doOutput = true

        val output = connection.outputStream
        output.write("""{"event":"open"}""".toByteArray())
        val code = connection.responseCode
        val input = connection.inputStream
        val body = input.readBytes().decodeToString()

        val real = platform.connection!!
        assertSame(real.sent, output)
        assertSame(real.received, input)
        assertEquals("""{"event":"open"}""", real.sent.toString())
        assertEquals(200, code)
        assertEquals("hello", body)
        assertTrue(sink.begun.isEmpty())
        assertTrue(sink.completions.isEmpty())
    }

    @Test
    fun `a GET the filter skips hands back the platform's own stream`() {
        val sink = RecordingSink()
        val platform = FakeHandler()
        val connection = open(sink, platform) { false }

        val input = connection.inputStream

        assertSame(platform.connection!!.received, input)
        assertTrue(sink.begun.isEmpty())
    }

    @Test
    fun `the filter is asked once, with the method and headers the app set`() {
        val seen = mutableListOf<NetworkCaptureRequest>()
        val sink = RecordingSink()
        val connection =
            open(sink, FakeHandler()) {
                seen += it
                true
            }
        connection.requestMethod = "PUT"
        connection.setRequestProperty("X-No-Capture", "0")
        connection.doOutput = true

        connection.outputStream.write("{}".toByteArray())
        connection.responseCode
        connection.inputStream.readBytes()

        val request = seen.single()
        assertEquals("http://example.com/events", request.url)
        assertEquals("PUT", request.method)
        assertEquals("0", request.headers.get("X-No-Capture"))
        assertEquals(listOf("PUT http://example.com/events"), sink.begun)
        assertEquals("{}", sink.requestBodies.single()?.text)
        assertEquals("hello", sink.completions.last().body?.text)
    }

    @Test
    fun `a doOutput GET that connects before writing is filtered and recorded as the POST it sends`() {
        val seen = mutableListOf<String>()
        val sink = RecordingSink()
        val connection =
            open(sink, FakeHandler()) {
                seen += it.method
                true
            }
        connection.doOutput = true

        connection.connect()
        connection.outputStream.write("{}".toByteArray())
        connection.responseCode

        assertEquals(listOf("POST"), seen)
        assertEquals(listOf("POST http://example.com/events"), sink.begun)
    }

    private fun open(
        sink: NetworkCaptureSink,
        platform: FakeHandler,
        shouldCapture: (NetworkCaptureRequest) -> Boolean,
    ): HttpURLConnection {
        val handler =
            HttpUrlConnectionCapture.CapturingStreamHandler(
                real = platform,
                sink = sink,
                isPaused = { false },
                captureFilter = SafeCaptureFilter(NetworkCaptureFilter.of("test filter", shouldCapture)),
                maxBodySize = 1024,
                secure = false,
            )
        return URL(null, "http://example.com/events", handler).openConnection() as HttpURLConnection
    }

    /** Stands in for the platform handler, keeping the connection it opened. */
    private class FakeHandler : URLStreamHandler() {
        var connection: FakeConnection? = null

        override fun openConnection(u: URL): URLConnection = FakeConnection(u).also { connection = it }
    }

    /** Answers every request with a 200 and a short text body, and keeps what the app sent. */
    private class FakeConnection(url: URL) : HttpURLConnection(url) {
        val sent = ByteArrayOutputStream()
        val received = ByteArrayInputStream("hello".toByteArray())

        override fun connect() {
            connected = true
        }

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false

        override fun getOutputStream(): OutputStream {
            connect()
            return sent
        }

        override fun getInputStream(): InputStream {
            connect()
            return received
        }

        override fun getResponseCode(): Int {
            connect()
            return HttpURLConnection.HTTP_OK
        }

        override fun getHeaderFields(): MutableMap<String, MutableList<String>> =
            mutableMapOf("Content-Type" to mutableListOf("text/plain"))
    }

    private class RecordingSink : NetworkCaptureSink {
        val begun = mutableListOf<String>()
        val requestBodies = mutableListOf<CapturedBody?>()
        val completions = mutableListOf<CapturedResponse>()

        override fun beginRequest(
            url: String,
            method: String,
            headers: Headers,
            requestBody: CapturedBody?,
            contentType: MediaType?,
        ): TransactionId {
            begun += "$method $url"
            requestBodies += requestBody
            return TransactionId("tx-${begun.size}")
        }

        override fun findMockRule(url: String, method: String): MockRule? = null

        override fun completeRequest(id: TransactionId, response: CapturedResponse) {
            completions += response
        }

        override fun failRequest(id: TransactionId, durationMs: Long, error: String) = Unit
    }
}
