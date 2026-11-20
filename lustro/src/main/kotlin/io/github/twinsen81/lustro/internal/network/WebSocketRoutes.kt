package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugRoutes
import io.github.twinsen81.lustro.DebugRoutes.Companion.ref
import io.github.twinsen81.lustro.ExperimentalDebugRoutes
import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.MediaType
import io.github.twinsen81.lustro.escapeForJson
import io.github.twinsen81.lustro.internal.toDebugTimestamp

/**
 * The Network tab's routes under `websockets`: the connections, and each one's
 * event log.
 *
 * The connections are a snapshot list, so they use the cursor envelope. An
 * event log only grows at its end, and sending it whole on every message would
 * cost more with every message, so it uses the stream envelope: a poll gets
 * the events after its cursor.
 */
internal class WebSocketRoutes(
    private val store: WebSocketTrafficStore,
    private val searchTimeoutMs: () -> Long,
    private val stateJson: () -> String,
) {
    /** Declares the routes, with the text of their OpenAPI document in [NetworkDocument]. */
    @OptIn(ExperimentalDebugRoutes::class)
    fun declare(routes: DebugRoutes) {
        routes.get("websockets") {
            operationId = "listWebSockets"
            summary = "Poll captured WebSocket connections with an opaque cursor."
            description = NetworkDocument.WEBSOCKETS
            val cursor = query.string("cursor") { description = NetworkDocument.CURSOR }
            response("Cursor envelope.", ref("WebSocketCursorEnvelope"))
            handle { call -> connections(call[cursor]) }
        }
        routes.get("websockets/{id}") {
            operationId = "getWebSocket"
            summary = "One WebSocket connection, with the headers of its handshake."
            val id = path.string("id")
            response("Connection detail.", ref("WebSocketConnection"))
            errors(404)
            handle { call -> connection(call[id]) }
        }
        routes.get("websockets/{id}/events") {
            operationId = "listWebSocketEvents"
            summary = "Poll a connection's log: its messages and lifecycle events, in order."
            description = NetworkDocument.EVENTS
            val id = path.string("id")
            val cursor = query.string("cursor") { description = NetworkDocument.CURSOR }
            // No upper limit: the log's own limits bound a response.
            val limit = query.int("limit") { min = 1; description = NetworkDocument.EVENTS_LIMIT }.withDefault(DEFAULT_LIMIT)
            val direction = query.string("direction") { values = listOf(SENT, RECEIVED); description = NetworkDocument.EVENTS_DIRECTION }
            val search = query.string("search") { description = NetworkDocument.EVENTS_SEARCH }
            val regex = query.boolean("regex") { description = NetworkDocument.REGEX }.withDefault(false)
            response("Stream envelope.", ref("WebSocketEventStreamEnvelope"))
            errors(404)
            handle { call -> events(call[id], call[cursor], call[limit], call[direction], call[search], call[regex]) }
        }
        routes.get("websockets/{id}/events/{seq}/payload") {
            operationId = "getWebSocketPayload"
            summary = "The payload of one message, as it is stored."
            description = NetworkDocument.PAYLOAD
            val id = path.string("id")
            val seq = path.long("seq") { description = "The event's seq." }
            response("The payload.") {
                header("Content-Disposition", "attachment, always.")
                header("Content-Security-Policy", NetworkDocument.PAYLOAD_CSP)
                content("*/*")
            }
            errors(404)
            handle { call -> payload(call[id], call[seq]) }
        }
    }

    private fun connections(cursor: String?): DebugResponse =
        DebugResponse.cursorEnvelope(
            currentSequence = store.getSequence(),
            clientCursor = cursor,
            state = stateJson(),
            epoch = store.epoch,
        ) {
            store.getConnections().forEachIndexed { index, connection ->
                if (index > 0) append(',')
                appendConnection(connection, brief = true)
            }
        }

    private fun connection(id: String): DebugResponse {
        val connection = store.getConnection(id) ?: return DebugResponse.notFound(NO_CONNECTION)
        return DebugResponse.json { appendConnection(connection, brief = false) }
    }

    private fun events(id: String, cursor: String?, limit: Int, direction: String?, search: String?, regex: Boolean): DebugResponse {
        val log = store.getEvents(id) ?: return DebugResponse.notFound(NO_CONNECTION)
        return respondWithSearch(search, regex) { query ->
            val budget = SearchBudget(searchTimeoutMs())
            eventsEnvelope(log, limit, cursor) { event ->
                (direction == null || event.outgoing == (direction == SENT)) &&
                    (query == null || query.matchesText(event.text, budget))
            }
        }
    }

    private fun eventsEnvelope(
        log: WebSocketEventLog,
        limit: Int,
        cursor: String?,
        matches: (WebSocketEvent) -> Boolean,
    ): DebugResponse {
        val events = log.events
        return DebugResponse.streamEnvelope(
            total = log.stored,
            retained = events.size,
            clientCursor = cursor,
            epoch = log.epoch,
        ) { from, reset ->
            if (reset) {
                // A new client starts with the end of the log.
                events.filter(matches).takeLast(limit).forEachIndexed { index, event ->
                    if (index > 0) append(',')
                    appendEvent(event)
                }
                events.size
            } else {
                var written = 0
                var index = from
                // A long log arrives in pages: the next poll goes on after the last event of this one.
                while (index < events.size && written < limit) {
                    val event = events[index++]
                    if (!matches(event)) continue
                    if (written++ > 0) append(',')
                    appendEvent(event)
                }
                index
            }
        }
    }

    /**
     * Serves a message's payload as it is stored: the bytes of a binary message,
     * or the redacted text of a text one as UTF-8. Always an attachment with a
     * sandboxing policy, so a captured payload can never run script under the
     * console's origin.
     */
    private fun payload(id: String, seq: Long): DebugResponse {
        val log = store.getEvents(id) ?: return DebugResponse.notFound(NO_CONNECTION)
        val event = log.events.firstOrNull { it.seq == seq } ?: return DebugResponse.notFound("Event not found")
        val headers = Headers.of("Content-Disposition" to "attachment", "Content-Security-Policy" to ATTACHMENT_CSP)
        val bytes = event.bytes
        val text = event.text
        return when {
            bytes != null -> DebugResponse.bytes(bytes, MediaType.OCTET_STREAM, headers = headers)
            text != null -> DebugResponse.bytes(text.toByteArray(Charsets.UTF_8), MediaType.TEXT, headers = headers)
            else -> DebugResponse.notFound("No payload was retained")
        }
    }

    private fun StringBuilder.appendConnection(connection: WebSocketConnection, brief: Boolean) {
        val lifecycle = connection.lifecycle
        append("{\"id\":").appendString(connection.id)
        append(",\"url\":").appendString(connection.url)
        append(",\"state\":").appendString(lifecycle.state.wireName)
        append(",\"timestamp\":").appendString(connection.startedAt.toDebugTimestamp())
        append(",\"startedAt\":").append(connection.startedAt)
        append(",\"openedAt\":").append(lifecycle.openedAt ?: "null")
        append(",\"closedAt\":").append(lifecycle.closedAt ?: "null")
        append(",\"statusCode\":").append(lifecycle.statusCode ?: "null")
        append(",\"closeCode\":").append(lifecycle.closeCode ?: "null")
        append(",\"closeReason\":").appendNullable(lifecycle.closeReason)
        append(",\"closedBy\":").appendNullable(lifecycle.closedBy)
        append(",\"canceled\":").append(lifecycle.canceled)
        append(",\"error\":").appendNullable(lifecycle.error)
        append(",\"transactionId\":").appendNullable(connection.transactionId)
        append(",\"categories\":[")
        connection.categories.forEachIndexed { index, category ->
            if (index > 0) append(',')
            appendString(category)
        }
        append("],\"sentCount\":").append(connection.sentCount)
        append(",\"sentBytes\":").append(connection.sentBytes)
        append(",\"receivedCount\":").append(connection.receivedCount)
        append(",\"receivedBytes\":").append(connection.receivedBytes)
        append(",\"storedEvents\":").append(connection.storedEvents)
        append(",\"evictedEvents\":").append(connection.evictedEvents)
        append(",\"droppedEvents\":").append(connection.droppedEvents)
        if (!brief) {
            append(",\"protocol\":").appendNullable(lifecycle.protocol)
            append(",\"requestHeaders\":").appendHeaders(connection.requestHeaders)
            append(",\"responseHeaders\":")
            lifecycle.responseHeaders?.let { appendHeaders(it) } ?: append("null")
        }
        append('}')
    }

    // Only the keys an event's kind has, so a long log stays small.
    private fun StringBuilder.appendEvent(event: WebSocketEvent) {
        append("{\"seq\":").append(event.seq)
        append(",\"at\":").append(event.at)
        append(",\"timestamp\":").appendString(event.at.toDebugTimestamp())
        append(",\"kind\":").appendString(event.kind.wireName)
        event.outgoing?.let { append(",\"direction\":").appendString(if (it) SENT else RECEIVED) }
        when (event.kind) {
            WebSocketEventKind.MESSAGE -> appendMessage(event)
            WebSocketEventKind.CLOSE, WebSocketEventKind.CLOSED -> {
                append(",\"code\":").append(event.code ?: "null")
                append(",\"reason\":").appendNullable(event.reason)
            }
            WebSocketEventKind.OPEN -> append(",\"statusCode\":").append(event.statusCode ?: "null")
            WebSocketEventKind.FAILURE -> {
                append(",\"statusCode\":").append(event.statusCode ?: "null")
                append(",\"error\":").appendNullable(event.error)
            }
            WebSocketEventKind.CANCEL -> Unit
        }
        event.enqueued?.let { append(",\"enqueued\":").append(it) }
        append('}')
    }

    private fun StringBuilder.appendMessage(event: WebSocketEvent) {
        append(",\"type\":").appendString(if (event.binary) "binary" else "text")
        append(",\"payloadBytes\":").append(event.payloadBytes)
        append(",\"stored\":").append(event.text != null || event.bytes != null)
        append(",\"truncated\":").append(event.truncated)
        val text = event.text
        if (text != null) {
            val preview = text.previewPrefix()
            append(",\"preview\":").appendString(preview)
            append(",\"previewComplete\":").append(preview.length == text.length)
        }
        event.bytes?.let { bytes ->
            append(",\"hexPreview\":\"")
            for (i in 0 until minOf(bytes.size, HEX_PREVIEW_BYTES)) {
                val value = bytes[i].toInt() and BYTE_MASK
                append(HEX_DIGITS[value shr NIBBLE_BITS]).append(HEX_DIGITS[value and NIBBLE_MASK])
            }
            append('"')
        }
    }

    // Not cut between the two halves of a surrogate pair.
    private fun String.previewPrefix(): String {
        if (length <= PREVIEW_CHARS) return this
        val end = if (this[PREVIEW_CHARS - 1].isHighSurrogate()) PREVIEW_CHARS - 1 else PREVIEW_CHARS
        return substring(0, end)
    }

    private fun StringBuilder.appendHeaders(headers: Map<String, String>): StringBuilder {
        append('{')
        headers.entries.forEachIndexed { index, (name, value) ->
            if (index > 0) append(',')
            appendString(name).append(':').appendString(value)
        }
        return append('}')
    }

    private fun StringBuilder.appendString(value: String): StringBuilder = append('"').append(value.escapeForJson()).append('"')

    private fun StringBuilder.appendNullable(value: String?): StringBuilder = if (value == null) append("null") else appendString(value)

    private companion object {
        private const val NO_CONNECTION = "WebSocket connection not found"
        private const val SENT = "sent"
        private const val RECEIVED = "received"
        private const val DEFAULT_LIMIT = 100
        private const val PREVIEW_CHARS = 512
        private const val HEX_PREVIEW_BYTES = 32
        private const val HEX_DIGITS = "0123456789abcdef"
        private const val BYTE_MASK = 0xFF
        private const val NIBBLE_BITS = 4
        private const val NIBBLE_MASK = 0x0F

        // No subresources and no script, even when a browser renders the payload.
        private const val ATTACHMENT_CSP = "default-src 'none'; sandbox"
    }
}
