package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.MediaType
import io.github.twinsen81.lustro.network.CapturedBody
import io.github.twinsen81.lustro.network.CapturedResponse
import io.github.twinsen81.lustro.network.MockRule
import io.github.twinsen81.lustro.network.NetworkCaptureSink
import io.github.twinsen81.lustro.network.NoOpNetworkCaptureFilter
import io.github.twinsen81.lustro.network.TransactionId
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests how the platform `HttpURLConnection` capture follows a response body
 * as the app reads it. The capturing handler wraps a stand-in for the
 * platform's own connection, so no process-wide handler is installed.
 */
class HttpUrlConnectionCaptureBodyTest {
    @Test
    fun `a body the app opens is in flight until it ends, and is sized by what was read`() {
        val sink = RecordingSink()
        val body = ByteArray(5_000) { it.toByte() }
        val connection = open(sink, FakeConnection.Spec(body, "text/plain"))

        assertEquals(200, connection.responseCode)
        assertTrue("a caller that never reads the body still sees it complete", sink.completions.single().isComplete)

        val input = connection.inputStream
        assertFalse("opening the body puts it back in flight", sink.completions.last().isComplete)

        assertArrayEquals(body, input.readBytes())
        val done = sink.completions.last()
        assertTrue(done.isComplete)
        assertEquals("counted, with no Content-Length", 5_000L, done.body?.byteSize)
        assertTrue(done.body!!.truncated)
        assertTrue(sink.failures.isEmpty())
    }

    @Test
    fun `a body that fails while the app reads it fails the transaction`() {
        val sink = RecordingSink()
        val connection = open(sink, FakeConnection.Spec(ByteArray(5_000), "audio/mpeg", failAfter = 2_048))

        val input = connection.inputStream

        assertThrows(IOException::class.java) { input.readBytes() }
        assertEquals("Body failed after 2048 bytes: connection reset", sink.failures.single())
        assertFalse("no completion after the failure", sink.completions.last().isComplete)
    }

    @Test
    fun `a body the app closes early completes with its declared size`() {
        val sink = RecordingSink()
        val connection = open(sink, FakeConnection.Spec(ByteArray(5_000), "audio/mpeg", declared = true))

        val input = connection.inputStream
        input.read(ByteArray(100))
        input.close()

        val done = sink.completions.last()
        assertTrue(done.isComplete)
        assertEquals(5_000L, done.body?.byteSize)
        assertNull(sink.failures.firstOrNull())
    }

    @Test
    fun `an audio body is counted as the app reads it, but not copied or kept`() {
        val sink = RecordingSink()
        val connection = open(sink, FakeConnection.Spec(ByteArray(5_000) { 'x'.code.toByte() }, "audio/mpeg"))

        connection.inputStream.readBytes()

        val body = sink.completions.last().body!!
        assertNull(body.text)
        assertNull(body.bytes)
        assertEquals(5_000L, body.byteSize)
    }

    @Test
    fun `a redirect the platform followed is the final URL, and the app still sees it`() {
        val sink = RecordingSink()
        val connection = open(sink, FakeConnection.Spec("ok".toByteArray(), "text/plain", redirectTo = "http://cdn.example.com/episode"))

        assertEquals(200, connection.responseCode)

        assertEquals("http://cdn.example.com/episode", connection.url.toString())
        assertEquals("http://cdn.example.com/episode", sink.completions.single().finalUrl)
        assertTrue(sink.completions.single().priorResponses.isEmpty())
    }

    @Test
    fun `the headers the platform adds are left out, and the protocol is read from one`() {
        val sink = RecordingSink()
        val platformHeaders =
            mapOf(
                "X-Android-Sent-Millis" to "1790605327000",
                "X-Android-Received-Millis" to "1790605327090",
                "X-Android-Response-Source" to "NETWORK 200",
                "X-Android-Selected-Protocol" to "http/1.1",
            )
        val connection = open(sink, FakeConnection.Spec("ok".toByteArray(), "text/plain", headers = platformHeaders + ("X-Request-Id" to "r1")))

        assertEquals(200, connection.responseCode)
        connection.inputStream.readBytes()

        sink.completions.forEach { response ->
            assertEquals("http/1.1", response.protocol)
            assertEquals(listOf("Content-Type", "X-Request-Id"), response.headers.names().sorted())
        }
        assertEquals("the app still reads them", "NETWORK 200", connection.headerFields["X-Android-Response-Source"]?.single())
    }

    @Test
    fun `a response without the platform's protocol header has no protocol`() {
        val sink = RecordingSink()
        val connection = open(sink, FakeConnection.Spec("ok".toByteArray(), "text/plain"))

        connection.inputStream.readBytes()

        assertNull(sink.completions.last().protocol)
    }

    @Test
    fun `a request that was not redirected has no final URL`() {
        val sink = RecordingSink()
        val connection = open(sink, FakeConnection.Spec("ok".toByteArray(), "text/plain"))

        connection.inputStream.readBytes()

        assertEquals("http://example.com/episode", connection.url.toString())
        assertNull(sink.completions.last().finalUrl)
    }

    @Test
    fun `a body written after an explicit connect is captured`() {
        val sink = RecordingSink()
        val connection = open(sink, FakeConnection.Spec("ok".toByteArray(), "text/plain"))
        connection.requestMethod = "POST"
        connection.doOutput = true

        connection.connect()
        assertTrue("not recorded before the body is written", sink.requests.isEmpty())
        connection.outputStream.use { it.write(byteArrayOf(0x78, 0)) }

        val request = sink.requests.single()
        assertEquals("POST", request.method)
        assertEquals(2L, request.body?.byteSize)
        connection.inputStream.readBytes()
        assertEquals(1, sink.requests.size)
        assertTrue(sink.completions.last().isComplete)
    }

    @Test
    fun `a request with a body whose connect fails is recorded as failed`() {
        val sink = RecordingSink()
        val connection = open(sink, FakeConnection.Spec("ok".toByteArray(), "text/plain", connectError = "timeout"))
        connection.requestMethod = "POST"
        connection.doOutput = true

        val thrown = assertThrows(IOException::class.java) { connection.connect() }

        assertEquals("the app gets the platform's exception", "timeout", thrown.message)
        assertEquals("POST", sink.requests.single().method)
        assertEquals("timeout", sink.failures.single())
    }

    @Test
    fun `a request with no body is recorded when the app connects`() {
        val sink = RecordingSink()
        val connection = open(sink, FakeConnection.Spec("ok".toByteArray(), "text/plain"))

        connection.connect()

        assertEquals("GET", sink.requests.single().method)
        assertNull(sink.requests.single().body)
    }

    @Test
    fun `an error status that the app meets by opening the body is a response, not a failure`() {
        val sink = RecordingSink()
        val connection = open(sink, FakeConnection.Spec("[]".toByteArray(), "application/json", status = 404))

        val thrown = assertThrows(FileNotFoundException::class.java) { connection.inputStream }

        assertEquals("the app gets the platform's exception", "http://example.com/episode", thrown.message)
        assertTrue(sink.failures.isEmpty())
        val response = sink.completions.single()
        assertEquals(404, response.statusCode)
        assertTrue(response.isComplete)
        assertEquals(404, connection.responseCode)
        assertEquals(1, sink.completions.size)
    }

    @Test
    fun `an error body that the app reads after opening the body failed is captured`() {
        val sink = RecordingSink()
        val connection = open(sink, FakeConnection.Spec("""{"error":"gone"}""".toByteArray(), "application/json", status = 500))

        assertThrows(FileNotFoundException::class.java) { connection.inputStream }
        connection.errorStream!!.readBytes()

        val done = sink.completions.last()
        assertEquals(500, done.statusCode)
        assertTrue(done.isComplete)
        assertEquals("""{"error":"gone"}""", done.body?.text)
        assertTrue(sink.failures.isEmpty())
    }

    @Test
    fun `a request that fails before a response when the app opens the body is recorded as failed`() {
        val sink = RecordingSink()
        val connection = open(sink, FakeConnection.Spec("ok".toByteArray(), "text/plain", connectError = "timeout"))

        assertThrows(IOException::class.java) { connection.inputStream }

        assertEquals("timeout", sink.failures.single())
        assertTrue(sink.completions.isEmpty())
    }

    private fun open(sink: NetworkCaptureSink, spec: FakeConnection.Spec): HttpURLConnection {
        val platform =
            object : URLStreamHandler() {
                override fun openConnection(u: URL): URLConnection = FakeConnection(u, spec)
            }
        val handler =
            HttpUrlConnectionCapture.CapturingStreamHandler(
                real = platform,
                sink = sink,
                isPaused = { false },
                captureFilter = SafeCaptureFilter(NoOpNetworkCaptureFilter),
                maxBodySize = 1024,
                secure = false,
            )
        return URL(null, "http://example.com/episode", handler).openConnection() as HttpURLConnection
    }

    /** Answers with [Spec.status] and [Spec.body], which can fail after [Spec.failAfter] bytes. */
    private class FakeConnection(url: URL, private val spec: Spec) : HttpURLConnection(url) {
        class Spec(
            val body: ByteArray,
            val contentType: String,
            val failAfter: Int = -1,
            val declared: Boolean = false,
            val redirectTo: String? = null,
            val connectError: String? = null,
            val status: Int = HTTP_OK,
            val headers: Map<String, String> = emptyMap(),
        )

        private val stream =
            object : InputStream() {
                private var offset = 0

                override fun read(): Int {
                    val one = ByteArray(1)
                    return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (offset == spec.failAfter) throw IOException("connection reset")
                    if (offset == spec.body.size) return -1
                    val limit = if (spec.failAfter >= 0) spec.failAfter else spec.body.size
                    val count = minOf(len, 512, limit - offset)
                    spec.body.copyInto(b, off, offset, offset + count)
                    offset += count
                    return count
                }
            }

        override fun connect() {
            spec.connectError?.let { throw IOException(it) }
            connected = true
        }

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false

        // The platform's connection moves its URL when it follows a redirect.
        override fun getResponseCode(): Int {
            spec.connectError?.let { throw IOException(it) }
            spec.redirectTo?.let { url = URL(it) }
            return spec.status
        }

        // As the platform does, any status of 400 or more throws here, and its body
        // is in the error stream.
        override fun getInputStream(): InputStream {
            if (getResponseCode() >= HTTP_BAD_REQUEST) throw FileNotFoundException(url.toString())
            return stream
        }

        override fun getErrorStream(): InputStream? = if (spec.status >= HTTP_BAD_REQUEST) stream else null

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun getHeaderFields(): MutableMap<String, MutableList<String>> {
            val fields = mutableMapOf("Content-Type" to mutableListOf(spec.contentType))
            if (spec.declared) fields["Content-Length"] = mutableListOf(spec.body.size.toString())
            spec.headers.forEach { (name, value) -> fields[name] = mutableListOf(value) }
            return fields
        }
    }

    private class RecordingSink : NetworkCaptureSink {
        class Recorded(val method: String, val body: CapturedBody?)

        val requests = mutableListOf<Recorded>()
        val completions = mutableListOf<CapturedResponse>()
        val failures = mutableListOf<String>()

        override fun beginRequest(
            url: String,
            method: String,
            headers: Headers,
            requestBody: CapturedBody?,
            contentType: MediaType?,
        ): TransactionId {
            requests += Recorded(method, requestBody)
            return TransactionId("tx")
        }

        override fun findMockRule(url: String, method: String): MockRule? = null

        override fun completeRequest(id: TransactionId, response: CapturedResponse) {
            completions += response
        }

        override fun failRequest(id: TransactionId, durationMs: Long, error: String) {
            failures += error
        }
    }
}
