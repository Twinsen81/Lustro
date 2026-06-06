package io.github.twinsen81.lustro.network

import io.github.twinsen81.lustro.internal.network.toApiHeaders
import io.github.twinsen81.lustro.internal.network.toApiMediaType
import io.github.twinsen81.lustro.internal.network.toOkHttpHeaders
import io.github.twinsen81.lustro.internal.network.toOkHttpMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The default [NetworkSender], backed by an OkHttp [OkHttpClient].
 *
 * The runtime invokes [send] off the main thread and blocks for the result; this
 * implementation issues a synchronous OkHttp call. Replayed requests appear as
 * their own rows in the traffic list only when [client] carries the Lustro
 * interceptor — pass such a client if you want that. Otherwise the Send panel
 * still reports the inline outcome (status, success), but the replay is not
 * captured as a transaction.
 */
public class OkHttpSender(
    private val client: OkHttpClient,
) : NetworkSender {
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
            client.newCall(builder.build()).execute().use { response ->
                val body = response.body
                NetworkSendResult.of(
                    statusCode = response.code,
                    headers = response.headers.toApiHeaders(),
                    body = body?.bytes(),
                    contentType = body?.contentType().toApiMediaType(),
                )
            }
        } catch (e: Exception) {
            NetworkSendResult.failure(e.message ?: e.javaClass.simpleName)
        }
    }

    private companion object {
        // OkHttp's HttpMethod.requiresRequestBody / permitsRequestBody equivalents,
        // hard-coded so we don't depend on okhttp3.internal.
        private val BODY_REQUIRED_METHODS = setOf("POST", "PUT", "PATCH")
        private val BODY_PERMITTED_METHODS = setOf("DELETE")
    }
}
