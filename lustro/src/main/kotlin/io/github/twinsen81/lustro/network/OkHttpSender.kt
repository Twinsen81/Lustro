package io.github.twinsen81.lustro.network

import io.github.twinsen81.lustro.internal.network.toApiHeaders
import io.github.twinsen81.lustro.internal.network.toApiMediaType
import io.github.twinsen81.lustro.internal.network.toOkHttpHeaders
import io.github.twinsen81.lustro.internal.network.toOkHttpMediaType
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody

/**
 * The default [NetworkSender], backed by an OkHttp [OkHttpClient].
 *
 * The runtime invokes [send] off the main thread and blocks for the result; this
 * implementation issues a synchronous OkHttp call. Replayed requests appear as
 * their own rows in the traffic list only when [client] carries the Lustro
 * interceptor — pass such a client if you want that. Otherwise the Send panel
 * still reports the inline outcome (status, success), but the replay is not
 * captured as a transaction.
 *
 * At most 256 KB of the response body is read into [NetworkSendResult.body]. The
 * response is then closed without reading the rest, so a large or endless
 * response cannot exhaust the heap.
 */
public class OkHttpSender internal constructor(
    private val client: OkHttpClient,
    maxResponseBodyBytes: Long,
    private val callTimeoutMs: Long,
) : NetworkSender {
    /** Creates a sender that issues its calls through [client]. */
    public constructor(client: OkHttpClient) : this(client, DEFAULT_MAX_RESPONSE_BODY_BYTES, callTimeoutMs = 0L)

    private val maxResponseBodyBytes = maxResponseBodyBytes.coerceIn(0L, Int.MAX_VALUE.toLong())

    /** Sends [request] synchronously and maps the outcome to a [NetworkSendResult]. */
    override fun send(request: NetworkSendRequest): NetworkSendResult {
        return try {
            val builder = Request.Builder().url(request.url)
            request.headers.toOkHttpHeaders().let { builder.headers(it) }
            val mediaType = request.contentType.toOkHttpMediaType()
            val requiresBody = request.method.uppercase() in BODY_REQUIRED_METHODS
            val permitsBody = requiresBody || request.method.uppercase() in BODY_PERMITTED_METHODS
            val bodyBytes = request.body
            val requestBody =
                when {
                    requiresBody -> (bodyBytes ?: ByteArray(0)).toRequestBody(mediaType)
                    permitsBody && bodyBytes != null && bodyBytes.isNotEmpty() ->
                        bodyBytes.toRequestBody(mediaType)
                    else -> null
                }
            builder.method(request.method.uppercase(), requestBody)
            val call = client.newCall(builder.build())
            call.limitTimeoutTo(callTimeoutMs)
            call.execute().use { response ->
                val body = response.body
                NetworkSendResult.of(
                    statusCode = response.code,
                    headers = response.headers.toApiHeaders(),
                    body = body?.readAtMost(maxResponseBodyBytes),
                    contentType = body?.contentType().toApiMediaType(),
                )
            }
        } catch (e: Exception) {
            NetworkSendResult.failure(e.message ?: e.javaClass.simpleName)
        }
    }

    // Tightens, never loosens, the call timeout the client configured. When it
    // elapses OkHttp cancels the call, which also unblocks a read stalled on a
    // silent server; a thread interrupt alone cannot do that.
    private fun Call.limitTimeoutTo(timeoutMs: Long) {
        if (timeoutMs <= 0L) return
        val limitNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val currentNanos = timeout().timeoutNanos()
        if (currentNanos == 0L || currentNanos > limitNanos) {
            timeout().timeout(limitNanos, TimeUnit.NANOSECONDS)
        }
    }

    // Buffers one byte past the cap: a capturing interceptor on the client sees
    // only what is read from event streams, and flags truncation only past the cap.
    private fun ResponseBody.readAtMost(maxBytes: Long): ByteArray {
        val source = source()
        source.request(maxBytes + 1)
        return source.readByteArray(minOf(source.buffer.size, maxBytes))
    }

    private companion object {
        // OkHttp's HttpMethod.requiresRequestBody / permitsRequestBody equivalents,
        // hard-coded so we don't depend on okhttp3.internal.
        private val BODY_REQUIRED_METHODS = setOf("POST", "PUT", "PATCH")
        private val BODY_PERMITTED_METHODS = setOf("DELETE")
        private const val DEFAULT_MAX_RESPONSE_BODY_BYTES = 256L * 1024
    }
}
