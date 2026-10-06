package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.MediaType
import io.github.twinsen81.lustro.network.CapturedBody
import io.github.twinsen81.lustro.network.CapturedResponse
import io.github.twinsen81.lustro.network.MockRule
import io.github.twinsen81.lustro.network.NetworkCaptureSink
import io.github.twinsen81.lustro.network.NoOpNetworkCaptureFilter
import io.github.twinsen81.lustro.network.TransactionId
import java.io.IOException
import java.io.InputStream
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
    fun `a request that was not redirected has no final URL`() {
        val sink = RecordingSink()
        val connection = open(sink, FakeConnection.Spec("ok".toByteArray(), "text/plain"))

        connection.inputStream.readBytes()

        assertEquals("http://example.com/episode", connection.url.toString())
        assertNull(sink.completions.last().finalUrl)
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

    /** Answers with a 200 and [Spec.body], which can fail after [Spec.failAfter] bytes. */
    private class FakeConnection(url: URL, private val spec: Spec) : HttpURLConnection(url) {
        class Spec(
            val body: ByteArray,
            val contentType: String,
            val failAfter: Int = -1,
            val declared: Boolean = false,
            val redirectTo: String? = null,
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
            connected = true
        }

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false

        // The platform's connection moves its URL when it follows a redirect.
        override fun getResponseCode(): Int {
            spec.redirectTo?.let { url = URL(it) }
            return HTTP_OK
        }

        override fun getInputStream(): InputStream {
            responseCode
            return stream
        }

        override fun getHeaderFields(): MutableMap<String, MutableList<String>> {
            val fields = mutableMapOf("Content-Type" to mutableListOf(spec.contentType))
            if (spec.declared) fields["Content-Length"] = mutableListOf(spec.body.size.toString())
            return fields
        }
    }

    private class RecordingSink : NetworkCaptureSink {
        val completions = mutableListOf<CapturedResponse>()
        val failures = mutableListOf<String>()

        override fun beginRequest(
            url: String,
            method: String,
            headers: Headers,
            requestBody: CapturedBody?,
            contentType: MediaType?,
        ): TransactionId = TransactionId("tx")

        override fun findMockRule(url: String, method: String): MockRule? = null

        override fun completeRequest(id: TransactionId, response: CapturedResponse) {
            completions += response
        }

        override fun failRequest(id: TransactionId, durationMs: Long, error: String) {
            failures += error
        }
    }
}
