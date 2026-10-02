package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.escapeForJson
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.utf8Size

/**
 * Writes captured transactions as a HAR 1.2 document, the archive format that
 * browser devtools and most HTTP tools import.
 *
 * The capture has no phase timings, so each entry spends its whole duration in
 * `wait`. Headers, the URL, and text bodies are the redacted values the store
 * keeps, and a body kept as bytes goes out in base64. What HAR has no field
 * for goes in `_lustro`, the prefix HAR reserves for a tool's own fields.
 *
 * HAR has no field for WebSocket messages either. The entry of a socket's
 * handshake carries them in `_webSocketMessages`, the field Chrome DevTools
 * writes and reads.
 */
internal object HarExport {
    private val STARTED_DATE_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    // What browser devtools write when a response has no media type.
    private const val UNKNOWN_MIME_TYPE = "x-unknown"

    /** [webSocketOf] gives the socket a transaction is the handshake of, or `null` for any other transaction. */
    fun write(
        transactions: List<NetworkTransaction>,
        creatorVersion: String,
        webSocketOf: (NetworkTransaction) -> HarWebSocket? = { null },
    ): String =
        buildString {
            append("{\"log\":{\"version\":\"1.2\",")
            append("\"creator\":{\"name\":\"Lustro\",\"version\":").appendString(creatorVersion).append("},")
            append("\"entries\":[")
            transactions.forEachIndexed { index, tx ->
                if (index > 0) append(',')
                appendEntry(tx, webSocketOf(tx))
            }
            append("]}}")
        }

    private fun StringBuilder.appendEntry(tx: NetworkTransaction, webSocket: HarWebSocket?) {
        // An in-flight request has no duration yet, and HAR has no null for one.
        val durationMs = (tx.durationMs ?: 0L).coerceAtLeast(0L)
        append('{')
        append("\"startedDateTime\":\"").append(STARTED_DATE_TIME.format(Instant.ofEpochMilli(tx.startedAt))).append("\",")
        append("\"time\":").append(durationMs).append(',')
        appendRequest(tx)
        append(',')
        appendResponse(tx)
        append(",\"cache\":{},")
        append("\"timings\":{\"blocked\":-1,\"dns\":-1,\"connect\":-1,\"ssl\":-1,")
        append("\"send\":0,\"wait\":").append(durationMs).append(",\"receive\":0},")
        // Chrome's import files a request by this, and by the media type without
        // it, which would list a JSON response as a script.
        val image = tx.responseContentType?.startsWith("image/", ignoreCase = true) == true
        val resourceType = if (webSocket != null) "websocket" else if (image) "image" else "fetch"
        append("\"_resourceType\":\"").append(resourceType).append("\",")
        if (webSocket != null) {
            append("\"_webSocketMessages\":")
            appendWebSocketMessages(webSocket.events)
            append(',')
        }
        append("\"_lustro\":{")
        append("\"id\":").appendString(tx.id).append(',')
        append("\"isMocked\":").append(tx.isMocked).append(',')
        append("\"categories\":[")
        tx.categories.forEachIndexed { index, category ->
            if (index > 0) append(',')
            appendString(category)
        }
        append("],")
        append("\"requestBodyTruncated\":").append(tx.requestBodyTruncated).append(',')
        append("\"responseBodyTruncated\":").append(tx.responseBodyTruncated).append(',')
        append("\"responseComplete\":").append(tx.responseComplete).append(',')
        append("\"error\":")
        tx.error?.let { appendString(it) } ?: append("null")
        if (webSocket != null) {
            append(",\"webSocket\":")
            appendWebSocket(webSocket.connection)
        }
        append("}}")
    }

    // type, time, opcode, and data are the keys Chrome reads. A text message
    // has opcode 1, and a binary one has opcode 2 and its data in base64.
    private fun StringBuilder.appendWebSocketMessages(events: List<WebSocketEvent>) {
        append('[')
        var written = 0
        for (event in events) {
            // A message that send() refused never left the app.
            if (event.kind != WebSocketEventKind.MESSAGE || event.enqueued == false) continue
            if (written++ > 0) append(',')
            append("{\"type\":\"").append(if (event.outgoing == true) "send" else "receive").append("\",")
            // Seconds since the Unix epoch, written without floating point.
            append("\"time\":").append(event.at / MILLIS_PER_SECOND).append('.')
            append((event.at % MILLIS_PER_SECOND).toString().padStart(MILLIS_DIGITS, '0')).append(',')
            append("\"opcode\":").append(if (event.binary) OPCODE_BINARY else OPCODE_TEXT).append(',')
            append("\"data\":").appendString(event.text ?: base64(event.bytes))
            append(",\"_payloadBytes\":").append(event.payloadBytes)
            if (event.truncated) append(",\"_truncated\":true")
            if (event.text == null && event.bytes == null) append(",\"_stored\":false")
            append('}')
        }
        append(']')
    }

    private fun StringBuilder.appendWebSocket(connection: WebSocketConnection) {
        val lifecycle = connection.lifecycle
        append("{\"id\":").appendString(connection.id).append(',')
        append("\"url\":").appendString(connection.url).append(',')
        append("\"state\":").appendString(lifecycle.state.wireName).append(',')
        append("\"closeCode\":").append(lifecycle.closeCode ?: "null").append(',')
        append("\"closeReason\":").appendNullable(lifecycle.closeReason).append(',')
        append("\"closedBy\":").appendNullable(lifecycle.closedBy).append(',')
        append("\"canceled\":").append(lifecycle.canceled).append(',')
        append("\"error\":").appendNullable(lifecycle.error).append(',')
        append("\"sentCount\":").append(connection.sentCount).append(',')
        append("\"receivedCount\":").append(connection.receivedCount).append(',')
        append("\"evictedEvents\":").append(connection.evictedEvents).append(',')
        append("\"droppedEvents\":").append(connection.droppedEvents)
        append('}')
    }

    private fun StringBuilder.appendRequest(tx: NetworkTransaction) {
        append("\"request\":{")
        append("\"method\":").appendString(tx.method).append(',')
        append("\"url\":").appendString(tx.url).append(',')
        append("\"httpVersion\":").appendString(tx.protocol.orEmpty()).append(',')
        append("\"cookies\":[],")
        append("\"headers\":")
        appendHeaders(tx.requestHeaders)
        append(",\"queryString\":")
        appendQueryString(tx.url)
        val text = tx.requestBody
        val bytes = tx.requestBinaryBody
        if (text != null || bytes != null) {
            append(",\"postData\":{\"mimeType\":").appendString(tx.requestContentType.orEmpty())
            append(",\"text\":").appendString(text ?: base64(bytes))
            // HAR gives postData no encoding field, so this one is ours.
            if (text == null) append(",\"_encoding\":\"base64\"")
            append('}')
        }
        append(",\"headersSize\":-1,")
        append("\"bodySize\":").append(tx.requestBodyBytes ?: -1L)
        append('}')
    }

    private fun StringBuilder.appendResponse(tx: NetworkTransaction) {
        val headers = tx.responseHeaders.orEmpty()
        append("\"response\":{")
        append("\"status\":").append(tx.statusCode ?: 0).append(',')
        // The capture keeps no reason phrase, and HTTP/2 has none.
        append("\"statusText\":\"\",")
        append("\"httpVersion\":").appendString(tx.protocol.orEmpty()).append(',')
        append("\"cookies\":[],")
        append("\"headers\":")
        appendHeaders(headers)
        append(',')
        appendContent(tx)
        val location = headers.entries.firstOrNull { it.key.equals("Location", ignoreCase = true) }?.value
        append(",\"redirectURL\":").appendString(location.orEmpty()).append(',')
        append("\"headersSize\":-1,")
        append("\"bodySize\":").append(tx.responseBodyBytes ?: -1L)
        // Where Chrome's own export puts why a request failed.
        tx.error?.let { append(",\"_error\":").appendString(it) }
        append('}')
    }

    private fun StringBuilder.appendContent(tx: NetworkTransaction) {
        val text = tx.responseBody
        val bytes = tx.responseBinaryBody
        // content.size is the size of the decoded body, and a gzip or deflate body
        // is kept inflated, so what was kept can be more than its size on the wire.
        val retained = text?.utf8Size() ?: bytes?.size?.toLong() ?: 0L
        append("\"content\":{")
        append("\"size\":").append(maxOf(tx.responseBodyBytes ?: 0L, retained)).append(',')
        append("\"mimeType\":").appendString(tx.responseContentType ?: UNKNOWN_MIME_TYPE)
        when {
            text != null -> append(",\"text\":").appendString(text)
            bytes != null -> append(",\"text\":").appendString(base64(bytes)).append(",\"encoding\":\"base64\"")
        }
        append('}')
    }

    private fun StringBuilder.appendHeaders(headers: Map<String, String>) {
        append('[')
        headers.entries.forEachIndexed { index, (name, value) ->
            if (index > 0) append(',')
            append("{\"name\":").appendString(name).append(",\"value\":").appendString(value).append('}')
        }
        append(']')
    }

    private fun StringBuilder.appendQueryString(url: String) {
        append('[')
        val parsed = url.toHttpUrlOrNull()
        if (parsed != null) {
            for (index in 0 until parsed.querySize) {
                if (index > 0) append(',')
                append("{\"name\":").appendString(parsed.queryParameterName(index))
                append(",\"value\":").appendString(parsed.queryParameterValue(index).orEmpty()).append('}')
            }
        }
        append(']')
    }

    private fun base64(bytes: ByteArray?): String = Base64.getEncoder().encodeToString(bytes ?: ByteArray(0))

    private fun StringBuilder.appendString(value: String): StringBuilder =
        append('"').append(value.escapeForJson()).append('"')

    private fun StringBuilder.appendNullable(value: String?): StringBuilder = if (value == null) append("null") else appendString(value)

    private const val MILLIS_PER_SECOND = 1000
    private const val MILLIS_DIGITS = 3
    private const val OPCODE_TEXT = 1
    private const val OPCODE_BINARY = 2
}

/** A socket's connection and its log, for the HAR entry of its handshake. */
internal class HarWebSocket(val connection: WebSocketConnection, val events: List<WebSocketEvent>)
