package io.github.twinsen81.lustro.network

import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.internal.network.NetworkTrafficStore
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for WebSocket capture through the Network tab: a real OkHttp socket
 * from the capturing factory against a MockWebServer that echoes, read back
 * through the tab's `websockets` routes.
 */
class NetworkWebSocketCaptureTest {
    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    @After
    fun tearDown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    /** The app's listener: keeps each call, with the socket it came with. */
    private class RecordingListener : WebSocketListener() {
        val calls = LinkedBlockingQueue<String>()
        val sockets = LinkedBlockingQueue<WebSocket>()

        override fun onOpen(webSocket: WebSocket, response: Response) = record(webSocket, "open ${response.code}")

        override fun onMessage(webSocket: WebSocket, text: String) = record(webSocket, "text $text")

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = record(webSocket, "binary ${bytes.hex()}")

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = record(webSocket, "closing $code $reason")

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = record(webSocket, "closed $code $reason")

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
            record(webSocket, "failure ${response?.code}")

        private fun record(webSocket: WebSocket, call: String) {
            sockets.put(webSocket)
            calls.put(call)
        }

        fun next(): String = calls.poll(5, TimeUnit.SECONDS) ?: error("no listener call within 5 s")
    }

    private fun echoServer() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        webSocket.send(text)
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        webSocket.send(bytes)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                },
            ),
        )
    }

    private fun request(path: String = "/socket?token=abc"): Request = Request.Builder().url(server.url(path)).build()

    private fun get(path: String, vararg query: Pair<String, String>): DebugRequest =
        DebugRequest(path = path, method = "GET", queryParams = query.associate { (k, v) -> k to listOf(v) })

    private fun DebugResponse?.json(): JSONObject = JSONObject(this!!.body.toString(Charsets.UTF_8))

    private fun NetworkDebugTab.await() {
        assertTrue((captureSink as NetworkTrafficStore).awaitCaptures())
        assertTrue(webSocketCapture.awaitCaptures())
    }

    private fun NetworkDebugTab.connections(): JSONArray {
        await()
        return handle(get("websockets")).json().getJSONArray("items")
    }

    private fun NetworkDebugTab.events(id: String, vararg query: Pair<String, String>): JSONObject {
        await()
        return handle(get("websockets/$id/events", *query)).json()
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun JSONObject.summary(): String =
        buildString {
            append(getString("kind"))
            if (has("direction")) append(' ').append(getString("direction"))
            if (has("type")) append(' ').append(getString("type"))
            if (has("preview")) append(' ').append(getString("preview"))
            if (has("hexPreview")) append(' ').append(getString("hexPreview"))
            if (has("code")) append(' ').append(get("code"))
        }

    @Test(timeout = 20_000)
    fun `records the messages of both directions and the lifecycle, in order`() {
        echoServer()
        val tab = NetworkDebugTab.create()
        val listener = RecordingListener()
        val appRequest = request()

        val socket = tab.wrapWebSocketFactory(client).newWebSocket(appRequest, listener)
        assertEquals("open 101", listener.next())
        assertTrue(socket.send("hello"))
        assertEquals("text hello", listener.next())
        assertTrue(socket.send(byteArrayOf(1, 2, 0x7f).toByteString()))
        assertEquals("binary 01027f", listener.next())
        assertTrue(socket.close(1000, "done"))
        assertEquals("closing 1000 done", listener.next())
        assertEquals("closed 1000 done", listener.next())

        // The app sees one socket: the one it created, with the request it passed.
        assertTrue(listener.sockets.all { it === socket })
        assertSame(appRequest, socket.request())

        val connection = tab.connections().objects().single()
        assertEquals("closed", connection.getString("state"))
        assertEquals("app", connection.getString("closedBy"))
        assertEquals(1000, connection.getInt("closeCode"))
        assertEquals(101, connection.getInt("statusCode"))
        // OkHttp holds a ws: URL as http:; the console shows what the app asked for, redacted.
        assertEquals("ws://${server.hostName}:${server.port}/socket?token=%5BREDACTED%5D", connection.getString("url"))
        assertEquals(2, connection.getInt("sentCount"))
        assertEquals(8, connection.getInt("sentBytes"))
        assertEquals(2, connection.getInt("receivedCount"))

        val log = tab.events(connection.getString("id"))
        assertEquals("reset", log.getString("status"))
        assertEquals(
            listOf(
                "open",
                "message sent text hello",
                "message received text hello",
                "message sent binary 01027f",
                "message received binary 01027f",
                "close sent 1000",
                "close received 1000",
                "closed 1000",
            ),
            log.getJSONArray("items").objects().map { it.summary() },
        )
        val sent = log.getJSONArray("items").getJSONObject(1)
        assertTrue(sent.getBoolean("enqueued"))
        assertEquals(5, sent.getInt("payloadBytes"))
        assertTrue(sent.getBoolean("previewComplete"))
    }

    @Test(timeout = 20_000)
    fun `the events route sends only what is new after a cursor`() {
        echoServer()
        val tab = NetworkDebugTab.create()
        val listener = RecordingListener()
        val socket = tab.wrapWebSocketFactory(client).newWebSocket(request(), listener)
        assertEquals("open 101", listener.next())
        socket.send("one")
        assertEquals("text one", listener.next())
        val id = tab.connections().getJSONObject(0).getString("id")

        val first = tab.events(id)
        assertEquals(3, first.getJSONArray("items").length())
        val unchanged = tab.events(id, "cursor" to first.getString("cursor"))
        assertEquals("unchanged", unchanged.getString("status"))
        assertFalse(unchanged.has("items"))

        socket.send("two")
        assertEquals("text two", listener.next())
        val delta = tab.events(id, "cursor" to first.getString("cursor"))
        assertEquals("delta", delta.getString("status"))
        assertEquals(listOf("message sent text two", "message received text two"), delta.getJSONArray("items").objects().map { it.summary() })
        assertEquals(0, delta.getInt("dropped"))

        val received = tab.events(id, "direction" to "received")
        assertEquals(listOf("message received text one", "message received text two"), received.getJSONArray("items").objects().map { it.summary() })
        // A cursor of another log, or of none, gets the log again.
        assertEquals("reset", tab.events(id, "cursor" to "bogus").getString("status"))
        socket.cancel()
    }

    @Test(timeout = 20_000)
    fun `the events route searches the stored text, and says what is wrong with a parameter`() {
        echoServer()
        val tab = NetworkDebugTab.create()
        val listener = RecordingListener()
        val socket = tab.wrapWebSocketFactory(client).newWebSocket(request(), listener)
        assertEquals("open 101", listener.next())
        socket.send("Order SHIPPED")
        listener.next()
        socket.send("order paid")
        listener.next()
        val id = tab.connections().getJSONObject(0).getString("id")

        // Case is ignored, and an event with no text, such as the open, never matches.
        val found = tab.events(id, "search" to "shipped", "direction" to "sent")
        assertEquals(listOf("message sent text Order SHIPPED"), found.getJSONArray("items").objects().map { it.summary() })
        // A first poll gets the last events only, and its cursor is the end of the log.
        val last = tab.events(id, "limit" to "1")
        assertEquals(listOf("message received text order paid"), last.getJSONArray("items").objects().map { it.summary() })
        assertEquals("unchanged", tab.events(id, "cursor" to last.getString("cursor")).getString("status"))

        for ((name, value) in listOf("limit" to "0", "limit" to "many", "direction" to "up")) {
            val error = tab.handle(get("websockets/$id/events", name to value))!!
            assertEquals(400, error.status)
            assertEquals(name, JSONObject(error.body.toString(Charsets.UTF_8)).getString("field"))
        }
        assertEquals(404, tab.handle(get("websockets/unknown/events"))!!.status)
        assertEquals(404, tab.handle(get("websockets/unknown"))!!.status)
        assertNull(tab.handle(DebugRequest(path = "websockets", method = "POST")))
        socket.cancel()
    }

    @Test(timeout = 20_000)
    fun `a text message is redacted, and its payload route serves what is stored`() {
        echoServer()
        val tab = NetworkDebugTab.create()
        val listener = RecordingListener()
        val socket = tab.wrapWebSocketFactory(client).newWebSocket(request(), listener)
        assertEquals("open 101", listener.next())
        socket.send("""{"type":"auth","token":"s3cret"}""")
        // The app's own message is untouched.
        assertEquals("""text {"type":"auth","token":"s3cret"}""", listener.next())

        val id = tab.connections().getJSONObject(0).getString("id")
        val items = tab.events(id).getJSONArray("items").objects()
        assertEquals("""{"type":"auth","token":"[REDACTED]"}""", items[1].getString("preview"))
        assertEquals("""{"type":"auth","token":"[REDACTED]"}""", items[2].getString("preview"))
        // The size is the message's own, not the stored text's.
        assertEquals(32, items[1].getInt("payloadBytes"))

        val payload = tab.handle(get("websockets/$id/events/${items[1].getInt("seq")}/payload"))!!
        assertEquals("""{"type":"auth","token":"[REDACTED]"}""", payload.body.toString(Charsets.UTF_8))
        assertEquals("attachment", payload.headers.get("Content-Disposition"))
        socket.cancel()
    }

    @Test(timeout = 20_000)
    fun `a redactor can drop a payload, and one that throws drops it too`() {
        echoServer()
        val redactor =
            object : Redactor by DefaultRedactor {
                override fun redactWebSocketText(text: String, message: WebSocketMessageInfo): String? =
                    if (message.isOutgoing) null else error("boom on ${message.url}")

                override fun redactWebSocketBinary(bytes: ByteArray, message: WebSocketMessageInfo): ByteArray? =
                    bytes.also { it.fill(0) }
            }
        val tab = NetworkDebugTab.create(redactor = redactor)
        val listener = RecordingListener()
        val socket = tab.wrapWebSocketFactory(client).newWebSocket(request(), listener)
        assertEquals("open 101", listener.next())
        socket.send("secret")
        assertEquals("text secret", listener.next())
        val bytes = byteArrayOf(9, 9).toByteString()
        socket.send(bytes)
        // A redactor that changes the array changes the stored copy only.
        assertEquals("binary 0909", listener.next())

        val id = tab.connections().getJSONObject(0).getString("id")
        val items = tab.events(id).getJSONArray("items").objects()
        assertFalse(items[1].getBoolean("stored"))
        assertFalse(items[1].has("preview"))
        assertEquals(6, items[1].getInt("payloadBytes"))
        assertFalse(items[2].getBoolean("stored"))
        assertEquals("0000", items[3].getString("hexPreview"))
        assertEquals(404, tab.handle(get("websockets/$id/events/${items[1].getInt("seq")}/payload"))!!.status)
        socket.cancel()
    }

    @Test(timeout = 20_000)
    fun `a message over the capture cap keeps its first part and its whole size`() {
        echoServer()
        val tab = NetworkDebugTab.create()
        tab.applyConfig(
            maxCaptureTransactions = 1000,
            maxBodyCaptureBytes = 10,
            appServerBaseUrl = null,
            captureBudgetBytes = 50L * 1024 * 1024,
            requestTimeoutMs = 30_000,
            maxCaptureWebSockets = 100,
            maxWebSocketEvents = 1000,
            webSocketCaptureBudgetBytes = 16L * 1024 * 1024,
        )
        val listener = RecordingListener()
        val socket = tab.wrapWebSocketFactory(client).newWebSocket(request(), listener)
        assertEquals("open 101", listener.next())
        // 3 bytes each in UTF-8: the cap of 10 bytes keeps 3 of them, never a part of one.
        socket.send("日本語日本語")
        listener.next()

        val id = tab.connections().getJSONObject(0).getString("id")
        val sent = tab.events(id).getJSONArray("items").getJSONObject(1)
        assertEquals("日本語", sent.getString("preview"))
        assertTrue(sent.getBoolean("truncated"))
        assertEquals(18, sent.getInt("payloadBytes"))
        socket.cancel()
    }

    @Test(timeout = 20_000)
    fun `a refused upgrade is a failed connection with the response status`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("no"))
        val tab = NetworkDebugTab.create()
        val listener = RecordingListener()
        tab.wrapWebSocketFactory(client).newWebSocket(request(), listener)
        assertEquals("failure 403", listener.next())

        val connection = tab.connections().objects().single()
        assertEquals("failed", connection.getString("state"))
        assertEquals(403, connection.getInt("statusCode"))
        assertTrue(connection.getString("error").contains("Expected HTTP 101 response but was '403"))
        val items = tab.events(connection.getString("id")).getJSONArray("items").objects()
        assertEquals(listOf("failure"), items.map { it.summary() })
        // The headers of the response that refused it say why, such as WWW-Authenticate.
        val detail = tab.handle(get("websockets/${connection.getString("id")}")).json()
        assertEquals("2", detail.getJSONObject("responseHeaders").getString("Content-Length"))
        assertEquals("http/1.1", detail.getString("protocol"))
    }

    @Test(timeout = 20_000)
    fun `the transactions cursor moves when a connection is evicted or listed again`() {
        echoServer()
        echoServer()
        val tab = NetworkDebugTab.create()
        tab.applyConfig(
            maxCaptureTransactions = 1000,
            maxBodyCaptureBytes = 256L * 1024,
            appServerBaseUrl = null,
            captureBudgetBytes = 50L * 1024 * 1024,
            requestTimeoutMs = 30_000,
            maxCaptureWebSockets = 1,
            maxWebSocketEvents = 1000,
            webSocketCaptureBudgetBytes = 16L * 1024 * 1024,
        )
        val sockets = tab.wrapWebSocketFactory(client.newBuilder().addInterceptor(tab.createInterceptor { true }).build())
        val firstListener = RecordingListener()
        val first = sockets.newWebSocket(request("/first"), firstListener)
        assertEquals("open 101", firstListener.next())
        val secondListener = RecordingListener()
        val second = sockets.newWebSocket(request("/second"), secondListener)
        assertEquals("open 101", secondListener.next())
        tab.await()

        fun links(poll: JSONObject) =
            poll.getJSONArray("items").objects().associate { it.getString("url").substringAfterLast('/') to !it.isNull("webSocketId") }
        // The second socket took the only place, so the first one's handshake has no connection.
        val before = tab.handle(get("transactions")).json()
        assertEquals(mapOf("first" to false, "second" to true), links(before))

        // A message lists the first socket again, in the place of the second. No request changed.
        first.send("hello")
        assertEquals("text hello", firstListener.next())
        tab.await()
        val after = tab.handle(get("transactions", "cursor" to before.getString("cursor"))).json()
        assertEquals("delta", after.getString("status"))
        assertEquals(mapOf("first" to true, "second" to false), links(after))
        first.cancel()
        second.cancel()
    }

    @Test(timeout = 20_000)
    fun `the handshake transaction and the connection link to each other, and the filter is asked once`() {
        echoServer()
        var asked = 0
        val tab =
            NetworkDebugTab.create(
                captureFilter = NetworkCaptureFilter.of("counts") {
                    asked++
                    true
                },
            )
        val withInterceptor = client.newBuilder().addInterceptor(tab.createInterceptor { true }).build()
        val listener = RecordingListener()
        val socket = tab.wrapWebSocketFactory(withInterceptor).newWebSocket(request(), listener)
        assertEquals("open 101", listener.next())

        val connection = tab.connections().objects().single()
        val transaction = tab.handle(get("transactions")).json().getJSONArray("items").objects().single()
        assertEquals(101, transaction.getInt("statusCode"))
        assertEquals(connection.getString("id"), transaction.getString("webSocketId"))
        assertEquals(transaction.getString("id"), connection.getString("transactionId"))
        assertEquals(1, asked)

        // The export of the handshake carries the socket's messages, as Chrome DevTools reads them.
        socket.send("hello")
        assertEquals("text hello", listener.next())
        tab.await()
        val entry = tab.handle(get("transactions/_/export")).json().getJSONObject("log").getJSONArray("entries").getJSONObject(0)
        assertEquals("websocket", entry.getString("_resourceType"))
        assertEquals(listOf("send hello", "receive hello"), entry.getJSONArray("_webSocketMessages").objects().map { "${it.getString("type")} ${it.getString("data")}" })
        assertEquals(connection.getString("id"), entry.getJSONObject("_lustro").getJSONObject("webSocket").getString("id"))
        socket.cancel()
    }

    @Test(timeout = 20_000)
    fun `the golden fixtures have the keys that the routes write`() {
        echoServer()
        val tab = NetworkDebugTab.create()
        val withInterceptor = client.newBuilder().addInterceptor(tab.createInterceptor { true }).build()
        val listener = RecordingListener()
        val socket = tab.wrapWebSocketFactory(withInterceptor).newWebSocket(request(), listener)
        assertEquals("open 101", listener.next())
        socket.send("hello")
        listener.next()
        socket.send(byteArrayOf(1).toByteString())
        listener.next()
        socket.close(1000, "bye")
        listener.next()
        listener.next()
        val refused = socket.send("too late")
        assertFalse(refused)

        fun golden(name: String) = JSONObject(java.io.File("../wire-protocol/v1/golden/$name").readText())

        fun JSONObject.keySet(): Set<String> = keys().asSequence().toSet()
        val listed = tab.connections().getJSONObject(0)
        assertEquals(golden("websockets-reset.json").getJSONArray("items").getJSONObject(0).keySet(), listed.keySet())
        assertEquals(golden("websocket.json").keySet(), tab.handle(get("websockets/${listed.getString("id")}")).json().keySet())
        assertEquals(
            golden("cursor-reset.json").getJSONArray("items").getJSONObject(0).keySet(),
            tab.handle(get("transactions")).json().getJSONArray("items").getJSONObject(0).keySet(),
        )

        // Each kind of event, by what tells one kind from another.
        fun JSONObject.shape() = listOf("kind", "direction", "type").mapNotNull { key -> optString(key).takeIf { it.isNotEmpty() } }
            .plus(if (optBoolean("enqueued", true)) emptyList() else listOf("refused"))
        val written = tab.events(listed.getString("id")).getJSONArray("items").objects().associateBy({ it.shape() }, { it.keySet() })
        val fixtures = golden("stream-reset.json").getJSONArray("items").objects() + golden("stream-delta.json").getJSONArray("items").objects()
        for (fixture in fixtures) {
            if (fixture.optBoolean("truncated")) continue
            assertEquals("${fixture.shape()}", fixture.keySet(), written[fixture.shape()])
        }
    }

    @Test(timeout = 20_000)
    fun `a socket the capture filter skips is not recorded, and neither is its handshake`() {
        echoServer()
        val tab = NetworkDebugTab.create(captureFilter = NetworkCaptureFilter.of("No sockets") { !it.url.contains("/socket") })
        val withInterceptor = client.newBuilder().addInterceptor(tab.createInterceptor { true }).build()
        val listener = RecordingListener()
        val socket = tab.wrapWebSocketFactory(withInterceptor).newWebSocket(request(), listener)
        assertEquals("open 101", listener.next())
        socket.send("hello")
        assertEquals("text hello", listener.next())
        assertTrue(listener.sockets.all { it === socket })

        assertEquals(0, tab.connections().length())
        val poll = tab.handle(get("transactions")).json()
        assertEquals(0, poll.getJSONArray("items").length())
        assertEquals(1, poll.getJSONObject("state").getJSONObject("captureFilter").getInt("skipped"))
        socket.cancel()
    }

    @Test(timeout = 20_000)
    fun `while capture is paused no message is recorded, and a close still is`() {
        echoServer()
        val tab = NetworkDebugTab.create()
        val listener = RecordingListener()
        val socket = tab.wrapWebSocketFactory(client).newWebSocket(request(), listener)
        assertEquals("open 101", listener.next())
        tab.handle(DebugRequest(path = "pause", method = "POST"))
        socket.send("while paused")
        assertEquals("text while paused", listener.next())
        socket.close(1000, null)
        assertEquals("closing 1000 ", listener.next())
        assertEquals("closed 1000 ", listener.next())
        socket.cancel()

        val connection = tab.connections().objects().single()
        assertEquals("closed", connection.getString("state"))
        // cancel() after the end does nothing in OkHttp, and nothing here.
        assertFalse(connection.getBoolean("canceled"))
        assertEquals(0, connection.getInt("sentCount"))
        val items = tab.events(connection.getString("id")).getJSONArray("items").objects()
        assertEquals(listOf("open", "close sent 1000", "close received 1000", "closed 1000"), items.map { it.summary() })
    }

    @Test(timeout = 20_000)
    fun `a socket opened while paused, or cleared, is listed again with its next message`() {
        echoServer()
        val tab = NetworkDebugTab.create()
        tab.handle(DebugRequest(path = "pause", method = "POST"))
        val listener = RecordingListener()
        val socket = tab.wrapWebSocketFactory(client).newWebSocket(request(), listener)
        assertEquals("open 101", listener.next())
        assertEquals(0, tab.connections().length())

        tab.handle(DebugRequest(path = "pause", method = "POST"))
        socket.send("after the pause")
        listener.next()
        val listed = tab.connections().objects().single()
        assertEquals("open", listed.getString("state"))
        assertEquals(101, listed.getInt("statusCode"))
        assertFalse(listed.isNull("openedAt"))

        val cursor = tab.events(listed.getString("id")).getString("cursor")
        tab.handle(DebugRequest(path = "clear", method = "POST"))
        assertEquals(0, tab.connections().length())
        socket.send("after the clear")
        listener.next()
        val again = tab.connections().objects().single()
        assertEquals(listed.getString("id"), again.getString("id"))
        assertEquals(1, again.getInt("sentCount"))
        // It is a new log: a client with a cursor from before the clear replaces what it has.
        val log = tab.events(again.getString("id"), "cursor" to cursor)
        assertEquals("reset", log.getString("status"))
        assertEquals(
            listOf("message sent text after the clear", "message received text after the clear"),
            log.getJSONArray("items").objects().map { it.summary() },
        )
        socket.cancel()
    }

    @Test(timeout = 20_000)
    fun `a log past its limit drops its oldest events and says how many`() {
        echoServer()
        val tab = NetworkDebugTab.create()
        tab.applyConfig(
            maxCaptureTransactions = 1000,
            maxBodyCaptureBytes = 256L * 1024,
            appServerBaseUrl = null,
            captureBudgetBytes = 50L * 1024 * 1024,
            requestTimeoutMs = 30_000,
            maxCaptureWebSockets = 100,
            maxWebSocketEvents = 4,
            webSocketCaptureBudgetBytes = 16L * 1024 * 1024,
        )
        val listener = RecordingListener()
        val socket = tab.wrapWebSocketFactory(client).newWebSocket(request(), listener)
        assertEquals("open 101", listener.next())
        val id = tab.connections().getJSONObject(0).getString("id")
        val cursor = tab.events(id).getString("cursor")
        repeat(5) {
            socket.send("m$it")
            listener.next()
        }

        val connection = tab.connections().objects().single()
        assertEquals(4, connection.getInt("storedEvents"))
        assertEquals(7, connection.getInt("evictedEvents"))
        // The counts are of every message, also those the log no longer has.
        assertEquals(5, connection.getInt("sentCount"))
        val delta = tab.events(id, "cursor" to cursor)
        assertEquals(6, delta.getInt("dropped"))
        assertEquals(
            listOf("message sent text m3", "message received text m3", "message sent text m4", "message received text m4"),
            delta.getJSONArray("items").objects().map { it.summary() },
        )
        socket.cancel()
    }

    @Test(timeout = 20_000)
    fun `what the app's listener throws reaches OkHttp, which fails the socket`() {
        echoServer()
        val tab = NetworkDebugTab.create()
        val failures = LinkedBlockingQueue<String>()
        val listener =
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String): Unit = throw IllegalStateException("app bug")

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    failures.put(t.toString())
                }
            }
        val socket = tab.wrapWebSocketFactory(client).newWebSocket(request(), listener)
        socket.send("hello")
        assertEquals("java.lang.IllegalStateException: app bug", failures.poll(5, TimeUnit.SECONDS))

        val connection = tab.connections().objects().single()
        assertEquals("failed", connection.getString("state"))
        assertEquals("java.lang.IllegalStateException: app bug", connection.getString("error"))
    }

    @Test
    fun `wrapping a capturing factory again returns it`() {
        val tab = NetworkDebugTab.create()
        val factory = tab.wrapWebSocketFactory(client)
        assertSame(factory, tab.wrapWebSocketFactory(factory))
    }

    @Test(timeout = 20_000)
    fun `a request OkHttp refuses throws as it does without capture, and is listed as failed`() {
        val tab = NetworkDebugTab.create()
        val post = Request.Builder().url(server.url("/socket")).post(ByteArray(0).toRequestBody()).build()
        val thrown = runCatching { tab.wrapWebSocketFactory(client).newWebSocket(post, RecordingListener()) }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)

        val connection = tab.connections().objects().single()
        assertEquals("failed", connection.getString("state"))
        assertTrue(connection.isNull("openedAt"))
    }
}
