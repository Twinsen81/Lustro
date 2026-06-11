package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.network.CapturedBody
import io.github.twinsen81.lustro.network.NetworkCaptureSink
import io.github.twinsen81.lustro.network.TransactionId
import java.io.IOException
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
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
 * Gated by [captureEnabled]; when capture is disabled the interceptor still
 * mocks and throttles (those are independent of capture). "Pause" lives in the
 * store and is capture-only — the [NetworkTrafficStore] still matches mocks and
 * applies throttle while paused. Uses peekBody() for regular response capture
 * and wraps event streams so they can be captured without buffering upfront.
 *
 * Body capture: text-like request/response bodies are captured up to
 * [maxBodySize]; `truncated = fullSize > cap` and
 * `byteSize = declaredContentLength ?: fullSize`. Binary/one-shot/duplex bodies
 * report `CapturedBody(text=null, truncated=false, byteSize=declared)`.
 */
internal class LustroNetworkInterceptor(
    private val sink: NetworkCaptureSink,
    private val captureEnabled: () -> Boolean,
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
        val capturing = captureEnabled()

        val startTime = System.currentTimeMillis()
        var id: TransactionId? = null
        if (capturing) {
            val requestCapture = captureRequestBody(request)
            id =
                sink.beginRequest(
                    url = url,
                    method = request.method,
                    headers = request.headers.toApiHeaders(),
                    requestBody = requestCapture,
                    contentType = request.body?.contentType()?.toApiMediaType(),
                )
        }

        if (mockRule != null) {
            incrementMockHit(mockRule.id)
            val mediaType =
                (mockRule.responseHeaders.get("Content-Type") ?: "application/json")
                    .toMediaType()
            val mockBodyBytes = mockRule.responseBody.toByteArray(Charsets.UTF_8)
            if (id != null) {
                val durationMs = System.currentTimeMillis() - startTime
                sink.completeRequest(
                    id = id,
                    statusCode = mockRule.statusCode,
                    responseHeaders = mockRule.responseHeaders,
                    responseBody =
                        CapturedBody(
                            text = mockRule.responseBody,
                            truncated = false,
                            byteSize = mockBodyBytes.size.toLong(),
                        ),
                    durationMs = durationMs,
                    isMocked = true,
                    complete = true,
                )
            }
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(mockRule.statusCode)
                .message("Mocked")
                .body(mockRule.responseBody.toResponseBody(mediaType))
                .apply {
                    mockRule.responseHeaders.forEach { key, value ->
                        addHeader(key, value)
                    }
                }
                .build()
        }

        // Proceed with the real request.
        return try {
            val response = chain.proceed(request)
            val durationMs = System.currentTimeMillis() - startTime

            if (id == null) {
                return response
            }

            wrapEventStreamResponse(id, response, startTime)?.let { return it }

            val responseCapture = captureResponseBody(response)
            sink.completeRequest(
                id = id,
                statusCode = response.code,
                responseHeaders = response.headers.toApiHeaders(),
                responseBody = responseCapture,
                durationMs = durationMs,
                isMocked = false,
                complete = true,
            )
            response
        } catch (e: IOException) {
            if (id != null) {
                val durationMs = System.currentTimeMillis() - startTime
                sink.failRequest(id, durationMs, e.message ?: e.javaClass.simpleName)
            }
            throw e
        }
    }

    private fun captureRequestBody(request: okhttp3.Request): CapturedBody? {
        val body = request.body ?: return null
        val declaredSize = body.contentLength().takeIf { it >= 0 }
        if (body.isOneShot() || body.isDuplex()) {
            return CapturedBody(text = null, truncated = false, byteSize = declaredSize)
        }
        if (!body.contentType().isTextLike()) {
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
            val text = if (truncated) buffer.readUtf8(maxBodySize) else buffer.readUtf8()
            CapturedBody(text = text, truncated = truncated, byteSize = declaredSize ?: fullSize)
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
        if (!body.contentType().isTextLike()) {
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
            val charset = body.contentType().resolvedCharset()
            val text =
                if (truncated) {
                    String(raw, 0, maxBodySize.toInt(), charset)
                } else {
                    String(raw, charset)
                }
            // When the full size isn't known (no Content-Length) and the body was
            // truncated, we can't report a true byte count — leave it null rather
            // than report the truncated prefix length.
            val measured =
                declaredSize
                    ?: if (truncated) null else raw.size.toLong()
            CapturedBody(text = text, truncated = truncated, byteSize = measured)
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
            id = id,
            statusCode = response.code,
            responseHeaders = responseHeaders,
            responseBody = CapturedBody(text = null, truncated = false, byteSize = declaredSize),
            durationMs = System.currentTimeMillis() - startTime,
            isMocked = false,
            complete = false,
        )

        return response.newBuilder()
            .body(
                EventStreamCapturingResponseBody(
                    delegate = body,
                    declaredSize = declaredSize,
                    maxBodySize = maxBodySize,
                    onCapture = { capture ->
                        sink.completeRequest(
                            id = id,
                            statusCode = response.code,
                            responseHeaders = responseHeaders,
                            responseBody =
                                CapturedBody(
                                    text = capture.text,
                                    truncated = capture.truncated,
                                    byteSize = capture.sizeBytes,
                                ),
                            durationMs = System.currentTimeMillis() - startTime,
                            isMocked = false,
                            // false for progressive updates, true at EOF/close.
                            complete = capture.responseComplete,
                        )
                    },
                ),
            )
            .build()
    }
}
