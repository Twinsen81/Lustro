package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.MediaType
import io.github.twinsen81.lustro.network.CapturedBody
import io.github.twinsen81.lustro.network.CapturedResponse
import io.github.twinsen81.lustro.network.DefaultRedactor
import io.github.twinsen81.lustro.network.MockRule
import io.github.twinsen81.lustro.network.NetworkCaptureFilter
import io.github.twinsen81.lustro.network.NetworkCaptureRequest
import io.github.twinsen81.lustro.network.NetworkCaptureSink
import io.github.twinsen81.lustro.network.NoOpNetworkCaptureFilter
import io.github.twinsen81.lustro.network.NoOpNetworkClassifier
import io.github.twinsen81.lustro.network.Redactor
import io.github.twinsen81.lustro.network.TransactionId
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.GzipSink
import okio.GzipSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [LustroNetworkInterceptor]. The interceptor talks to the
 * [NetworkCaptureSink] SPI with explicit gates. We exercise it against a real
 * [NetworkTrafficStore] (identity redactor so bodies pass through verbatim,
 * unless a test checks redaction; capture work inline unless a test checks the
 * hand-off) and against a recording fake sink for short-circuit/throttle
 * behaviour.
 */
class LustroNetworkInterceptorTest {
    /** Identity redactor so body/url assertions are not perturbed by redaction. */
    private object IdentityRedactor : Redactor {
        override fun redactUrl(url: String): String = url

        override fun redactHeaderValue(name: String, value: String): String = value

        override fun redactBody(body: String, contentType: MediaType?): String = body
    }

    private fun store(
        redactor: Redactor = IdentityRedactor,
        worker: CaptureWorker = inlineCaptureWorker(),
    ): NetworkTrafficStore =
        NetworkTrafficStore(
            maxTransactions = 1000,
            redactor = redactor,
            classifier = NoOpNetworkClassifier,
            storage = null,
            worker = worker,
        )

    private fun interceptor(
        sink: NetworkCaptureSink,
        captureEnabled: Boolean = true,
        shouldCapture: ((NetworkCaptureRequest) -> Boolean)? = null,
        throttleDelayMs: Int = 0,
        onMockHit: (String) -> Unit = {},
        maxBodySize: Long = 256L * 1024,
    ): LustroNetworkInterceptor =
        LustroNetworkInterceptor(
            sink = sink,
            captureEnabled = { captureEnabled },
            captureFilter = SafeCaptureFilter(shouldCapture?.let { NetworkCaptureFilter.of("test filter", it) } ?: NoOpNetworkCaptureFilter),
            throttleDelayMs = { throttleDelayMs },
            incrementMockHit = onMockHit,
            maxBodySize = maxBodySize,
            recordThrottle = { id, delayMs -> (sink as? NetworkTrafficStore)?.recordThrottle(id, delayMs) },
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
        assertEquals("http/1.1", transaction.protocol)

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
    fun `the call returns before its capture is redacted`() {
        val executor = ManualExecutor()
        val redactor = CountingRedactor()
        val store = store(redactor = redactor, worker = CaptureWorker(executor))
        val interceptor = interceptor(store)
        val reqBody = """{"password":"hunter2"}""".toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("https://example.com/login").post(reqBody).build()
        val body = TrackingResponseBody("application/json".toMediaType(), """{"token":"abc"}""", chunkSize = 8_192)

        val result = interceptor.intercept(FakeChain(request, responseFor(request, body)))

        assertEquals("""{"token":"abc"}""", result.body!!.string())
        assertEquals(0, redactor.bodies)
        assertTrue(store.getTransactions().isEmpty())

        executor.runAll()

        val tx = store.getTransactions().single()
        assertEquals("""{"password":"[REDACTED]"}""", tx.requestBody)
        assertEquals("""{"token":"[REDACTED]"}""", tx.responseBody)
        assertTrue(tx.responseComplete)
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
    fun `a request the filter skips passes through without being read or recorded`() {
        val store = store()
        val interceptor = interceptor(store, shouldCapture = { false })
        val requestBody = TrackingRequestBody("""{"event":"open"}""")
        val request = Request.Builder().url("https://analytics.example.com/collect").post(requestBody).build()
        val body = TrackingResponseBody("application/json".toMediaType(), "{}", chunkSize = 2)
        val response = responseFor(request, body)

        val result = interceptor.intercept(FakeChain(request, response))

        assertSame(response, result)
        assertFalse(requestBody.written)
        assertFalse(body.sourceRequested)
        assertTrue(store.getTransactions().isEmpty())
    }

    @Test
    fun `the filter sees the URL, method, and headers of the request`() {
        var seen: NetworkCaptureRequest? = null
        val interceptor =
            interceptor(
                RecordingSink(),
                shouldCapture = {
                    seen = it
                    true
                },
            )
        val request =
            Request.Builder()
                .url("https://example.com/items?page=2")
                .header("X-No-Capture", "1")
                .delete()
                .build()

        interceptor.intercept(FakeChain(request, responseFor(request, TrackingResponseBody("text/plain".toMediaType(), "ok", 2))))

        assertEquals("https://example.com/items?page=2", seen!!.url)
        assertEquals("DELETE", seen!!.method)
        assertEquals("1", seen!!.headers.get("X-No-Capture"))
    }

    @Test
    fun `a request the filter skips is still mocked and throttled`() {
        val rule = MockRuleImpl(id = "rule-1", name = "mocked", urlPattern = "example.com/mocked", statusCode = 201)
        var hitId: String? = null
        val sink = RecordingSink(mockRule = rule)
        val interceptor = interceptor(sink, shouldCapture = { false }, throttleDelayMs = 120, onMockHit = { hitId = it })
        val request = Request.Builder().url("https://example.com/mocked/x").build()
        val chain = FakeChain(request, responseFor(request, TrackingResponseBody("text/plain".toMediaType(), "real", 4)))

        val start = System.currentTimeMillis()
        val result = interceptor.intercept(chain)
        val elapsed = System.currentTimeMillis() - start

        assertEquals(201, result.code)
        assertEquals(0, chain.proceedCount)
        assertEquals("rule-1", hitId)
        assertTrue("expected >= ~100ms throttle, was $elapsed", elapsed >= 100)
        assertEquals(0, sink.begun)
        assertTrue(sink.completions.isEmpty())
    }

    @Test
    fun `a filter that throws does not fail the call, and the request is captured`() {
        val store = store()
        val interceptor = interceptor(store, shouldCapture = { error("filter bug") })
        val request = Request.Builder().url("https://example.com/status").build()
        val response = responseFor(request, TrackingResponseBody("text/plain".toMediaType(), "ok", 2))

        val result = interceptor.intercept(FakeChain(request, response))

        assertSame(response, result)
        assertEquals("https://example.com/status", store.getTransactions().single().url)
    }

    @Test
    fun `the filter is not asked while capture is disabled`() {
        var asked = 0
        val interceptor =
            interceptor(
                RecordingSink(),
                captureEnabled = false,
                shouldCapture = {
                    asked++
                    true
                },
            )
        val request = Request.Builder().url("https://example.com/status").build()

        interceptor.intercept(FakeChain(request, responseFor(request, TrackingResponseBody("text/plain".toMediaType(), "ok", 2))))

        assertEquals(0, asked)
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
        // The mock response's HTTP/1.1 was never negotiated.
        assertNull(sink.completions.single().protocol)
    }

    @Test
    fun `a rule that cannot be served fails the call with an IOException`() {
        // A rule stored by an older build, or by a MockRuleStorage of the app's
        // own: whatever it does, it must not throw a RuntimeException out of the
        // app's own call, which for an enqueued call kills the process.
        val rule =
            MockRuleImpl(
                id = "rule-bad",
                name = "unbuildable",
                urlPattern = "example.com/mocked",
                responseHeaders = Headers.of("Content-Type" to "not a media type"),
                responseBody = "{}",
            )
        var hitId: String? = null
        val sink = RecordingSink(mockRule = rule)
        val interceptor = interceptor(sink, onMockHit = { hitId = it })

        val request = Request.Builder().url("https://example.com/mocked/x").build()
        val proceedResponse = responseFor(request, TrackingResponseBody("text/plain".toMediaType(), "real", 4))
        val chain = FakeChain(request, proceedResponse)

        val failure = assertThrows(IOException::class.java) { interceptor.intercept(chain) }

        assertTrue(failure.message!!, "rule-bad" in failure.message!!)
        assertEquals(0, chain.proceedCount) // the real request is not sent in its place
        assertNull(hitId) // a rule that never produced a response did not hit
        assertTrue(sink.completions.isEmpty())
        assertEquals(1, sink.failures.size)
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
    fun `a throttled request is listed from the call, with the wait kept apart from its duration`() {
        val store = store()
        val interceptor = interceptor(store, throttleDelayMs = 150)
        val request = Request.Builder().url("https://example.com/status").build()
        val response = responseFor(request, TrackingResponseBody("text/plain".toMediaType(), "ok", 2))

        val called = System.currentTimeMillis()
        interceptor.intercept(FakeChain(request, response))

        val tx = store.getTransactions().single()
        assertEquals(150L, tx.throttledMs)
        assertTrue("startedAt should be when the app made the call", tx.startedAt - called < 100)
        assertTrue("durationMs should leave the throttle out, was ${tx.durationMs}", tx.durationMs!! < 150)
        assertTrue("completedAt should come after the throttle", tx.completedAt!! - tx.startedAt >= 150)
    }

    @Test
    fun `a request that isn't throttled has no throttledMs`() {
        val store = store()
        val request = Request.Builder().url("https://example.com/status").build()
        val response = responseFor(request, TrackingResponseBody("text/plain".toMediaType(), "ok", 2))

        interceptor(store).intercept(FakeChain(request, response))

        assertNull(store.getTransactions().single().throttledMs)
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
        // Capture began before the wait, so the request is recorded as failed, not left in flight.
        assertEquals(1, sink.failures.size)
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
    fun `the negotiated protocol and both content types are captured`() {
        val store = store()
        val interceptor = interceptor(store)
        val reqBody = """{"q":1}""".toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("https://example.com/search").post(reqBody).build()
        val body = TrackingResponseBody("application/json; charset=utf-8".toMediaType(), "[]", chunkSize = 2)
        val response =
            responseFor(
                request,
                body,
                protocol = Protocol.HTTP_2,
                headers = okhttp3.Headers.headersOf("Content-Type", "application/json; charset=utf-8"),
            )

        interceptor.intercept(FakeChain(request, response))

        val tx = store.getTransactions().single()
        assertEquals("h2", tx.protocol)
        // OkHttp adds the charset to a String body's media type; that's what went out.
        assertEquals("application/json; charset=utf-8", tx.requestContentType)
        assertEquals("application/json; charset=utf-8", tx.responseContentType)
    }

    @Test
    fun `a Content-Type header stands in for a body that declares no media type`() {
        val store = store()
        val interceptor = interceptor(store)
        val request =
            Request.Builder()
                .url("https://example.com/upload")
                .header("Content-Type", "application/xml")
                .post("<order id=\"7\"/>".toByteArray().toRequestBody(null))
                .build()
        val response = responseFor(request, TrackingResponseBody("text/plain".toMediaType(), "ok", 2))

        interceptor.intercept(FakeChain(request, response))

        val tx = store.getTransactions().single()
        assertEquals("application/xml", tx.requestContentType)
        // Captured as the text the header says it is, not skipped as untyped bytes.
        assertEquals("<order id=\"7\"/>", tx.requestBody)
    }

    @Test
    fun `a typeless body captured by its header still reaches the server intact`() {
        // Capturing writes the body once into a buffer before OkHttp writes it to
        // the network; through a real client, the server must still get all of it.
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("ok"))
        server.start()
        try {
            val store = store()
            val client = OkHttpClient.Builder().addInterceptor(interceptor(store)).build()
            val json = """{"id":7,"note":"héllo"}"""
            val request =
                Request.Builder()
                    .url(server.url("/orders"))
                    .header("Content-Type", "application/json")
                    .post(json.toByteArray().toRequestBody(null))
                    .build()

            client.newCall(request).execute().use { assertEquals(200, it.code) }

            val sent = server.takeRequest()
            assertEquals(json, sent.body.readUtf8())
            assertEquals("application/json", sent.getHeader("Content-Type"))
            val tx = store.getTransactions().single()
            assertEquals("application/json", tx.requestContentType)
            assertEquals(json, tx.requestBody)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a failure before a response records no protocol`() {
        val store = store()
        val interceptor = interceptor(store)
        val request = Request.Builder().url("https://example.com/boom").build()

        assertThrows(IOException::class.java) {
            interceptor.intercept(FakeChain(request, response = null, proceedError = IOException("reset")))
        }

        val tx = store.getTransactions().single()
        assertEquals("reset", tx.error)
        assertNull(tx.protocol)
        assertNull(tx.responseContentType)
        assertNotNull(tx.completedAt)
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
    fun `a truncated body of unknown length is sized once the app reads it to the end`() {
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

        val result = interceptor.intercept(FakeChain(request, response))

        val inFlight = store.getTransactions().single()
        assertEquals(256 * 1024, inFlight.responseBody!!.length)
        assertFalse(inFlight.responseComplete)
        assertNull("the full size isn't known from the capture alone", inFlight.responseBodyBytes)

        assertEquals(256 * 1024 + 1, result.body!!.string().length)

        val tx = store.getTransactions().single()
        assertTrue(tx.responseComplete)
        assertEquals(256L * 1024 + 1, tx.responseBodyBytes)
        assertEquals(256 * 1024, tx.responseBody!!.length)
        assertTrue(tx.responseBodyTruncated)
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

        interceptor.intercept(FakeChain(request, response)).close()

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

    @Test
    fun `an image response is kept as bytes, not decoded or redacted`() {
        val redactor = CountingRedactor()
        val store = store(redactor)
        val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, -1)
        val request = Request.Builder().url("https://example.com/avatar.png").build()
        val response = responseFor(request, png.toResponseBody("image/png".toMediaType()))

        val result = interceptor(store).intercept(FakeChain(request, response))

        val tx = store.getTransactions().single()
        assertArrayEquals(png, tx.responseBinaryBody)
        assertNull(tx.responseBody)
        assertFalse(tx.responseBodyTruncated)
        assertEquals(10L, tx.responseBodyBytes)
        assertEquals(0, redactor.bodies)
        // Capture peeked: the app still reads the whole body.
        assertArrayEquals(png, result.body!!.bytes())
    }

    @Test
    fun `an image response over the cap keeps a prefix and reports the full size`() {
        val store = store()
        val jpeg = ByteArray(64) { it.toByte() }
        val request = Request.Builder().url("https://example.com/photo.jpg").build()
        val response = responseFor(request, jpeg.toResponseBody("image/jpeg".toMediaType()))

        interceptor(store, maxBodySize = 16).intercept(FakeChain(request, response))

        val tx = store.getTransactions().single()
        assertArrayEquals(jpeg.copyOf(16), tx.responseBinaryBody)
        assertTrue(tx.responseBodyTruncated)
        assertEquals(64L, tx.responseBodyBytes)
    }

    @Test
    fun `an image request body is kept as bytes, up to the cap`() {
        val store = store()
        val upload = ByteArray(32) { (255 - it).toByte() }
        val request =
            Request.Builder()
                .url("https://example.com/upload")
                .put(upload.toRequestBody("image/webp".toMediaType()))
                .build()
        val response = responseFor(request, TrackingResponseBody("text/plain".toMediaType(), "ok", 2))

        interceptor(store, maxBodySize = 16).intercept(FakeChain(request, response))

        val tx = store.getTransactions().single()
        assertArrayEquals(upload.copyOf(16), tx.requestBinaryBody)
        assertNull(tx.requestBody)
        assertTrue(tx.requestBodyTruncated)
        assertEquals(32L, tx.requestBodyBytes)
    }

    @Test
    fun `a multipart body keeps its text parts and a line for each file`() {
        val store = store()
        val png = ByteArray(300) { it.toByte() }
        val multipart =
            MultipartBody.Builder("b0undary")
                .setType(MultipartBody.FORM)
                .addFormDataPart("description", "A cat")
                .addFormDataPart("file", "cat.png", png.toRequestBody("image/png".toMediaType()))
                .build()
        val request = Request.Builder().url("https://example.com/api/v2/media").post(multipart).build()
        val response = responseFor(request, TrackingResponseBody("application/json".toMediaType(), "{}", 2))

        interceptor(store).intercept(FakeChain(request, response))

        val tx = store.getTransactions().single()
        val body = tx.requestBody!!
        assertEquals(
            "--b0undary\r\n" +
                "Content-Disposition: form-data; name=\"description\"\r\n" +
                "Content-Length: 5\r\n\r\n" +
                "A cat\r\n" +
                "--b0undary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"cat.png\"\r\n" +
                "Content-Type: image/png\r\n" +
                "Content-Length: 300\r\n\r\n" +
                "[Lustro did not store this part: image/png, 300 bytes]\r\n" +
                "--b0undary--\r\n",
            body,
        )
        assertFalse(tx.requestBodyTruncated)
        assertNull(tx.requestBinaryBody)
        assertEquals(multipart.contentLength(), tx.requestBodyBytes)
        assertEquals("multipart/form-data; boundary=b0undary", tx.requestContentType)
    }

    @Test
    fun `a multipart body is cut at the cap like any other`() {
        val store = store()
        val multipart =
            MultipartBody.Builder("b").setType(MultipartBody.FORM).addFormDataPart("note", "z".repeat(500)).build()
        val request = Request.Builder().url("https://example.com/notes").post(multipart).build()
        val response = responseFor(request, TrackingResponseBody("text/plain".toMediaType(), "ok", 2))

        interceptor(store, maxBodySize = 100).intercept(FakeChain(request, response))

        val tx = store.getTransactions().single()
        assertTrue(tx.requestBodyTruncated)
        assertEquals(100, tx.requestBody!!.length)
        assertTrue(tx.requestBody!!.endsWith("zzz"))
    }

    @Test
    fun `a multipart file with no type is not read`() {
        val store = store()
        var reads = 0
        val file =
            object : RequestBody() {
                override fun contentType(): okhttp3.MediaType? = null

                override fun contentLength(): Long = 4

                override fun writeTo(sink: BufferedSink) {
                    reads++
                    sink.write(byteArrayOf(0x50, 0x4b, 3, 4))
                }
            }
        val multipart =
            MultipartBody.Builder("b").setType(MultipartBody.FORM)
                .addFormDataPart("title", "notes")
                .addFormDataPart("archive", "notes.zip", file)
                .build()
        val request = Request.Builder().url("https://example.com/upload").post(multipart).build()
        val response = responseFor(request, TrackingResponseBody("text/plain".toMediaType(), "ok", 2))

        interceptor(store).intercept(FakeChain(request, response))

        assertEquals(0, reads)
        val body = store.getTransactions().single().requestBody!!
        assertTrue(body, body.contains("\r\n\r\nnotes\r\n"))
        assertTrue(body, body.contains("[Lustro did not store this part: content, 4 bytes]"))
    }

    @Test
    fun `the multipart cap counts bytes, as for any other body`() {
        val store = store()
        // 30 chars of 3 bytes each: two fields fit in 100 chars but not in 100 bytes.
        val cjk = "\u6f22".repeat(30)
        val multipart =
            MultipartBody.Builder("b").setType(MultipartBody.FORM)
                .addFormDataPart("a", cjk)
                .addFormDataPart("b", cjk)
                .build()
        val request = Request.Builder().url("https://example.com/notes").post(multipart).build()
        val response = responseFor(request, TrackingResponseBody("text/plain".toMediaType(), "ok", 2))

        interceptor(store, maxBodySize = 200).intercept(FakeChain(request, response))

        val tx = store.getTransactions().single()
        assertTrue(tx.requestBodyTruncated)
        assertTrue("stored ${tx.requestBody!!.toByteArray().size} bytes", tx.requestBody!!.toByteArray().size <= 200)
    }

    @Test
    fun `a one-shot multipart part is not read`() {
        val store = store()
        var reads = 0
        val oneShot =
            object : RequestBody() {
                override fun contentType(): okhttp3.MediaType = "text/plain".toMediaType()

                override fun isOneShot(): Boolean = true

                override fun writeTo(sink: BufferedSink) {
                    reads++
                    sink.writeUtf8("streamed")
                }
            }
        val multipart = MultipartBody.Builder("b").setType(MultipartBody.FORM).addFormDataPart("log", "log.txt", oneShot).build()
        val request = Request.Builder().url("https://example.com/logs").post(multipart).build()
        val response = responseFor(request, TrackingResponseBody("text/plain".toMediaType(), "ok", 2))

        interceptor(store).intercept(FakeChain(request, response))

        assertEquals(0, reads)
        assertTrue(store.getTransactions().single().requestBody!!.contains("[Lustro did not store this part: text/plain, size unknown]"))
    }

    @Test
    fun `an HLS playlist is kept as text`() {
        val store = store()
        val request = Request.Builder().url("https://example.com/live/index.m3u8").build()
        val playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:5.0,\nsegment-1.ts\n"
        val response = responseFor(request, playlist.toResponseBody("application/vnd.apple.mpegurl".toMediaType()))

        interceptor(store).intercept(FakeChain(request, response)).body!!.string()

        val tx = store.getTransactions().single()
        assertEquals(playlist, tx.responseBody)
        assertEquals(playlist.length.toLong(), tx.responseBodyBytes)
    }

    @Test
    fun `other binary bodies are still dropped, keeping their size`() {
        val store = store()
        val request =
            Request.Builder()
                .url("https://example.com/blob")
                .post(byteArrayOf(1, 2, 3).toRequestBody("application/protobuf".toMediaType()))
                .build()
        val response = responseFor(request, ByteArray(5).toResponseBody("application/octet-stream".toMediaType()))

        interceptor(store).intercept(FakeChain(request, response))

        val tx = store.getTransactions().single()
        assertNull(tx.requestBinaryBody)
        assertNull(tx.requestBody)
        assertEquals(3L, tx.requestBodyBytes)
        assertNull(tx.responseBinaryBody)
        assertNull(tx.responseBody)
        assertEquals(5L, tx.responseBodyBytes)
    }

    @Test
    fun `a download stays in flight until the app reads its body, then has the read in its duration`() {
        val store = store()
        val request = Request.Builder().url("https://example.com/episode.mp3").build()
        val audio = ByteArray(64 * 1024) { it.toByte() }
        val body = SlowResponseBody(audio, "audio/mpeg".toMediaType(), delayPerReadMs = 20)
        val response = responseFor(request, body)

        val result = interceptor(store).intercept(FakeChain(request, response))

        val inFlight = store.getTransactions().single()
        assertEquals(200, inFlight.statusCode)
        assertFalse(inFlight.responseComplete)
        assertNull(inFlight.completedAt)
        val headersMs = inFlight.durationMs!!

        assertArrayEquals("the app reads the body unchanged", audio, result.body!!.bytes())

        val tx = store.getTransactions().single()
        assertTrue(tx.responseComplete)
        assertNotNull(tx.completedAt)
        assertNull(tx.error)
        assertEquals(audio.size.toLong(), tx.responseBodyBytes)
        assertNull(tx.responseBody)
        assertTrue("duration ${tx.durationMs} covers the reads", tx.durationMs!! >= headersMs + 20 * 4)
    }

    @Test
    fun `a body the app closes early completes when it is closed`() {
        val store = store()
        val request = Request.Builder().url("https://example.com/episode.mp3").build()
        val response = responseFor(request, SlowResponseBody(ByteArray(10_000), "audio/mpeg".toMediaType(), declared = true))

        val result = interceptor(store).intercept(FakeChain(request, response))
        result.body!!.source().readByteArray(100)
        assertFalse(store.getTransactions().single().responseComplete)
        result.close()

        val tx = store.getTransactions().single()
        assertTrue(tx.responseComplete)
        assertNull(tx.error)
        assertEquals(10_000L, tx.responseBodyBytes)
    }

    @Test
    fun `a body that fails while the app reads it fails the transaction and keeps its status`() {
        val store = store()
        val request = Request.Builder().url("https://example.com/episode.mp3").build()
        val body = SlowResponseBody(ByteArray(10_000), "audio/mpeg".toMediaType(), failAfterBytes = 4_096)
        val response = responseFor(request, body)

        val result = interceptor(store).intercept(FakeChain(request, response))

        assertThrows(IOException::class.java) { result.body!!.bytes() }
        val tx = store.getTransactions().single()
        assertEquals(200, tx.statusCode)
        assertTrue(tx.responseComplete)
        assertEquals("Body failed after 4096 bytes: connection reset", tx.error)
    }

    @Test
    fun `a response with no body to read is complete at once`() {
        val store = store()
        val head = Request.Builder().url("https://example.com/episode.mp3").head().build()
        val headers = okhttp3.Headers.headersOf("Content-Length", "65000000")

        interceptor(store).intercept(FakeChain(head, responseFor(head, ByteArray(0).toResponseBody("audio/mpeg".toMediaType()), headers = headers)))

        assertTrue("an app may never close the reply to a HEAD", store.getTransactions().single().responseComplete)
    }

    @Test
    fun `a body that capture kept whole has the time it took to arrive in its duration`() {
        val store = store()
        val request = Request.Builder().url("https://example.com/feed.json").build()
        // 3 reads of 8 KB, 50 ms apart: well under the cap, so capture reads it all.
        val body = SlowResponseBody(ByteArray(24 * 1024) { 'a'.code.toByte() }, "application/json".toMediaType(), delayPerReadMs = 50)

        interceptor(store).intercept(FakeChain(request, responseFor(request, body)))

        val tx = store.getTransactions().single()
        assertTrue(tx.responseComplete)
        assertTrue("duration ${tx.durationMs} covers the body", tx.durationMs!! >= 150)
    }

    @Test
    fun `a body that capture kept whole is complete before the app reads it`() {
        val store = store()
        val request = Request.Builder().url("https://example.com/feed.json").build()

        interceptor(store).intercept(FakeChain(request, responseFor(request, "{}".toResponseBody("application/json".toMediaType()))))

        val tx = store.getTransactions().single()
        assertTrue(tx.responseComplete)
        assertEquals("{}", tx.responseBody)
    }

    @Test
    fun `a redirect OkHttp follows is one transaction with its hops and the URL the response came from`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "/feeds/2"))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/feeds/3"))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/xml").setBody("<rss/>"))
        server.start()
        try {
            val store = store()
            val client = OkHttpClient.Builder().addInterceptor(interceptor(store)).build()

            client.newCall(Request.Builder().url(server.url("/feeds/1")).build()).execute().use { assertEquals("<rss/>", it.body!!.string()) }

            val tx = store.getTransactions().single()
            assertEquals(server.url("/feeds/1").toString(), tx.url)
            assertEquals(server.url("/feeds/3").toString(), tx.finalUrl)
            assertEquals(
                listOf(PriorResponse(server.url("/feeds/1").toString(), 301), PriorResponse(server.url("/feeds/2").toString(), 302)),
                tx.priorResponses,
            )
            assertEquals(200, tx.statusCode)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `an answered auth challenge is a prior response, with no final URL`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Basic realm=\"feeds\""))
        server.enqueue(MockResponse().setBody("ok"))
        server.start()
        try {
            val store = store()
            val client =
                OkHttpClient.Builder()
                    .addInterceptor(interceptor(store))
                    .authenticator { _, response -> response.request.newBuilder().header("Authorization", "Basic dTpw").build() }
                    .build()

            client.newCall(Request.Builder().url(server.url("/private")).build()).execute().close()

            val tx = store.getTransactions().single()
            assertNull(tx.finalUrl)
            assertEquals(listOf(PriorResponse(server.url("/private").toString(), 401)), tx.priorResponses)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a response that was not redirected has no final URL or prior responses`() {
        val store = store()
        val request = Request.Builder().url("https://example.com/feed.json").build()

        interceptor(store).intercept(FakeChain(request, responseFor(request, "{}".toResponseBody("application/json".toMediaType()))))

        val tx = store.getTransactions().single()
        assertNull(tx.finalUrl)
        assertTrue(tx.priorResponses.isEmpty())
    }

    @Test
    fun `the final URL and the hops are redacted`() {
        val store = store(redactor = DefaultRedactor)
        val requested = Request.Builder().url("https://example.com/feed?token=s3cret").build()
        val hop = Request.Builder().url("https://cdn.example.com/feed?token=s3cret").build()
        val prior = responseFor(requested, ByteArray(0).toResponseBody(null)).newBuilder().code(302).body(null).build()
        val response = responseFor(hop, "{}".toResponseBody("application/json".toMediaType())).newBuilder().priorResponse(prior).build()

        interceptor(store).intercept(FakeChain(requested, response))

        val tx = store.getTransactions().single()
        assertFalse(tx.finalUrl!!, tx.finalUrl!!.contains("s3cret"))
        assertTrue(tx.finalUrl!!.startsWith("https://cdn.example.com/feed?token="))
        assertFalse(tx.priorResponses.single().url.contains("s3cret"))
    }

    @Test
    fun `an SVG body is text, so the Redactor reads it`() {
        val store = store()
        val svg = """<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>"""
        val request = Request.Builder().url("https://example.com/logo.svg").build()
        val response = responseFor(request, TrackingResponseBody("image/svg+xml".toMediaType(), svg, 64))

        interceptor(store).intercept(FakeChain(request, response))

        val tx = store.getTransactions().single()
        assertEquals(svg, tx.responseBody)
        assertNull(tx.responseBinaryBody)
    }

    @Test
    fun `a gzip response to a client that sets Accept-Encoding itself is captured as its JSON`() {
        val json = """{"id":7,"items":["a","b"],"note":"héllo"}"""
        val compressed = gzip(json)
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setHeader("Content-Encoding", "gzip")
                .setBody(Buffer().write(compressed)),
        )
        server.start()
        try {
            val store = store()
            val client = OkHttpClient.Builder().addInterceptor(interceptor(store)).build()
            // Setting Accept-Encoding turns off OkHttp's own gzip handling.
            val request = Request.Builder().url(server.url("/orders")).header("Accept-Encoding", "gzip").build()

            val received = client.newCall(request).execute().use { it.body!!.bytes() }

            // The app still gets the compressed bytes it asked for.
            assertArrayEquals(compressed, received)
            val tx = store.getTransactions().single()
            assertEquals(json, tx.responseBody)
            assertFalse(tx.responseBodyTruncated)
            assertEquals(compressed.size.toLong(), tx.responseBodyBytes)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a response OkHttp inflates itself is captured as the app reads it`() {
        val json = """{"id":7}"""
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setHeader("Content-Encoding", "gzip")
                .setBody(Buffer().write(gzip(json))),
        )
        server.start()
        try {
            val store = store()
            val client = OkHttpClient.Builder().addInterceptor(interceptor(store)).build()

            val received = client.newCall(Request.Builder().url(server.url("/orders")).build()).execute().use { it.body!!.string() }

            assertEquals("gzip", server.takeRequest().getHeader("Accept-Encoding"))
            assertEquals(json, received)
            val tx = store.getTransactions().single()
            assertEquals(json, tx.responseBody)
            assertEquals(json.length.toLong(), tx.responseBodyBytes)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a gzip request body is captured as its JSON`() {
        // The OkHttp GzipRequestInterceptor recipe, added before Lustro's as the README says.
        val gzipRequests =
            Interceptor { chain ->
                val original = chain.request()
                val body = original.body!!
                val compressing =
                    object : RequestBody() {
                        override fun contentType(): okhttp3.MediaType? = body.contentType()

                        override fun contentLength(): Long = -1L

                        override fun writeTo(sink: BufferedSink) {
                            GzipSink(sink).buffer().use { body.writeTo(it) }
                        }
                    }
                chain.proceed(original.newBuilder().header("Content-Encoding", "gzip").method(original.method, compressing).build())
            }
        val json = """{"hello":"gzip","note":"héllo"}"""
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("ok"))
        server.start()
        try {
            val store = store()
            val client = OkHttpClient.Builder().addInterceptor(gzipRequests).addInterceptor(interceptor(store)).build()
            val request = Request.Builder().url(server.url("/events")).post(json.toRequestBody("application/json".toMediaType())).build()

            client.newCall(request).execute().use { assertEquals(200, it.code) }

            val sent = server.takeRequest().body
            val sentSize = sent.size
            assertEquals(json, GzipSource(sent).buffer().readUtf8())
            val tx = store.getTransactions().single()
            assertEquals(json, tx.requestBody)
            assertFalse(tx.requestBodyTruncated)
            assertEquals(sentSize, tx.requestBodyBytes)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a br response keeps only its size`() {
        val encoded = ByteArray(48) { (it * 7).toByte() }
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setHeader("Content-Encoding", "br")
                .setBody(Buffer().write(encoded)),
        )
        server.start()
        try {
            val store = store()
            val client = OkHttpClient.Builder().addInterceptor(interceptor(store)).build()
            val request = Request.Builder().url(server.url("/orders")).header("Accept-Encoding", "br").build()

            val received = client.newCall(request).execute().use { it.body!!.bytes() }

            assertArrayEquals(encoded, received)
            val tx = store.getTransactions().single()
            assertNull(tx.responseBody)
            assertNull(tx.responseBinaryBody)
            assertFalse(tx.responseBodyTruncated)
            assertEquals(48L, tx.responseBodyBytes)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a response that inflates past the cap is cut at the cap and flagged`() {
        // 8 MB of text compresses to about 8 KB; only the cap of it may be inflated.
        val bomb = gzip("a".repeat(8 * 1024 * 1024))
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/plain")
                .setHeader("Content-Encoding", "gzip")
                .setBody(Buffer().write(bomb)),
        )
        server.start()
        try {
            val store = store()
            val client = OkHttpClient.Builder().addInterceptor(interceptor(store, maxBodySize = 1024)).build()
            val request = Request.Builder().url(server.url("/export")).header("Accept-Encoding", "gzip").build()

            client.newCall(request).execute().use { assertEquals(bomb.size.toLong(), it.body!!.bytes().size.toLong()) }

            val tx = store.getTransactions().single()
            assertEquals("a".repeat(1024), tx.responseBody)
            assertTrue(tx.responseBodyTruncated)
            assertEquals(bomb.size.toLong(), tx.responseBodyBytes)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a secret that the body cap cuts off is redacted before it is stored`() {
        val store = store(DefaultRedactor)
        val prefix = "{\"id\":1,\"note\":\"${"x".repeat(20)}\",\"password\":\""
        val content = prefix + "hunter2-hunter2-hunter2\"}"
        val interceptor = interceptor(store, maxBodySize = prefix.length + 8L)
        val request = Request.Builder().url("https://example.com/big").build()
        val body = TrackingResponseBody("application/json".toMediaType(), content, chunkSize = 8_192)

        interceptor.intercept(FakeChain(request, responseFor(request, body)))

        val tx = store.getTransactions().single()
        assertTrue(tx.responseBodyTruncated)
        assertEquals(prefix + "[REDACTED]", tx.responseBody)
    }

    @Test
    fun `an event stream secret split across reads is redacted before it is stored`() {
        val store = store(DefaultRedactor)
        val interceptor = interceptor(store)
        val request = Request.Builder().url("https://example.com/events").build()
        val firstChunk = "data: {\"token\":\"abc"
        val body =
            TrackingResponseBody(
                contentType = "text/event-stream".toMediaType(),
                content = firstChunk + "def\"}\n\n",
                chunkSize = firstChunk.length,
            )
        val result = interceptor.intercept(FakeChain(request, responseFor(request, body)))

        val sink = Buffer()
        val source = result.body!!.source()
        source.read(sink, 8_192)

        // In flight, with only part of the token read: nothing of it is stored.
        var transaction = store.getTransactions().single()
        assertFalse(transaction.responseComplete)
        assertEquals("data: {\"token\":\"[REDACTED]", transaction.responseBody)

        while (source.read(sink, 8_192) != -1L) {
            // Drain the stream.
        }

        transaction = store.getTransactions().single()
        assertTrue(transaction.responseComplete)
        assertEquals("data: {\"token\":\"[REDACTED]\"}\n\n", transaction.responseBody)
    }

    private fun responseFor(
        request: Request,
        body: ResponseBody,
        protocol: Protocol = Protocol.HTTP_1_1,
        headers: okhttp3.Headers = okhttp3.Headers.headersOf(),
    ): Response =
        Response.Builder()
            .request(request)
            .protocol(protocol)
            .code(200)
            .message("OK")
            .headers(headers)
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

    /** Serves [content] in 8 KB reads, each after [delayPerReadMs], and can fail after [failAfterBytes]. */
    private class SlowResponseBody(
        private val content: ByteArray,
        private val contentType: okhttp3.MediaType,
        private val delayPerReadMs: Long = 0,
        private val failAfterBytes: Int = -1,
        private val declared: Boolean = false,
    ) : ResponseBody() {
        override fun contentType(): okhttp3.MediaType = contentType

        override fun contentLength(): Long = if (declared) content.size.toLong() else -1L

        override fun source(): BufferedSource =
            object : Source {
                private var offset = 0

                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (offset == failAfterBytes) throw IOException("connection reset")
                    if (offset == content.size) return -1L
                    if (delayPerReadMs > 0) Thread.sleep(delayPerReadMs)
                    val limit = if (failAfterBytes >= 0) failAfterBytes else content.size
                    val count = minOf(byteCount, 8_192L, (limit - offset).toLong()).toInt()
                    sink.write(content, offset, count)
                    offset += count
                    return count.toLong()
                }

                override fun timeout(): Timeout = Timeout.NONE

                override fun close() = Unit
            }.buffer()
    }

    private class TrackingRequestBody(private val content: String) : RequestBody() {
        var written: Boolean = false
            private set

        override fun contentType(): okhttp3.MediaType = "application/json".toMediaType()

        override fun writeTo(sink: BufferedSink) {
            written = true
            sink.writeUtf8(content)
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

    private data class Failure(val id: TransactionId, val error: String)

    private class RecordingSink(private val mockRule: MockRule? = null) : NetworkCaptureSink {
        val completions = mutableListOf<CapturedResponse>()
        val failures = mutableListOf<Failure>()
        val requestBodies = mutableListOf<CapturedBody?>()
        private var counter = 0

        val begun: Int
            get() = counter

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

        override fun completeRequest(id: TransactionId, response: CapturedResponse) {
            completions.add(response)
        }

        override fun failRequest(id: TransactionId, durationMs: Long, error: String) {
            failures.add(Failure(id, error))
        }
    }

}
