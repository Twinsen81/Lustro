package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.MediaType
import io.github.twinsen81.lustro.network.DefaultRedactor
import io.github.twinsen81.lustro.network.NoOpNetworkCaptureFilter
import io.github.twinsen81.lustro.network.NoOpNetworkClassifier
import io.github.twinsen81.lustro.network.Redactor
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the capturing factory against a fake socket, where the test decides
 * when each call happens and when the capture thread runs.
 */
class WebSocketRecorderTest {
    /** A socket that goes nowhere. [onSend] runs inside send(), before it returns. */
    private class FakeSocket(private val request: Request, val listener: WebSocketListener) : WebSocket {
        var onSend: (FakeSocket.(String) -> Unit)? = null
        var sendResult = true

        override fun request(): Request = request

        override fun queueSize(): Long = 7L

        override fun send(text: String): Boolean {
            onSend?.invoke(this, text)
            return sendResult
        }

        override fun send(bytes: ByteString): Boolean = sendResult

        override fun close(code: Int, reason: String?): Boolean {
            require(code != BAD_CLOSE_CODE) { "Code must be in range [1000,5000): $code" }
            return true
        }

        override fun cancel() = Unit
    }

    private val executor = ManualExecutor()
    private val store = WebSocketTrafficStore()
    private val sockets = mutableListOf<FakeSocket>()
    private val request = Request.Builder().url("https://example.com/socket").build()

    private fun factory(
        redactor: Redactor = DefaultRedactor,
        maxBacklogChars: Long = 4L * 1024 * 1024,
    ): WebSocket.Factory =
        CapturingWebSocketFactory(
            delegate = { request, listener -> FakeSocket(request, listener).also { sockets += it } },
            capture =
                WebSocketCapture(
                    store = store,
                    redactor = redactor,
                    classifier = NoOpNetworkClassifier,
                    captureFilter = SafeCaptureFilter(NoOpNetworkCaptureFilter),
                    isPaused = { false },
                    maxPayloadBytes = { 256L * 1024 },
                    worker = CaptureWorker(executor, maxBacklogChars),
                ),
        )

    private fun log(): List<String> {
        executor.runAll()
        val connection = store.getConnections().single()
        return store.getEvents(connection.id)!!.events.map { event ->
            listOfNotNull(event.kind.wireName, event.outgoing?.let { if (it) "sent" else "received" }, event.text).joinToString(" ")
        }
    }

    @Test
    fun `a reply that arrives before send returns is logged after the message`() {
        val socket = factory().newWebSocket(request, object : WebSocketListener() {})
        sockets.single().onSend = { text -> listener.onMessage(this, "reply to $text") }

        socket.send("ping")

        assertEquals(listOf("message sent ping", "message received reply to ping"), log())
    }

    // The redactor here overrides only redactBody and delegates the rest, as an app's often does.
    @Test
    fun `a call on the socket's thread leaves redaction to the capture thread, where redactBody covers messages`() {
        var redactions = 0
        val counting =
            object : Redactor by DefaultRedactor {
                override fun redactBody(body: String, contentType: MediaType?): String {
                    redactions++
                    return body
                }
            }
        val socket = factory(counting).newWebSocket(request, object : WebSocketListener() {})

        socket.send("one")
        sockets.single().listener.onMessage(sockets.single(), "two")
        assertEquals(0, redactions)

        executor.runAll()
        assertEquals(2, redactions)
    }

    @Test
    fun `while the capture thread is behind, messages are dropped and counted, and the end is still recorded`() {
        val socket = factory(maxBacklogChars = 3_000).newWebSocket(request, object : WebSocketListener() {})
        val real = sockets.single()
        executor.runAll()

        // The first is queued; the next two would pass the limit while it waits.
        repeat(3) { socket.send("x".repeat(2_000)) }
        real.listener.onClosed(real, 1000, "")

        assertEquals(listOf("message sent " + "x".repeat(2_000), "closed"), log())
        val connection = store.getConnections().single()
        assertEquals(2, connection.droppedEvents)
        assertEquals(WebSocketState.CLOSED, connection.lifecycle.state)
    }

    @Test
    fun `the socket returns what the delegate returns`() {
        val appRequest = request
        val socket = factory().newWebSocket(appRequest, object : WebSocketListener() {})
        val real = sockets.single()

        assertSame(appRequest, socket.request())
        assertEquals(7L, socket.queueSize())
        real.sendResult = false
        assertFalse(socket.send("refused"))
        assertFalse(socket.send(ByteString.EMPTY))
        assertTrue(socket.close(1000, null))
    }

    @Test
    fun `a close that throws leaves no event and doesn't hold later events back`() {
        val socket = factory().newWebSocket(request, object : WebSocketListener() {})

        val thrown = runCatching { socket.close(BAD_CLOSE_CODE, null) }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
        socket.send("after")

        assertEquals(listOf("message sent after"), log())
    }

    @Test
    fun `the listener gets the socket the factory returned, in every call`() {
        val seen = mutableListOf<WebSocket>()
        val listener =
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = Unit.also { seen += webSocket }

                override fun onMessage(webSocket: WebSocket, text: String) = Unit.also { seen += webSocket }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit.also { seen += webSocket }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = Unit.also { seen += webSocket }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = Unit.also { seen += webSocket }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = Unit.also { seen += webSocket }
            }
        val socket = factory().newWebSocket(request, listener)
        val real = sockets.single()

        real.listener.onMessage(real, "text")
        real.listener.onMessage(real, ByteString.EMPTY)
        real.listener.onClosing(real, 1000, "")
        real.listener.onClosed(real, 1000, "")
        real.listener.onFailure(real, IllegalStateException("late"), null)

        assertEquals(5, seen.size)
        assertTrue(seen.all { it === socket })
    }

    @Test
    fun `the prefix that fits a byte limit never ends inside a character`() {
        assertEquals(3, utf8PrefixLength("abcdef", 3))
        assertEquals(6, utf8PrefixLength("abcdef", 100))
        // é is 2 bytes, 日 is 3, and 😀 is 4 bytes in 2 chars.
        assertEquals(1, utf8PrefixLength("éé", 3))
        assertEquals(1, utf8PrefixLength("日日", 5))
        assertEquals(0, utf8PrefixLength("😀", 3))
        assertEquals(2, utf8PrefixLength("😀😀", 7))
        assertEquals(0, utf8PrefixLength("abc", 0))
    }

    private companion object {
        private const val BAD_CLOSE_CODE = 999
    }
}
