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

    /**
     * The response headers (excludes the content type). A
     * `Content-Security-Policy` among them is enforced alongside the server's
     * own policy, so it can only restrict the response further.
     */
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
         * A cursor-envelope response for observable list routes (the wire
         * protocol's live-polling shape):
         * `{"cursor":..,"status":..,("items":[..],)?(,"state":..)?}`.
         *
         * The `status` is derived by checking [clientCursor] — the cursor the
         * client echoed back, typically `request.queryParam("cursor")` —
         * against [currentSequence], the server's current change sequence:
         * absent, undecodable, or foreign cursor → `reset`; equal →
         * `unchanged` (items omitted); anything else → `delta`. For `reset`
         * and `delta`, [appendItems] writes the comma-separated JSON array
         * elements (the enclosing `"items":[...]` is emitted by this factory);
         * per the envelope contract the items always carry the full
         * authoritative list. A non-null [state] must be a valid JSON value and
         * is appended verbatim as the envelope's `state` member.
         *
         * Advance the sequence whenever the list changes, and only then: every
         * advance re-sends the whole list to every poller. Observable state
         * that isn't part of the list belongs in [state], which is sent with
         * every response, `unchanged` ones included.
         *
         * [epoch] identifies the store that counts [currentSequence]; a cursor
         * issued under any other epoch is foreign. That keeps a restarted store
         * from answering `unchanged` to a client still holding a cursor from
         * before the restart once its new sequence catches up. The default is
         * picked once per process, which covers app restarts. A store that can
         * be recreated within a process should pick its own when it's created,
         * for example with `Random.nextLong()`.
         *
         * This factory implements snapshot-list semantics only. For a route
         * with append-stream semantics (e.g. a log tail, where a poll returns
         * only the entries after the cursor), use [streamEnvelope].
         */
        @JvmStatic
        @JvmOverloads
        public fun cursorEnvelope(
            currentSequence: Long,
            clientCursor: String?,
            state: String? = null,
            epoch: Long = CursorCodec.processEpoch,
            appendItems: StringBuilder.() -> Unit,
        ): DebugResponse =
            json {
                append("{\"cursor\":\"")
                append(CursorCodec.encode(currentSequence, epoch).escapeForJson())
                append("\",")
                val clientSequence = CursorCodec.decode(clientCursor, epoch)
                when {
                    clientSequence == null -> {
                        append("\"status\":\"reset\",\"items\":[")
                        appendItems()
                        append("]")
                    }
                    clientSequence == currentSequence -> append("\"status\":\"unchanged\"")
                    else -> {
                        append("\"status\":\"delta\",\"items\":[")
                        appendItems()
                        append("]")
                    }
                }
                if (state != null) {
                    append(",\"state\":").append(state)
                }
                append("}")
            }

        /**
         * A stream-envelope response for a route whose list only grows at its
         * end, such as a log:
         * `{"cursor":..,"status":..,("items":[..],)?("dropped":n,)?("state":..)?}`.
         *
         * A poll gets only the entries after its cursor, so a long list isn't
         * sent again each time it grows. The cursor counts entries: [total] is
         * how many the list ever had, and [retained] how many of those, the
         * newest, it still holds. From [clientCursor], the cursor the client
         * echoed back, the `status` is:
         * - `reset` for an absent, undecodable, or foreign cursor, and for one
         *   past the end. [appendItems] gets `from = 0` and `reset = true`, and
         *   writes what a new client starts with, usually the last entries.
         * - `unchanged` when the client has every entry. Items are omitted.
         * - `delta` otherwise. [appendItems] gets, as `from`, the index among
         *   the retained entries of the first one the client doesn't have, and
         *   `dropped` says how many entries the list evicted before the client
         *   got them.
         *
         * [appendItems] writes the comma-separated JSON array elements (this
         * factory emits the enclosing `"items":[...]`) and returns the index
         * just past the last retained entry it dealt with: [retained] when it
         * reached the end, or less when it stopped at a page limit, and then
         * the next poll goes on from there. An entry that a filter left out
         * counts as dealt with.
         *
         * A non-null [state] must be a valid JSON value and is appended
         * verbatim as the envelope's `state` member, with every status.
         *
         * [epoch] identifies the list, as in [cursorEnvelope]: a cursor issued
         * under another epoch is foreign. A list that can be emptied and filled
         * again should take a new epoch when that happens, so a client with a
         * cursor from before gets a `reset` and replaces what it has.
         */
        @JvmStatic
        @JvmOverloads
        public fun streamEnvelope(
            total: Long,
            retained: Int,
            clientCursor: String?,
            state: String? = null,
            epoch: Long = CursorCodec.processEpoch,
            appendItems: StringBuilder.(from: Int, reset: Boolean) -> Int,
        ): DebugResponse =
            json {
                val held = retained.toLong().coerceIn(0L, total.coerceAtLeast(0L)).toInt()
                val evicted = total.coerceAtLeast(0L) - held
                val seen = CursorCodec.decode(clientCursor, epoch)?.takeIf { it in 0..evicted + held }
                val items = StringBuilder()
                var status = "unchanged"
                var next = evicted + held
                var dropped = 0L
                if (seen == null) {
                    status = "reset"
                    next = evicted + items.appendItems(0, true).coerceIn(0, held)
                } else if (seen != next) {
                    status = "delta"
                    dropped = (evicted - seen).coerceAtLeast(0L)
                    val from = (seen - evicted).coerceAtLeast(0L).toInt()
                    next = evicted + items.appendItems(from, false).coerceIn(from, held)
                }
                append("{\"cursor\":\"").append(CursorCodec.encode(next, epoch).escapeForJson()).append("\",")
                append("\"status\":\"").append(status).append('"')
                if (status != "unchanged") append(",\"items\":[").append(items).append(']')
                if (status == "delta") append(",\"dropped\":").append(dropped)
                if (state != null) append(",\"state\":").append(state)
                append('}')
            }

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
