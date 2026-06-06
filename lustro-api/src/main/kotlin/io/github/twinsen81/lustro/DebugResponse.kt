package io.github.twinsen81.lustro

/**
 * A response produced by a [DebugTab] and served by the Lustro runtime.
 *
 * This is an interface so the runtime serves it; the concrete implementation is
 * [internal][RealDebugResponse] to this module. Construct instances through the
 * [companion object][Companion] factories. The [body] is always raw bytes.
 */
public interface DebugResponse {
    /** The HTTP status code. */
    public val status: Int

    /** The response headers (excludes the content type). */
    public val headers: Headers

    /** The `Content-Type` of the [body], or `null` if unspecified. */
    public val contentType: MediaType?

    /** The raw response body bytes. */
    public val body: ByteArray

    /** Factories for the common response shapes. */
    public companion object {
        /**
         * A `application/json; charset=utf-8` response carrying the already
         * serialized [json] string, with the given [status] (default `200`).
         */
        @JvmStatic
        @JvmOverloads
        public fun ok(json: String, status: Int = 200): DebugResponse =
            RealDebugResponse(
                status = status,
                headers = Headers.EMPTY,
                contentType = MediaType.JSON,
                body = json.toByteArray(Charsets.UTF_8),
            )

        /**
         * A `application/json; charset=utf-8` response whose body is built by
         * applying [build] to a [StringBuilder], with the given [status]
         * (default `200`).
         */
        @JvmStatic
        public fun json(status: Int = 200, build: StringBuilder.() -> Unit): DebugResponse =
            RealDebugResponse(
                status = status,
                headers = Headers.EMPTY,
                contentType = MediaType.JSON,
                body = buildString(build).toByteArray(Charsets.UTF_8),
            )

        /**
         * A text response carrying [body], with the given [status] (default
         * `200`) and [contentType] (default `text/plain; charset=utf-8`).
         */
        @JvmStatic
        @JvmOverloads
        public fun text(body: String, status: Int = 200, contentType: MediaType? = null): DebugResponse =
            RealDebugResponse(
                status = status,
                headers = Headers.EMPTY,
                contentType = contentType ?: MediaType.TEXT,
                body = body.toByteArray(Charsets.UTF_8),
            )

        /**
         * A response carrying arbitrary [body] bytes, with the given
         * [contentType] (default `application/octet-stream`), [status] (default
         * `200`), and [headers] (default empty).
         */
        @JvmStatic
        @JvmOverloads
        public fun bytes(
            body: ByteArray,
            contentType: MediaType? = null,
            status: Int = 200,
            headers: Headers = Headers.EMPTY,
        ): DebugResponse =
            RealDebugResponse(
                status = status,
                headers = headers,
                contentType = contentType ?: MediaType.OCTET_STREAM,
                body = body,
            )

        /**
         * A `404` enveloped error response. The body is the uniform error
         * envelope JSON with [message] (default `"Not found"`).
         */
        @JvmStatic
        @JvmOverloads
        public fun notFound(message: String = "Not found"): DebugResponse =
            error(message = message, status = 404)

        /**
         * An enveloped error response. The body is the uniform error envelope
         * JSON `{"error":<type>,"message":...,"code":?,"field":?,"hint":?}`,
         * where `<type>` is derived from [status]. Optional [code], [field], and
         * [hint] keys are omitted when `null`. All string values are escaped via
         * [escapeForJson].
         */
        @JvmStatic
        @JvmOverloads
        public fun error(
            message: String,
            status: Int = 400,
            code: String? = null,
            field: String? = null,
            hint: String? = null,
        ): DebugResponse {
            val body = buildString {
                append('{')
                append("\"error\":\"").append(errorTypeFor(status).escapeForJson()).append('"')
                append(",\"message\":\"").append(message.escapeForJson()).append('"')
                if (code != null) {
                    append(",\"code\":\"").append(code.escapeForJson()).append('"')
                }
                if (field != null) {
                    append(",\"field\":\"").append(field.escapeForJson()).append('"')
                }
                if (hint != null) {
                    append(",\"hint\":\"").append(hint.escapeForJson()).append('"')
                }
                append('}')
            }
            return RealDebugResponse(
                status = status,
                headers = Headers.EMPTY,
                contentType = MediaType.JSON,
                body = body.toByteArray(Charsets.UTF_8),
            )
        }

        /** Maps an HTTP [status] to the canonical error envelope `error` type. */
        private fun errorTypeFor(status: Int): String =
            when (status) {
                400 -> "bad_request"
                401 -> "unauthorized"
                403 -> "forbidden"
                404 -> "not_found"
                405 -> "method_not_allowed"
                413 -> "payload_too_large"
                500 -> "internal_error"
                503 -> "unavailable"
                504 -> "timeout"
                else -> "error"
            }
    }
}

/**
 * The internal concrete [DebugResponse] built by the factories. Kept `internal`
 * so consumers must use the factories, while `:lustro-noop` consumers still
 * link against the interface.
 */
internal class RealDebugResponse(
    override val status: Int,
    override val headers: Headers,
    override val contentType: MediaType?,
    override val body: ByteArray,
) : DebugResponse
