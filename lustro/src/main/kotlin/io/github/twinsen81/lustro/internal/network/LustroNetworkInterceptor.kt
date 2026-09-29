package io.github.twinsen81.lustro.internal.network

import android.util.Log
import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.network.CapturedBody
import io.github.twinsen81.lustro.network.CapturedResponse
import io.github.twinsen81.lustro.network.MockRule
import io.github.twinsen81.lustro.network.NetworkCaptureSink
import io.github.twinsen81.lustro.network.TransactionId
import java.io.IOException
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
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
 * Uses peekBody() for regular response capture and wraps event streams so they
 * can be captured without buffering upfront.
 *
 * Body capture: text-like request/response bodies are captured as text, and
 * image ones as bytes, up to [maxBodySize]; `truncated = fullSize > cap` and
 * `byteSize = declaredContentLength ?: fullSize`. Other binary bodies and
 * one-shot/duplex ones report `CapturedBody(text=null, truncated=false,
 * byteSize=declared)`.
 */
internal class LustroNetworkInterceptor(
    private val sink: NetworkCaptureSink,
    private val captureEnabled: () -> Boolean,
    private val captureFilter: SafeCaptureFilter,
    private val throttleDelayMs: () -> Int,
    private val incrementMockHit: (String) -> Unit,
    private val maxBodySize: Long,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Throttle applies regardless of capture/pause (mocks + throttle still run).
        val throttleMs = throttleDelayMs()
        if (throttleMs > 0) {
            try {
                Thread.sleep(throttleMs.toLong())
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Throttle interrupted", e)
            }
        }

        val url = request.url.toString()

        // Mock short-circuit — independent of capture being enabled.
        val mockRule = sink.findMockRule(url, request.method)

        val startTime = System.currentTimeMillis()
        val id = if (captureEnabled()) beginCapture(request, url) else null

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
            val response = chain.proceed(request)
            val durationMs = System.currentTimeMillis() - startTime

            if (id == null) {
                return response
            }

            wrapEventStreamResponse(id, response, startTime)?.let { return it }

            sink.completeRequest(id, response.toCapturedResponse(durationMs, captureResponseBody(response)))
            response
        } catch (e: IOException) {
            if (id != null) {
                val durationMs = System.currentTimeMillis() - startTime
                sink.failRequest(id, durationMs, e.message ?: e.javaClass.simpleName)
            }
            throw e
        }
    }

    // Null when the filter skips the request: none of it is read for capture or reported.
    private fun beginCapture(request: okhttp3.Request, url: String): TransactionId? {
        val headers = request.headers.toApiHeaders()
        if (!captureFilter.shouldCapture(url, request.method, headers)) return null
        val contentType = requestContentType(request)
        return sink.beginRequest(
            url = url,
            method = request.method,
            headers = headers,
            requestBody = captureRequestBody(request, contentType),
            contentType = contentType.toApiMediaType(),
        )
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
        val declaredSize = body.contentLength().takeIf { it >= 0 }
        if (body.isOneShot() || body.isDuplex()) {
            return CapturedBody(text = null, truncated = false, byteSize = declaredSize)
        }
        val binary = contentType.isRetainedBinary()
        if (!binary && !contentType.isTextLike()) {
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
            val fullSize = capturing.bytesSeen
            val truncated = fullSize > maxBodySize
            val kept = if (truncated) maxBodySize else buffer.size
            val byteSize = declaredSize ?: fullSize
            if (binary) {
                CapturedBody(text = null, truncated = truncated, byteSize = byteSize, bytes = buffer.readByteArray(kept))
            } else {
                CapturedBody(text = buffer.readUtf8(kept), truncated = truncated, byteSize = byteSize)
            }
        } catch (_: Exception) {
            CapturedBody(text = null, truncated = false, byteSize = declaredSize)
        } finally {
            buffer.close()
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
            val peeked = response.peekBody(maxBodySize + 1)
            val raw = peeked.bytes()
            val truncated = raw.size > maxBodySize
            val kept = if (truncated) maxBodySize.toInt() else raw.size
            // When the full size isn't known (no Content-Length) and the body was
            // truncated, we can't report a true byte count — leave it null rather
            // than report the truncated prefix length.
            val measured =
                declaredSize
                    ?: if (truncated) null else raw.size.toLong()
            if (binary) {
                val bytes = if (truncated) raw.copyOf(kept) else raw
                CapturedBody(text = null, truncated = truncated, byteSize = measured, bytes = bytes)
            } else {
                val text = String(raw, 0, kept, contentType.resolvedCharset())
                CapturedBody(text = text, truncated = truncated, byteSize = measured)
            }
        } catch (_: Exception) {
            CapturedBody(text = null, truncated = false, byteSize = declaredSize)
        }
    }

    private fun wrapEventStreamResponse(
        id: TransactionId,
        response: Response,
        startTime: Long,
    ): Response? {
        val body = response.body ?: return null
        if (!body.contentType().isEventStream()) return null

        val responseHeaders = response.headers.toApiHeaders()
        val declaredSize = body.contentLength().takeIf { it >= 0 }

        // Record an initial "in flight" (complete=false) state so the transaction
        // shows up before the stream produces any bytes.
        sink.completeRequest(
            id,
            response.toCapturedResponse(
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

    private fun Response.toCapturedResponse(
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
            .build()

    // What goes on the wire: OkHttp sends the body's media type, or the header
    // the app set when the body has none. Body capture goes by the same type.
    private fun requestContentType(request: okhttp3.Request): okhttp3.MediaType? =
        request.body?.contentType() ?: request.header("Content-Type")?.toMediaTypeOrNull()

    private companion object {
        private const val TAG = "Lustro"
    }
}
