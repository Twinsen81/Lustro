package io.github.twinsen81.lustro.network

import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.MediaType

/**
 * The outcome of a [NetworkSender.send] call.
 *
 * Consumer-constructed via the [companion object][Companion] factories. Lives in
 * `:lustro-api` so both the release and no-op runtimes can build it. A result is
 * a [success][isSuccess] when [errorMessage] is `null`.
 */
public class NetworkSendResult private constructor(
    /** The HTTP status code, or `0` for a transport failure. */
    public val statusCode: Int,
    /** The response headers. */
    public val headers: Headers,
    /** The response body bytes, or `null` if there is no body. */
    public val body: ByteArray?,
    /** The `Content-Type` of the body, or `null` if absent. */
    public val contentType: MediaType?,
    /** The failure message, or `null` when the send succeeded. */
    public val errorMessage: String?,
) {
    /** Whether the send succeeded (i.e. [errorMessage] is `null`). */
    public val isSuccess: Boolean
        get() = errorMessage == null

    /** Factories for creating [NetworkSendResult] instances. */
    public companion object {
        /**
         * A successful result with the given [statusCode], and optional
         * [headers] (default empty), [body] (default `null`), and [contentType]
         * (default `null`).
         */
        @JvmStatic
        @JvmOverloads
        public fun of(
            statusCode: Int,
            headers: Headers = Headers.EMPTY,
            body: ByteArray? = null,
            contentType: MediaType? = null,
        ): NetworkSendResult =
            NetworkSendResult(
                statusCode = statusCode,
                headers = headers,
                body = body,
                contentType = contentType,
                errorMessage = null,
            )

        /**
         * A transport-failure result carrying [errorMessage]; [statusCode] is
         * `0`.
         */
        @JvmStatic
        public fun failure(errorMessage: String): NetworkSendResult =
            NetworkSendResult(
                statusCode = 0,
                headers = Headers.EMPTY,
                body = null,
                contentType = null,
                errorMessage = errorMessage,
            )
    }
}
