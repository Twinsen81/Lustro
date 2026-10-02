package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
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
    private val stateJson: () -> String,
) {
    fun handle(request: DebugRequest): DebugResponse? {
        if (!request.method.equals("GET", ignoreCase = true)) return null
        val segments = request.path.split('/')
        return when {
            segments.size == 1 -> connections(request)
            segments.size == 2 -> connection(segments[1])
            segments.size == 3 && segments[2] == "events" -> events(segments[1], request)
            segments.size == 5 && segments[2] == "events" && segments[4] == "payload" -> payload(segments[1], segments[3])
            else -> null
        }
    }

    private fun connections(request: DebugRequest): DebugResponse =
        DebugResponse.cursorEnvelope(
            currentSequence = store.getSequence(),
            clientCursor = request.queryParam("cursor"),
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

    private fun events(id: String, request: DebugRequest): DebugResponse {
        val log = store.getEvents(id) ?: return DebugResponse.notFound(NO_CONNECTION)
        val direction = request.queryParam("direction")?.takeIf { it.isNotEmpty() }
        if (direction != null && direction != SENT && direction != RECEIVED) {
            return DebugResponse.error("direction must be $SENT or $RECEIVED", field = "direction")
        }
        val limit =
            when (val given = request.queryParam("limit")?.takeIf { it.isNotEmpty() }) {
                null -> DEFAULT_LIMIT
                // No upper limit: the log's own limits bound a response.
                else -> given.toIntOrNull()?.takeIf { it >= 1 }
                    ?: return DebugResponse.error("limit must be a whole number, 1 or more", field = "limit")
            }
        val needle = request.queryParam("search")?.takeIf { it.isNotBlank() }?.foldCase()
        val matches = { event: WebSocketEvent ->
            (direction == null || event.outgoing == (direction == SENT)) &&
                (needle == null || event.text?.containsFolded(needle) == true)
        }
        val events = log.events
        return DebugResponse.streamEnvelope(
            total = log.stored,
            retained = events.size,
            clientCursor = request.queryParam("cursor"),
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
    private fun payload(id: String, seqText: String): DebugResponse {
        val log = store.getEvents(id) ?: return DebugResponse.notFound(NO_CONNECTION)
        val seq = seqText.toLongOrNull()
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
