package io.github.twinsen81.lustro.sample

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each WebSocket action ends its run, also when a cancel or a new connect replaces the socket it waits for. */
class WebSocketDemoTest {
    private val log = EventLog()
    private val factory = FakeFactory()
    private val demo = WebSocketDemo(factory, log)

    @Test
    fun `a cancel during a connect ends the connect run`() {
        demo.connect(URL, log.start("connect"))
        demo.cancel(log.start("cancel"))

        assertEquals(Outcome.INFO, outcome("connect"))
        assertEquals(Outcome.OK, outcome("cancel"))
    }

    @Test
    fun `a new connect ends the connect that waits`() {
        demo.connect(URL, log.start("first"))
        demo.connect(URL, log.start("second"))
        factory.open(1)

        assertEquals(Outcome.INFO, outcome("first"))
        assertEquals(Outcome.OK, outcome("second"))
    }

    @Test
    fun `a cancel during a close ends the close run`() {
        demo.connect(URL, log.start("connect"))
        factory.open(0)
        demo.close(log.start("close"))
        assertEquals(Outcome.RUNNING, outcome("close"))

        demo.cancel(log.start("cancel"))

        assertEquals(Outcome.INFO, outcome("close"))
    }

    @Test
    fun `sending 20 messages on a closed socket reports that none went`() {
        demo.connect(URL, log.start("connect"))
        factory.open(0)
        demo.close(log.start("close"))

        demo.sendMany(log.start("many"))

        val deadline = System.currentTimeMillis() + WAIT_MS
        while (outcome("many") == Outcome.RUNNING && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        val event = log.latestByLabel.value.getValue("many")
        assertEquals(Outcome.FAILED, event.outcome)
        assertEquals("WS sent 0 of 20 messages", event.text)
    }

    @Test
    fun `sending without a socket fails at once`() {
        demo.sendMany(log.start("many"))

        assertEquals(Outcome.FAILED, outcome("many"))
        assertTrue(factory.sockets.isEmpty())
    }

    private fun outcome(label: String): Outcome? = log.latestByLabel.value[label]?.outcome

    private class FakeFactory : WebSocket.Factory {
        val sockets = mutableListOf<Pair<FakeSocket, WebSocketListener>>()

        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket = FakeSocket(request).also { sockets += it to listener }

        fun open(index: Int) {
            val (socket, listener) = sockets[index]
            val response = Response.Builder().request(socket.request()).protocol(Protocol.HTTP_1_1).code(SWITCHING).message("Switching Protocols").build()
            listener.onOpen(socket, response)
        }
    }

    /** Accepts messages until it is closed or cancelled, as an OkHttp socket does. */
    private class FakeSocket(private val request: Request) : WebSocket {
        private var accepting = true

        override fun request(): Request = request

        override fun queueSize(): Long = 0

        override fun send(text: String): Boolean = accepting

        override fun send(bytes: ByteString): Boolean = accepting

        override fun close(code: Int, reason: String?): Boolean = accepting.also { accepting = false }

        override fun cancel() {
            accepting = false
        }
    }

    private companion object {
        const val URL = "wss://echo.example/"
        const val SWITCHING = 101
        const val WAIT_MS = 2_000L
        const val POLL_MS = 10L
    }
}
