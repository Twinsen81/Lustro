package io.github.twinsen81.lustro.network

import io.github.twinsen81.lustro.internal.network.SendCaptureTag
import io.github.twinsen81.lustro.internal.network.toApiHeaders
import io.github.twinsen81.lustro.internal.network.toApiMediaType
import io.github.twinsen81.lustro.internal.network.toOkHttpHeaders
import io.github.twinsen81.lustro.internal.network.toOkHttpMediaType
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody

/**
 * The default [NetworkSender], backed by an OkHttp [OkHttpClient].
 *
 * The Network tab's Send form lists each sender by its [name], and resolves a
 * relative URL against its [baseUrl]. Register several with
 * `NetworkDebugTab.create(senders = ...)`, one for each client that the app
 * builds with its own interceptors or for its own host:
 *
 * ```kotlin
 * NetworkDebugTab.create(
 *     senders = listOf(
 *         OkHttpSender.create("API (signed in)", baseUrl = "https://api.example.com") { apiClient },
 *         OkHttpSender.create("CDN", baseUrl = "https://cdn.example.com") { cdnClient },
 *     ),
 * )
 * ```
 *
 * The runtime invokes [send] off the main thread and blocks for the result; this
 * implementation issues a synchronous OkHttp call. A sent request appears as its
 * own row in the traffic list only when the client carries the Lustro
 * interceptor. Otherwise the Send form still reports the outcome (status,
 * success), but the request is not captured.
 *
 * At most 256 KB of the response body is read into [NetworkSendResult.body]. The
 * response is then closed without reading the rest, so a large or endless
 * response cannot exhaust the heap.
 */
public class OkHttpSender internal constructor(
    /** The name that the Send form lists this sender under. Unique within a tab. */
    public val name: String,
    /**
     * The URL that a relative URL in the Send form resolves against, or `null`
     * when this sender takes absolute URLs only.
     */
    public val baseUrl: String?,
    clientProvider: () -> OkHttpClient,
) : NetworkSender {
    /**
     * Creates a sender named "Default" that issues its calls through [client].
     * Its relative URLs resolve against `DebugConfig.appServerBaseUrl` when the
     * tab gets it as `senderClient`.
     */
    public constructor(client: OkHttpClient) : this(DEFAULT_NAME, null, { client })

    // Kept after the first call that succeeds, so a provider that builds a new
    // client doesn't get a connection pool for each send. A provider that throws
    // is asked again on the next send.
    private val client: OkHttpClient by lazy(clientProvider)

    /** Sends [request] synchronously and maps the outcome to a [NetworkSendResult]. */
    override fun send(request: NetworkSendRequest): NetworkSendResult =
        send(request, DEFAULT_MAX_RESPONSE_BODY_BYTES, callTimeoutMs = 0L, captureTag = null)

    /**
     * Sends [request] with the tab's limits. [captureTag] rides on the OkHttp
     * request, so a Lustro interceptor on the client can report which
     * transaction it recorded.
     */
    internal fun send(
        request: NetworkSendRequest,
        maxResponseBodyBytes: Long,
        callTimeoutMs: Long,
        captureTag: SendCaptureTag?,
    ): NetworkSendResult {
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
            if (captureTag != null) builder.tag(SendCaptureTag::class.java, captureTag)
            val call = client.newCall(builder.build())
            call.limitTimeoutTo(callTimeoutMs)
            call.execute().use { response ->
                val body = response.body
                NetworkSendResult.of(
                    statusCode = response.code,
                    headers = response.headers.toApiHeaders(),
                    body = body?.readAtMost(maxResponseBodyBytes.coerceIn(0L, Int.MAX_VALUE.toLong())),
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

    /** Factories for [OkHttpSender]. */
    public companion object {
        // OkHttp's HttpMethod.requiresRequestBody / permitsRequestBody equivalents,
        // hard-coded so we don't depend on okhttp3.internal.
        private val BODY_REQUIRED_METHODS = setOf("POST", "PUT", "PATCH")
        private val BODY_PERMITTED_METHODS = setOf("DELETE")
        private const val DEFAULT_MAX_RESPONSE_BODY_BYTES = 256L * 1024
        private const val DEFAULT_NAME = "Default"

        /**
         * Creates a sender that the Send form lists as [name].
         *
         * Lustro calls [client] only when a request is sent through this sender,
         * and keeps the client it returns. So a client that has to be built after
         * Lustro, because it carries the interceptor from
         * `Lustro.networkInterceptor()`, can still be the sender, and the
         * requests sent through it are captured.
         *
         * @param name the name the Send form lists the sender under; must not be
         *   blank, and must be unique among the senders of one tab.
         * @param baseUrl the absolute `http` or `https` URL that a relative URL
         *   resolves against: the path is appended to it, so
         *   `https://api.example.com/v1` and `/items` give
         *   `https://api.example.com/v1/items`. `null` (the default) means the
         *   sender takes absolute URLs only.
         * @param client returns the client to send through. Called off the main
         *   thread, at the first send.
         * @throws IllegalArgumentException when [name] is blank or [baseUrl] is
         *   not an absolute `http` or `https` URL.
         */
        @JvmStatic
        @JvmOverloads
        public fun create(
            name: String,
            baseUrl: String? = null,
            client: () -> OkHttpClient,
        ): OkHttpSender {
            require(name.isNotBlank()) { "A sender's name must not be blank" }
            require(baseUrl == null || baseUrl.toHttpUrlOrNull() != null) {
                "baseUrl must be an absolute http or https URL: $baseUrl"
            }
            return OkHttpSender(name, baseUrl, client)
        }
    }
}
