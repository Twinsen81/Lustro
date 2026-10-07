package io.github.twinsen81.lustro.internal.network

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the golden HAR fixture to what [HarExport] writes, so the fixture that
 * clients test against can't drift from the server.
 */
class HarExportGoldenTest {
    private val gif =
        byteArrayOf(
            71, 73, 70, 56, 57, 97, 1, 0, 1, 0, -128, 0, 0, -1, -1, -1, 0, 0, 0, 33, -7, 4, 1, 0, 0, 0, 0,
            44, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 68, 1, 0, 59,
        )

    private val transactions =
        listOf(
            NetworkTransaction(
                id = "tx_4b1d77a0",
                startedAt = 1790605329003,
                completedAt = 1790605329325,
                durationMs = 322,
                categories = listOf("api"),
                method = "POST",
                url = "https://api.example.com/v1/orders",
                requestHeaders =
                    mapOf("Content-Type" to "application/json; charset=utf-8", "Authorization" to "[REDACTED]"),
                requestBody = """{"sku":"A-1","quantity":2}""",
                requestContentType = "application/json; charset=utf-8",
                requestBodyBytes = 26,
                protocol = "h2",
                statusCode = 201,
                responseHeaders = mapOf("Content-Type" to "application/json; charset=utf-8", "Location" to "/v1/orders/9c2e"),
                responseBody = """{"id":"9c2e","sku":"A-1","quantity":2,"status":"open"}""",
                responseContentType = "application/json; charset=utf-8",
                responseBodyBytes = 54,
                responseComplete = true,
            ),
            NetworkTransaction(
                id = "tx_77e2c014",
                startedAt = 1790605331781,
                completedAt = 1790605332822,
                durationMs = 41,
                throttledMs = 1000,
                categories = listOf("api"),
                method = "GET",
                url = "https://api.example.com/v1/orders/77e2",
                requestHeaders = mapOf("Accept" to "application/json", "Authorization" to "[REDACTED]"),
                requestBodyBytes = 0,
                statusCode = 500,
                responseHeaders = mapOf("Content-Type" to "application/json"),
                responseBody = """{"error":"boom"}""",
                responseContentType = "application/json",
                responseBodyBytes = 16,
                responseComplete = true,
                isMocked = true,
            ),
            NetworkTransaction(
                id = "tx_e6b0d413",
                startedAt = 1790605333140,
                completedAt = 1790605333175,
                durationMs = 35,
                categories = listOf("media"),
                method = "GET",
                url = "https://cdn.example.com/pixel.gif",
                requestHeaders = mapOf("Accept" to "image/*"),
                protocol = "http/1.1",
                statusCode = 200,
                responseHeaders = mapOf("Content-Type" to "image/gif", "Content-Length" to "43"),
                responseBinaryBody = gif,
                responseContentType = "image/gif",
                responseBodyBytes = 43,
                responseComplete = true,
                source = CaptureSource.PLATFORM,
            ),
            NetworkTransaction(
                id = "tx_5e8a2f61",
                startedAt = 1790605334022,
                completedAt = 1790605335232,
                durationMs = 1210,
                categories = listOf("api"),
                method = "GET",
                url = "https://api.example.com/v1/catalog?page=2&tag=new%20in",
                requestHeaders = mapOf("Accept" to "application/json"),
                protocol = "h2",
                statusCode = 200,
                responseHeaders = mapOf("Content-Type" to "application/json; charset=utf-8"),
                responseBody = """[{"sku":"A-1","name":"Desk lamp"},{"sku":"A-2","na""",
                responseBodyTruncated = true,
                responseContentType = "application/json; charset=utf-8",
                responseBodyBytes = 524288,
                responseComplete = true,
                finalUrl = "https://eu.api.example.com/v1/catalog?page=2&tag=new%20in",
                priorResponses = listOf(PriorResponse("https://api.example.com/v1/catalog?page=2&tag=new%20in", 302)),
            ),
            NetworkTransaction(
                id = "tx_d03a9b44",
                startedAt = 1790605335917,
                completedAt = 1790605335925,
                durationMs = 8,
                method = "GET",
                url = "https://telemetry.example.com/v1/events",
                requestHeaders = mapOf("Accept" to "application/json"),
                responseComplete = true,
                error = "java.net.UnknownHostException: Unable to resolve host \"telemetry.example.com\"",
            ),
        )

    // A socket's handshake, as the interceptor captures it, and the socket's log.
    private val handshake =
        NetworkTransaction(
            id = "tx_a81c3f09",
            startedAt = 1790605337100,
            completedAt = 1790605337242,
            durationMs = 142,
            categories = listOf("chat"),
            method = "GET",
            url = "https://chat.example.com/socket?token=%5BREDACTED%5D",
            requestHeaders =
                mapOf(
                    "Upgrade" to "websocket",
                    "Connection" to "Upgrade",
                    "Sec-WebSocket-Key" to "[REDACTED]",
                    "Sec-WebSocket-Version" to "13",
                    "Sec-WebSocket-Extensions" to "permessage-deflate",
                ),
            protocol = "http/1.1",
            statusCode = 101,
            responseHeaders =
                mapOf("Upgrade" to "websocket", "Connection" to "Upgrade", "Sec-WebSocket-Accept" to "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="),
            responseBodyBytes = 0,
            responseComplete = true,
        )

    private val webSocket =
        HarWebSocket(
            connection =
                WebSocketConnection(
                    id = "ws_3d2a7f10",
                    url = "wss://chat.example.com/socket?token=%5BREDACTED%5D",
                    categories = listOf("chat"),
                    startedAt = 1790605337098,
                    lifecycle =
                        WebSocketLifecycle(
                            state = WebSocketState.CLOSED,
                            openedAt = 1790605337243,
                            closedAt = 1790605341020,
                            statusCode = 101,
                            protocol = "http/1.1",
                            closeCode = 1000,
                            closeReason = "bye",
                            closedBy = "app",
                        ),
                    transactionId = "tx_a81c3f09",
                    requestHeaders = emptyMap(),
                    sentCount = 2,
                    sentBytes = 40,
                    receivedCount = 2,
                    receivedBytes = 300016,
                    storedEvents = 9,
                    evictedEvents = 0,
                    droppedEvents = 0,
                ),
            events =
                listOf(
                    WebSocketEvent(1, 1790605337243, WebSocketEventKind.OPEN, statusCode = 101),
                    message(2, 1790605337250, outgoing = true, enqueued = true, text = """{"type":"auth","token":"[REDACTED]"}"""),
                    message(3, 1790605337391, outgoing = false, text = """{"type":"ready"}"""),
                    message(4, 1790605338004, outgoing = true, enqueued = true, bytes = byteArrayOf(-34, -83, -66, -17)),
                    // Cut at the capture cap: the size is the message's, and the data is its first part.
                    message(5, 1790605339112, outgoing = false, text = """{"type":"snapshot","items":[{"id":1,"na""", payloadBytes = 300000),
                    // send() refused this one after close(), so it is not in the export.
                    message(7, 1790605340019, outgoing = true, enqueued = false, text = "too late"),
                    WebSocketEvent(6, 1790605340010, WebSocketEventKind.CLOSE, outgoing = true, enqueued = true, code = 1000, reason = "bye"),
                    WebSocketEvent(8, 1790605341018, WebSocketEventKind.CLOSE, outgoing = false, code = 1000, reason = "bye"),
                    WebSocketEvent(9, 1790605341020, WebSocketEventKind.CLOSED, code = 1000, reason = "bye"),
                ),
        )

    private fun message(
        seq: Long,
        at: Long,
        outgoing: Boolean,
        enqueued: Boolean? = null,
        text: String? = null,
        bytes: ByteArray? = null,
        payloadBytes: Long? = null,
    ): WebSocketEvent =
        WebSocketEvent(
            seq = seq,
            at = at,
            kind = WebSocketEventKind.MESSAGE,
            outgoing = outgoing,
            binary = bytes != null,
            payloadBytes = payloadBytes ?: (text?.length ?: bytes!!.size).toLong(),
            truncated = payloadBytes != null,
            text = text,
            bytes = bytes,
            enqueued = enqueued,
        )

    @Test
    fun `the golden HAR fixture of a WebSocket is what the export writes`() {
        val written = HarExport.write(listOf(handshake), creatorVersion = "0.1.0") { webSocket }
        assertEquals(
            "$GOLDEN_WEBSOCKET differs from:\n$written\n",
            canonical(JSONObject(File(GOLDEN_WEBSOCKET).readText())),
            canonical(JSONObject(written)),
        )
    }

    @Test
    fun `the golden HAR fixture is what the export writes`() {
        val written = HarExport.write(transactions, creatorVersion = "0.1.0")
        assertEquals(
            "$GOLDEN differs from:\n$written\n",
            canonical(JSONObject(File(GOLDEN).readText())),
            canonical(JSONObject(written)),
        )
    }

    // Key order is not part of JSON, so objects compare as sorted maps.
    private fun canonical(value: Any?): Any? =
        when (value) {
            is JSONObject -> value.keys().asSequence().associateWith { canonical(value.get(it)) }.toSortedMap()
            is JSONArray -> (0 until value.length()).map { canonical(value.get(it)) }
            else -> value
        }

    private companion object {
        // Unit tests run in the module directory.
        const val GOLDEN = "../wire-protocol/v1/golden/export-har.json"
        const val GOLDEN_WEBSOCKET = "../wire-protocol/v1/golden/export-har-websocket.json"
    }
}
