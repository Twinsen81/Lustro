package io.github.twinsen81.lustro.internal.network

import android.util.Log
import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.MediaType
import io.github.twinsen81.lustro.network.CapturedBody
import io.github.twinsen81.lustro.network.CapturedPriorResponse
import io.github.twinsen81.lustro.network.CapturedResponse
import io.github.twinsen81.lustro.network.MockRule
import io.github.twinsen81.lustro.network.NetworkCaptureSink
import io.github.twinsen81.lustro.network.TransactionId
import java.io.IOException
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Protocol
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Sink
import okio.Timeout
import okio.buffer

/**
 * OkHttp application interceptor that captures request/response data into a
 * [NetworkCaptureSink]. Talks to the SPI sink instead of a concrete store,
 * keeping the interceptor decoupled from any particular storage implementation.
 *
 * Gated by [captureEnabled], then by [captureFilter] for each request; when
 * either says no, the interceptor still mocks and throttles (those are
 * independent of capture). "Pause" lives in the store and is capture-only: the
 * [NetworkTrafficStore] still matches mocks and applies throttle while paused.
 * Uses peekBody() for regular response capture and wraps event streams and
 * JSON streams so they can be captured without buffering upfront.
 *
 * Body capture: text-like request/response bodies are captured as text, and
 * image ones as bytes, up to [maxBodySize]. A request body with no type is
 * captured as text. A request body is copied as OkHttp writes it (see
 * [CapturingRequestBody]), so the request is listed with the body's size first
 * and its bytes once they are sent. A form body is written once more for
 * capture, and a multipart one is captured as text too, with its text parts
 * and a line for each other part (see [MultipartCapture]);
 * `truncated = fullSize > cap` and `byteSize = declaredContentLength ?: fullSize`.
 * Other binary bodies and duplex ones report `CapturedBody(text=null,
 * truncated=false, byteSize=declared)`.
 *
 * A body that capture doesn't read whole, such as a download, a body past the
 * cap, or one of a type that isn't kept, reaches the app through a
 * [TransferTrackingResponseBody]. Its response is reported in flight with its
 * headers, then complete when the app reads to the end or closes the body, with
 * the duration up to then and the size counted when there is no
 * `Content-Length`. A read that throws fails the transaction and keeps its
 * status.
 *
 * A gzip or deflate body is inflated first (see [decodeBody]): `truncated` then
 * refers to the inflated body, and `byteSize` stays the size on the wire. A body
 * in a coding capture can't undo, such as `br`, reports `text=null` with its
 * size. Event streams are captured as they arrive, without decoding: servers
 * don't compress them in practice, so capture doesn't inflate a stream while
 * the app reads it.
 */
internal class LustroNetworkInterceptor(
    private val sink: NetworkCaptureSink,
    private val captureEnabled: () -> Boolean,
    private val captureFilter: SafeCaptureFilter,
    private val throttleDelayMs: () -> Int,
    private val incrementMockHit: (String) -> Unit,
    private val maxBodySize: Long,
    private val recordThrottle: (TransactionId, Long) -> Unit = { _, _ -> },
    private val recordRequestBody: (TransactionId, CapturedBody, MediaType?) -> Unit = { _, _, _ -> },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        val contentType = requestContentType(request)
        val bodyOnWrite = capturesBodyOnWrite(request.body, contentType)

        // Capture starts before the throttle: the request is listed while it
        // waits, and its start time is when the app made the call.
        val id = if (captureEnabled()) beginCapture(request, url, contentType, bodyOnWrite) else null

        // Throttle applies regardless of capture/pause (mocks + throttle still run).
        val throttleMs = throttleDelayMs()
        if (throttleMs > 0) {
            if (id != null) recordThrottle(id, throttleMs.toLong())
            try {
                Thread.sleep(throttleMs.toLong())
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                if (id != null) sink.failRequest(id, 0L, "Throttle interrupted")
                throw IOException("Throttle interrupted", e)
            }
        }

        // Mock short-circuit — independent of capture being enabled.
        val mockRule = sink.findMockRule(url, request.method)

        // durationMs leaves the throttle out; the transaction's throttledMs has it.
        val startTime = System.currentTimeMillis()

        if (mockRule != null) {
            // Build the response before recording anything: rules are validated
            // where they enter, but a rule that still can't be turned into one
            // must fail this call the way a network error does — never as a
            // RuntimeException thrown out of the app's own execute()/enqueue().
            val mockResponse =
                try {
                    buildMockResponse(request, mockRule)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Mock rule '${mockRule.id}' cannot be served; failing the request", e)
                    val durationMs = System.currentTimeMillis() - startTime
                    val reason = "Invalid mock rule '${mockRule.id}': ${e.message}"
                    if (id != null) {
                        sink.failRequest(id, durationMs, reason)
                    }
                    throw IOException(reason, e)
                }
            incrementMockHit(mockRule.id)
            if (id != null) {
                // No request is sent, so nothing else writes the body.
                if (bodyOnWrite) {
                    captureRequestBody(request, contentType)?.let { recordRequestBody(id, it, contentType.toApiMediaType()) }
                }
                val durationMs = System.currentTimeMillis() - startTime
                val mockBodyBytes = mockRule.responseBody.toByteArray(Charsets.UTF_8)
                // No protocol: the mock response's HTTP/1.1 is made up, not negotiated.
                sink.completeRequest(
                    id,
                    CapturedResponse.Builder(mockRule.statusCode, durationMs)
                        .headers(mockRule.responseHeaders)
                        .body(
                            CapturedBody(
                                text = mockRule.responseBody,
                                truncated = false,
                                byteSize = mockBodyBytes.size.toLong(),
                            ),
                        )
                        .mocked(true)
                        .build(),
                )
            }
            return mockResponse
        }

        // Proceed with the real request.
        return try {
            val sent = if (id != null && bodyOnWrite) request.withBodyCapturedOnWrite(id, contentType) else request
            val response = chain.proceed(sent)

            if (id == null) {
                return response
            }

            wrapTextStreamResponse(id, request.url, response, startTime)?.let { return it }

            val body = captureResponseBody(response)
            // Read after capture: peeking the body waits for it to arrive, up to the cap.
            val durationMs = System.currentTimeMillis() - startTime
            if (!response.bodyOutlivesCapture(body)) {
                sink.completeRequest(id, response.toCapturedResponse(request.url, durationMs, body))
                return response
            }
            // The app reads the rest of the body after this returns, which can take far
            // longer than the headers did, so the response stays in flight until it ends.
            sink.completeRequest(id, response.toCapturedResponse(request.url, durationMs, body, complete = false))
            trackBodyTransfer(id, request.url, response, body, startTime)
        } catch (e: IOException) {
            if (id != null) {
                val durationMs = System.currentTimeMillis() - startTime
                sink.failRequest(id, durationMs, e.message ?: e.javaClass.simpleName)
            }
            throw e
        }
    }

    // Null when the filter skips the request: none of it is read for capture or reported.
    @Suppress("RestrictedApi") // id.value is @RestrictTo(LIBRARY_GROUP); same-group call.
    private fun beginCapture(
        request: okhttp3.Request,
        url: String,
        contentType: okhttp3.MediaType?,
        bodyOnWrite: Boolean,
    ): TransactionId? {
        val headers = request.headers.toApiHeaders()
        // The handshake of a socket from the capturing factory. The factory asked
        // the filter when the app created the socket, so it isn't asked again.
        val webSocket = request.tag(WebSocketCaptureTag::class.java)
        val capture =
            if (webSocket != null) webSocket.recorder != null else captureFilter.shouldCapture(url, request.method, headers)
        if (!capture) return null
        val requestBody =
            if (bodyOnWrite) {
                // Its bytes are recorded when OkHttp has written them.
                CapturedBody(text = null, truncated = false, byteSize = request.body?.contentLength()?.takeIf { it >= 0 })
            } else {
                captureRequestBody(request, contentType)
            }
        return sink.beginRequest(
            url = url,
            method = request.method,
            headers = headers,
            requestBody = requestBody,
            contentType = contentType.toApiMediaType(),
        ).also { id -> webSocket?.recorder?.linkTransaction(id.value) }
    }

    /**
     * Whether a request body is captured as OkHttp writes it, rather than by
     * writing it once more for capture. A form or multipart body is written for
     * capture, part by part for a multipart one: both are built in memory, and
     * an interceptor or a network interceptor after this one may cast the body
     * to its class. A duplex body is still being written while the response
     * arrives, so it isn't captured at all.
     */
    private fun capturesBodyOnWrite(body: RequestBody?, contentType: okhttp3.MediaType?): Boolean =
        body != null && body !is FormBody && body !is MultipartBody && !body.isDuplex() && !contentType.isUnkeptType()

    private fun okhttp3.Request.withBodyCapturedOnWrite(id: TransactionId, contentType: okhttp3.MediaType?): okhttp3.Request {
        val original = body ?: return this
        val captured =
            CapturingRequestBody(original, maxBodySize + 1) { raw, fullSize ->
                // Runs on the thread that sends the request: capture must never fail it.
                reportSafely {
                    val body = requestCapturedBody(raw, fullSize, original.contentLength().takeIf { it >= 0 }, contentType, headers)
                    recordRequestBody(id, body, contentType.toApiMediaType())
                }
            }
        return newBuilder().method(method, captured).build()
    }

    private fun buildMockResponse(request: okhttp3.Request, rule: MockRule): Response {
        val mediaType =
            (rule.responseHeaders.get("Content-Type") ?: "application/json").toMediaType()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(rule.statusCode)
            .message("Mocked")
            .body(rule.responseBody.toResponseBody(mediaType))
            .apply {
                rule.responseHeaders.forEach { key, value ->
                    addHeader(key, value)
                }
            }
            .build()
    }

    private fun captureRequestBody(request: okhttp3.Request, contentType: okhttp3.MediaType?): CapturedBody? {
        val body = request.body ?: return null
        if (body is MultipartBody) {
            return try {
                MultipartCapture.capture(body, maxBodySize)
            } catch (_: Exception) {
                CapturedBody(text = null, truncated = false, byteSize = body.contentLength().takeIf { it >= 0 })
            }
        }
        val declaredSize = body.contentLength().takeIf { it >= 0 }
        if (body.isOneShot() || body.isDuplex() || contentType.isUnkeptType()) {
            return CapturedBody(text = null, truncated = false, byteSize = declaredSize)
        }
        val buffer = Buffer()
        return try {
            // Retain at most maxBodySize+1 bytes (the +1 lets us detect truncation)
            // while still counting the full size. Writing the whole body into an
            // unbounded Buffer first would let a large outgoing request balloon memory
            // before we ever truncate; the response path is already bounded via peekBody.
            val capturing = CappingSink(buffer, maxBodySize + 1)
            capturing.buffer().use { body.writeTo(it) }
            requestCapturedBody(buffer.readByteArray(), capturing.bytesSeen, declaredSize, contentType, request.headers)
        } catch (_: Exception) {
            CapturedBody(text = null, truncated = false, byteSize = declaredSize)
        } finally {
            buffer.close()
        }
    }

    // A body with no type is captured as text, as platform capture does: apps
    // send text, such as a message or JSON, without one.
    private fun requestCapturedBody(
        raw: ByteArray,
        fullSize: Long,
        declaredSize: Long?,
        contentType: okhttp3.MediaType?,
        headers: okhttp3.Headers,
    ): CapturedBody {
        val byteSize = declaredSize ?: fullSize
        val decoded =
            decodeBody(raw, fullSize > maxBodySize, headers.values(CONTENT_ENCODING), maxBodySize)
                ?: return CapturedBody(text = null, truncated = false, byteSize = byteSize)
        return if (contentType.isRetainedBinary()) {
            CapturedBody(text = null, truncated = decoded.truncated, byteSize = byteSize, bytes = decoded.bytes())
        } else {
            CapturedBody(text = decoded.text(Charsets.UTF_8), truncated = decoded.truncated, byteSize = byteSize)
        }
    }

    /**
     * An [okio.Sink] that copies at most [cap] bytes into [target] and discards the
     * rest, while counting every byte written in [bytesSeen]. Lets a request body be
     * buffered for capture with bounded memory without losing its true total size.
     */
    private class CappingSink(
        private val target: Buffer,
        private val cap: Long,
    ) : Sink {
        var bytesSeen: Long = 0L
            private set

        override fun write(source: Buffer, byteCount: Long) {
            val room = (cap - target.size).coerceAtLeast(0L)
            val keep = minOf(room, byteCount)
            if (keep > 0) target.write(source, keep)
            if (byteCount > keep) source.skip(byteCount - keep)
            bytesSeen += byteCount
        }

        override fun flush() = Unit

        override fun close() = Unit

        override fun timeout(): Timeout = Timeout.NONE
    }

    private fun captureResponseBody(response: Response): CapturedBody? {
        val body = response.body ?: return null
        val declaredSize = body.contentLength().takeIf { it >= 0 }
        val contentType = body.contentType()
        val binary = contentType.isRetainedBinary()
        if (!binary && !contentType.isTextLike()) {
            return CapturedBody(text = null, truncated = false, byteSize = declaredSize)
        }
        return try {
            // Compare bytes to bytes — peekBody truncates at byte level, but
            // String.length is the UTF-16 code-unit count. For multi-byte UTF-8
            // (e.g. CJK at ~3 bytes/char), a length-based check would miss the
            // truncation and the "Truncated" badge would never show. Reading
            // through peekBody leaves the real body untouched for the caller.
            val raw = response.peekBody(maxBodySize + 1).bytes()
            val rawTruncated = raw.size > maxBodySize
            // When the full size isn't known (no Content-Length) and the body was
            // truncated, we can't report a true byte count — leave it null rather
            // than report the truncated prefix length.
            val measured =
                declaredSize
                    ?: if (rawTruncated) null else raw.size.toLong()
            // OkHttp inflates a gzip response itself only when it asked for gzip. When
            // the app sets Accept-Encoding, the body arrives here as the server sent it.
            val decoded =
                decodeBody(raw, rawTruncated, response.headers.values(CONTENT_ENCODING), maxBodySize)
                    ?: return CapturedBody(text = null, truncated = false, byteSize = measured)
            if (binary) {
                CapturedBody(text = null, truncated = decoded.truncated, byteSize = measured, bytes = decoded.bytes())
            } else {
                CapturedBody(text = decoded.text(contentType.resolvedCharset()), truncated = decoded.truncated, byteSize = measured)
            }
        } catch (_: Exception) {
            CapturedBody(text = null, truncated = false, byteSize = declaredSize)
        }
    }

    /**
     * Whether the app still has body bytes to read once capture returns: not for
     * a response that has no body by its status or method, nor for one whose body
     * capture kept whole, since reading it for capture already took it all in.
     */
    private fun Response.bodyOutlivesCapture(captured: CapturedBody?): Boolean {
        val responseBody = body ?: return false
        if (request.method == "HEAD" || code in INFORMATIONAL || code == NO_CONTENT || code == NOT_MODIFIED) return false
        if (responseBody.contentLength() == 0L) return false
        val keptWhole = captured != null && (captured.text != null || captured.bytes != null) && !captured.truncated
        return !keptWhole
    }

    private fun trackBodyTransfer(
        id: TransactionId,
        requestedUrl: HttpUrl,
        response: Response,
        captured: CapturedBody?,
        startTime: Long,
    ): Response {
        val responseBody = response.body ?: return response
        val declaredSize = responseBody.contentLength().takeIf { it >= 0 }
        val tracked =
            TransferTrackingResponseBody(
                delegate = responseBody,
                onEnd = { bytesRead, reachedEnd ->
                    reportSafely {
                        // Read to the end, the count is the size even without a Content-Length.
                        val byteSize = declaredSize ?: bytesRead.takeIf { reachedEnd } ?: captured?.byteSize
                        val body = CapturedBody(captured?.text, captured?.truncated ?: false, byteSize, captured?.bytes)
                        sink.completeRequest(id, response.toCapturedResponse(requestedUrl, System.currentTimeMillis() - startTime, body))
                    }
                },
                onFailure = { bytesRead, error ->
                    reportSafely {
                        val reason = error.message ?: error.javaClass.simpleName
                        sink.failRequest(id, System.currentTimeMillis() - startTime, "Body failed after $bytesRead bytes: $reason")
                    }
                },
            )
        return response.newBuilder().body(tracked).build()
    }

    // Runs on the app's thread as it reads the body: capture must never fail that read.
    private inline fun reportSafely(report: () -> Unit) {
        try {
            report()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not record the end of a response body", e)
        }
    }

    private fun wrapTextStreamResponse(
        id: TransactionId,
        requestedUrl: HttpUrl,
        response: Response,
        startTime: Long,
    ): Response? {
        val body = response.body ?: return null
        if (!body.contentType().isTextStream()) return null

        val responseHeaders = response.headers.toApiHeaders()
        val declaredSize = body.contentLength().takeIf { it >= 0 }

        // Record an initial "in flight" (complete=false) state so the transaction
        // shows up before the stream produces any bytes.
        sink.completeRequest(
            id,
            response.toCapturedResponse(
                requestedUrl = requestedUrl,
                durationMs = System.currentTimeMillis() - startTime,
                body = CapturedBody(text = null, truncated = false, byteSize = declaredSize),
                complete = false,
                headers = responseHeaders,
            ),
        )

        return response.newBuilder()
            .body(
                EventStreamCapturingResponseBody(
                    delegate = body,
                    declaredSize = declaredSize,
                    maxBodySize = maxBodySize,
                    onCapture = { capture ->
                        sink.completeRequest(
                            id,
                            response.toCapturedResponse(
                                requestedUrl = requestedUrl,
                                durationMs = System.currentTimeMillis() - startTime,
                                body =
                                    CapturedBody(
                                        text = capture.text,
                                        truncated = capture.truncated,
                                        byteSize = capture.sizeBytes,
                                    ),
                                // false for progressive updates, true at EOF/close.
                                complete = capture.responseComplete,
                                headers = responseHeaders,
                            ),
                        )
                    },
                ),
            )
            .build()
    }

    // An application interceptor gets the response to the last request OkHttp
    // sent, so its request is where the redirects ended: the final URL when it
    // isn't requestedUrl, the URL of the request this interceptor passed on.
    private fun Response.toCapturedResponse(
        requestedUrl: HttpUrl,
        durationMs: Long,
        body: CapturedBody?,
        complete: Boolean = true,
        headers: Headers = this.headers.toApiHeaders(),
    ): CapturedResponse =
        CapturedResponse.Builder(code, durationMs)
            .headers(headers)
            .body(body)
            .complete(complete)
            .protocol(protocol.toString())
            .finalUrl(request.url.takeIf { it != requestedUrl }?.toString())
            .priorResponses(priorResponses())
            .build()


    private fun Response.priorResponses(): List<CapturedPriorResponse> =
        generateSequence(priorResponse) { it.priorResponse }
            .map { CapturedPriorResponse(it.request.url.toString(), it.code) }
            .toList()
            .asReversed()

    // What goes on the wire: OkHttp sends the body's media type, or the header
    // the app set when the body has none. Body capture goes by the same type.
    private fun requestContentType(request: okhttp3.Request): okhttp3.MediaType? =
        request.body?.contentType() ?: request.header("Content-Type")?.toMediaTypeOrNull()

    private companion object {
        private const val TAG = "Lustro"
        private val INFORMATIONAL = 100..199
        private const val NO_CONTENT = 204
        private const val NOT_MODIFIED = 304
    }
}
