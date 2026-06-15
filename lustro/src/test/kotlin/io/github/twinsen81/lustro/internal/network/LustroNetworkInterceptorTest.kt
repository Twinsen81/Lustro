package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.MediaType
import io.github.twinsen81.lustro.network.CapturedBody
import io.github.twinsen81.lustro.network.MockRule
import io.github.twinsen81.lustro.network.NetworkCaptureSink
import io.github.twinsen81.lustro.network.NoOpNetworkClassifier
import io.github.twinsen81.lustro.network.Redactor
import io.github.twinsen81.lustro.network.TransactionId
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [LustroNetworkInterceptor]. The interceptor talks to the
 * [NetworkCaptureSink] SPI with explicit gates. We exercise it against a real
 * [NetworkTrafficStore] (identity redactor so bodies pass through verbatim) and
 * against a recording fake sink for short-circuit/throttle behaviour.
 */
class LustroNetworkInterceptorTest {
    /** Identity redactor so body/url assertions are not perturbed by redaction. */
    private object IdentityRedactor : Redactor {
        override fun redactUrl(url: String): String = url

        override fun redactHeaderValue(name: String, value: String): String = value

        override fun redactBody(body: String, contentType: MediaType?): String = body
    }

    private fun store(): NetworkTrafficStore =
        NetworkTrafficStore(
            maxTransactions = 1000,
            redactor = IdentityRedactor,
            classifier = NoOpNetworkClassifier,
            storage = null,
        )

    private fun interceptor(
        sink: NetworkCaptureSink,
        captureEnabled: Boolean = true,
        throttleDelayMs: Int = 0,
        onMockHit: (String) -> Unit = {},
        maxBodySize: Long = 256L * 1024,
    ): LustroNetworkInterceptor =
        LustroNetworkInterceptor(
            sink = sink,
            captureEnabled = { captureEnabled },
            throttleDelayMs = { throttleDelayMs },
            incrementMockHit = onMockHit,
            maxBodySize = maxBodySize,
        )

    @Test
    fun `event stream responses are captured while body is consumed`() {
        val store = store()
        val interceptor = interceptor(store)
        val request = Request.Builder().url("https://example.com/api/ui-skin/generate").build()
        val firstChunk = "event: ping\n"
        val secondChunk = "data: {\"event\":\"ping\",\"seq\":1,\"payload\":{}}\n\n"
        val content = firstChunk + secondChunk
        val body =
            TrackingResponseBody(
                contentType = "text/event-stream".toMediaType(),
                content = content,
                chunkSize = firstChunk.toByteArray(Charsets.UTF_8).size,
            )
        val response = responseFor(request, body)

        val result = interceptor.intercept(FakeChain(request, response))

        // The response is wrapped so the body can be captured lazily as it's read.
        assertNotSame(response, result)
        assertFalse(body.sourceRequested)
        var transaction = store.getTransactions().single()
        // Before any bytes are read, the body is still null and the transaction is
        // recorded as IN FLIGHT (responseComplete=false) — the interceptor passes
        // complete=false for the initial event-stream record (responseComplete
        // flips through CapturedBody/complete as chunks arrive).
        assertNull(transaction.responseBody)
        assertFalse(transaction.responseComplete)

        val sink = Buffer()
        val source = result.body!!.source()
        assertEquals(1L, source.read(sink, 1))

        // First chunk is captured progressively as the consumer reads — still in flight.
        assertTrue(body.sourceRequested)
        transaction = store.getTransactions().single()
        assertEquals(firstChunk, transaction.responseBody)
        assertFalse(transaction.responseComplete)

        while (source.read(sink, 8_192) != -1L) {
            // Drain the stream.
        }

        // The full stream content is delivered to the caller and captured in full;
        // at EOF the interceptor flips complete=true.
        assertEquals(content, sink.readUtf8())
        transaction = store.getTransactions().single()
        assertEquals(content, transaction.responseBody)
        assertTrue(transaction.responseComplete)
    }

    @Test
    fun `non event stream responses use regular response capture`() {
        val store = store()
        val interceptor = interceptor(store)
        val request = Request.Builder().url("https://example.com/status").build()
        val body = TrackingResponseBody("text/plain".toMediaType(), "ok", chunkSize = 2)
        val response = responseFor(request, body)

        val result = interceptor.intercept(FakeChain(request, response))

        assertSame(response, result)
        assertTrue(body.sourceRequested)
        val transaction = store.getTransactions().single()
        assertEquals("ok", transaction.responseBody)
        assertEquals(2L, transaction.responseBodyBytes)
        assertTrue(transaction.responseComplete)
    }

    @Test
    fun `capture disabled passes through without recording`() {
        val store = store()
        val interceptor = interceptor(store, captureEnabled = false)
        val request = Request.Builder().url("https://example.com/status").build()
        val body = TrackingResponseBody("text/plain".toMediaType(), "ok", chunkSize = 2)
        val response = responseFor(request, body)

        val result = interceptor.intercept(FakeChain(request, response))

        // Pass-through: same response, body not peeked, nothing recorded.
        assertSame(response, result)
        assertFalse(body.sourceRequested)
        assertTrue(store.getTransactions().isEmpty())
    }

    @Test
    fun `mock rule short-circuits without hitting the network`() {
        val rule =
            MockRuleImpl(
                id = "rule-1",
                name = "mocked",
                urlPattern = "example.com/mocked",
                statusCode = 201,
                responseHeaders = Headers.of("Content-Type" to "application/json"),
                responseBody = """{"mocked":true}""",
            )
        var hitId: String? = null
        val sink = RecordingSink(mockRule = rule)
        val interceptor = interceptor(sink, onMockHit = { hitId = it })

        val request = Request.Builder().url("https://example.com/mocked/x").build()
        val proceedResponse = responseFor(request, TrackingResponseBody("text/plain".toMediaType(), "real", 4))
        val chain = FakeChain(request, proceedResponse)

        val result = interceptor.intercept(chain)

        assertEquals(0, chain.proceedCount) // network never hit
        assertEquals("rule-1", hitId)
        assertEquals(201, result.code)
        assertEquals("""{"mocked":true}""", result.body!!.string())
        // The mock completion is reported as mocked.
        assertEquals(1, sink.completions.size)
        assertTrue(sink.completions.single().isMocked)
        assertEquals(201, sink.completions.single().statusCode)
    }

    @Test
    fun `throttle sleeps before proceeding`() {
        val store = store()
        val interceptor = interceptor(store, throttleDelayMs = 120)
        val request = Request.Builder().url("https://example.com/status").build()
        val response = responseFor(request, TrackingResponseBody("text/plain".toMediaType(), "ok", 2))

        val start = System.currentTimeMillis()
        interceptor.intercept(FakeChain(request, response))
        val elapsed = System.currentTimeMillis() - start

        assertTrue("expected >= ~100ms throttle, was $elapsed", elapsed >= 100)
    }

    @Test
    fun `throttle is interruptible and surfaces an IOException`() {
        val sink = RecordingSink()
        val interceptor = interceptor(sink, throttleDelayMs = 60_000)
        val request = Request.Builder().url("https://example.com/slow").build()
        val response = responseFor(request, TrackingResponseBody("text/plain".toMediaType(), "ok", 2))

        val thrown = arrayOfNulls<Throwable>(1)
        val worker =
            Thread {
                try {
                    interceptor.intercept(FakeChain(request, response))
                } catch (t: Throwable) {
                    thrown[0] = t
                }
            }
        worker.start()
        // Give the worker a moment to enter Thread.sleep, then interrupt.
        Thread.sleep(200)
        worker.interrupt()
        worker.join(5_000)

        assertTrue("expected IOException, got ${thrown[0]}", thrown[0] is IOException)
        assertEquals("Throttle interrupted", thrown[0]?.message)
    }

    @Test
    fun `IOException path records a failure and rethrows`() {
        val sink = RecordingSink()
        val interceptor = interceptor(sink)
        val request = Request.Builder().url("https://example.com/boom").build()
        val boom = IOException("connection reset")
        val chain = FakeChain(request, response = null, proceedError = boom)

        val thrown = assertThrows(IOException::class.java) { interceptor.intercept(chain) }
        assertSame(boom, thrown)
        assertEquals(1, sink.failures.size)
        assertEquals("connection reset", sink.failures.single().error)
    }

    @Test
    fun `request body is captured into the sink`() {
        val store = store()
        val interceptor = interceptor(store)
        val reqBody = """{"hello":"world"}""".toRequestBody("application/json".toMediaType())
        val request =
            Request.Builder().url("https://example.com/post").post(reqBody).build()
        val response = responseFor(request, TrackingResponseBody("text/plain".toMediaType(), "ok", 2))

        interceptor.intercept(FakeChain(request, response))

        val tx = store.getTransactions().single()
        assertEquals("POST", tx.method)
        assertEquals("""{"hello":"world"}""", tx.requestBody)
    }

    @Test
    fun `truncated unknown length non event stream responses do not report exact byte count`() {
        val store = store()
        val interceptor = interceptor(store)
        val request = Request.Builder().url("https://example.com/large").build()
        val body =
            TrackingResponseBody(
                contentType = "text/plain".toMediaType(),
                content = "x".repeat(256 * 1024 + 1),
                chunkSize = 8_192,
            )
        val response = responseFor(request, body)

        interceptor.intercept(FakeChain(request, response))

        val tx = store.getTransactions().single()
        assertEquals(256 * 1024, tx.responseBody!!.length)
        assertTrue(tx.responseComplete)
    }

    @Test
    fun `oversized non event stream response reports truncated and the full byte size`() {
        val store = store()
        val interceptor = interceptor(store, maxBodySize = 16)
        val fullText = "x".repeat(64) // well over the 16-byte cap
        val request = Request.Builder().url("https://example.com/big").build()
        val body =
            TrackingResponseBody(
                contentType = "text/plain".toMediaType(),
                content = fullText,
                chunkSize = 8,
                // Declared Content-Length => byteSize is the FULL size, not the cap.
                declaredLength = fullText.toByteArray(Charsets.UTF_8).size.toLong(),
            )
        val response = responseFor(request, body)

        interceptor.intercept(FakeChain(request, response))

        val tx = store.getTransactions().single()
        assertTrue(tx.responseBodyTruncated)
        // The captured text is only the 16-byte prefix...
        assertEquals(16, tx.responseBody!!.length)
        // ...but the reported byte size is the full body, not the truncated prefix.
        assertEquals(64L, tx.responseBodyBytes)
        assertTrue(tx.responseComplete)
    }

    @Test
    fun `oversized request body reports truncated and the full byte size`() {
        val store = store()
        val interceptor = interceptor(store, maxBodySize = 16)
        val fullText = "y".repeat(64)
        val reqBody = fullText.toRequestBody("text/plain".toMediaType())
        val request =
            Request.Builder().url("https://example.com/upload").post(reqBody).build()
        val response = responseFor(request, TrackingResponseBody("text/plain".toMediaType(), "ok", 2))

        interceptor.intercept(FakeChain(request, response))

        val tx = store.getTransactions().single()
        assertTrue(tx.requestBodyTruncated)
        // Captured prefix is capped at 16 bytes...
        assertEquals(16, tx.requestBody!!.length)
        // ...while the reported byte size is the full request body size.
        assertEquals(64L, tx.requestBodyBytes)
    }


    private fun responseFor(request: Request, body: ResponseBody): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body)
            .build()

    private class TrackingResponseBody(
        private val contentType: okhttp3.MediaType,
        private val content: String,
        private val chunkSize: Int,
        private val declaredLength: Long = -1L,
    ) : ResponseBody() {
        var sourceRequested: Boolean = false
            private set

        override fun contentType(): okhttp3.MediaType = contentType

        override fun contentLength(): Long = declaredLength

        override fun source(): BufferedSource {
            sourceRequested = true
            val bytes = content.toByteArray(Charsets.UTF_8)
            return object : Source {
                private var offset = 0

                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (offset == bytes.size) return -1L
                    val bytesToRead =
                        minOf(byteCount, chunkSize.toLong(), (bytes.size - offset).toLong()).toInt()
                    sink.write(bytes, offset, bytesToRead)
                    offset += bytesToRead
                    return bytesToRead.toLong()
                }

                override fun timeout(): Timeout = Timeout.NONE

                override fun close() = Unit
            }.buffer()
        }
    }

    private class FakeChain(
        private val request: Request,
        private val response: Response?,
        private val proceedError: IOException? = null,
    ) : Interceptor.Chain {
        var proceedCount: Int = 0
            private set

        override fun request(): Request = request

        @Throws(IOException::class)
        override fun proceed(request: Request): Response {
            proceedCount++
            proceedError?.let { throw it }
            return response ?: error("no response configured")
        }

        override fun connection(): Connection? = null

        override fun call(): Call = error("not used")

        override fun connectTimeoutMillis(): Int = 0

        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

        override fun readTimeoutMillis(): Int = 0

        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

        override fun writeTimeoutMillis(): Int = 0

        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }

    private data class Completion(
        val id: TransactionId,
        val statusCode: Int,
        val isMocked: Boolean,
        val complete: Boolean,
        val responseBody: CapturedBody?,
    )

    private data class Failure(val id: TransactionId, val error: String)

    private class RecordingSink(private val mockRule: MockRule? = null) : NetworkCaptureSink {
        val completions = mutableListOf<Completion>()
        val failures = mutableListOf<Failure>()
        val requestBodies = mutableListOf<CapturedBody?>()
        private var counter = 0

        override fun beginRequest(
            url: String,
            method: String,
            headers: Headers,
            requestBody: CapturedBody?,
            contentType: MediaType?,
        ): TransactionId {
            requestBodies.add(requestBody)
            return TransactionId("tx-${counter++}")
        }

        override fun findMockRule(url: String, method: String): MockRule? =
            mockRule?.takeIf { (it as MockRuleImpl).matches(url, method) }

        override fun completeRequest(
            id: TransactionId,
            statusCode: Int,
            responseHeaders: Headers,
            responseBody: CapturedBody?,
            durationMs: Long,
            isMocked: Boolean,
            complete: Boolean,
        ) {
            completions.add(Completion(id, statusCode, isMocked, complete, responseBody))
        }

        override fun failRequest(id: TransactionId, durationMs: Long, error: String) {
            failures.add(Failure(id, error))
        }
    }

}
