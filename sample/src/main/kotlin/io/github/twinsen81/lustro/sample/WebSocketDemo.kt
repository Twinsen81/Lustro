package io.github.twinsen81.lustro.sample

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * Reference use of an OkHttp WebSocket: one connection at a time, to a server
 * that echoes each message.
 *
 * The sockets come from a [WebSocket.Factory], not from the `OkHttpClient`
 * directly. That is the one thing message capture needs: in the debug variant
 * the factory is the one Lustro wraps, so the debug console shows each message,
 * and in the release variant it is the client itself.
 *
 * Callbacks arrive on OkHttp's threads. Each action reports to the [Feedback]
 * of its run; messages from the server go to [EventLog] as runs of their own.
 */
internal class WebSocketDemo(
    private val sockets: WebSocket.Factory,
    private val events: EventLog,
) {
    @Volatile
    private var socket: WebSocket? = null

    // The runs that wait for the socket: a connect waits for onOpen, a close for
    // onClosed. Whoever ends a run takes it out first, so each run ends once.
    private val connecting = AtomicReference<Feedback?>()
    private val closing = AtomicReference<Feedback?>()
    private val sent = AtomicInteger()

    private val listener =
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket !== socket) return
                (connecting.getAndSet(null) ?: events.start(null)).ok("WS open (${response.code})")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val more = if (text.length > STATUS_CHARS) "… (${text.length} chars)" else ""
                events.start(null).info("WS ⇠ ${text.take(STATUS_CHARS)}$more")
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                events.start(null).info("WS ⇠ ${bytes.size} bytes")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // The peer closed first: answer, or OkHttp keeps the socket half open.
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                (closing.getAndSet(null) ?: events.start(null)).ok("WS closed ($code${if (reason.isEmpty()) "" else " $reason"})")
            }

            // A socket that connect or cancel replaced fails too; that is expected, not news.
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== socket) return
                val what = if (response != null) "WS upgrade refused (${response.code})" else "WS failed"
                val pending = listOfNotNull(connecting.getAndSet(null), closing.getAndSet(null))
                pending.ifEmpty { listOf(events.start(null)) }.forEach { it.failed(what, t.message) }
            }
        }

    fun connect(url: String, feedback: Feedback) {
        val previous = socket
        endPending("replaced by a new connect")
        connecting.set(feedback)
        feedback.running("WS connect ${url.removePrefix("wss://").trimEnd('/')}")
        socket = sockets.newWebSocket(Request.Builder().url(url).build(), listener)
        previous?.cancel()
    }

    fun sendText(feedback: Feedback) = send(feedback, "WS sent a text message") { it.send("hello ${sent.incrementAndGet()}") }

    // The default redactor masks the token before the console stores the message.
    fun sendJsonWithToken(feedback: Feedback) =
        send(feedback, "WS sent JSON with a token") { it.send("""{"type":"auth","token":"not-a-real-one","user":"ada"}""") }

    fun sendBinary(feedback: Feedback) =
        send(feedback, "WS sent $BINARY_BYTES bytes") { it.send(ByteArray(BINARY_BYTES) { index -> index.toByte() }.toByteString()) }

    // Past the default capture cap of 256 KiB, so the console keeps its first part.
    fun sendLargeText(feedback: Feedback) = send(feedback, "WS sent a 300 KB text") { it.send("0123456789".repeat(LARGE_TEXT_CHARS / 10)) }

    // One message at a time, so the console's log grows while you watch it. The
    // echo server also closes the connection when many messages arrive at once.
    // The run ends when the worker does, with the number the socket accepted.
    fun sendMany(feedback: Feedback) {
        val socket = socket ?: return feedback.failed("Connect the WebSocket first")
        feedback.running("WS sending $MANY_MESSAGES messages")
        thread(isDaemon = true, name = "websocket-demo") {
            var accepted = 0
            while (accepted < MANY_MESSAGES && socket.send("""{"n":${sent.incrementAndGet()}}""")) {
                accepted++
                if (accepted < MANY_MESSAGES) Thread.sleep(SEND_INTERVAL_MS)
            }
            if (accepted == MANY_MESSAGES) {
                feedback.ok("WS sent $MANY_MESSAGES messages")
            } else {
                feedback.failed("WS sent $accepted of $MANY_MESSAGES messages", "The socket is closing or closed")
            }
        }
    }

    fun close(feedback: Feedback) {
        if (socket?.close(NORMAL_CLOSURE, "done") == true) {
            closing.getAndSet(feedback)?.info("WS close repeated")
            feedback.running("WS closing")
        } else {
            feedback.failed("No open WebSocket")
        }
    }

    fun cancel(feedback: Feedback) {
        val current = socket ?: return feedback.failed("No WebSocket")
        socket = null
        endPending("cancelled")
        current.cancel()
        feedback.ok("WS cancelled")
    }

    // The callbacks of a socket that is cancelled or replaced are ignored, so the
    // runs that wait for it end here; otherwise their spinners would stay.
    private fun endPending(why: String) {
        connecting.getAndSet(null)?.info("WS connect $why")
        closing.getAndSet(null)?.info("WS close $why")
    }

    private fun send(feedback: Feedback, done: String, action: (WebSocket) -> Boolean) {
        val socket = socket
        when {
            socket == null -> feedback.failed("Connect the WebSocket first")
            !action(socket) -> feedback.failed("WS send refused", "The socket is closing or closed")
            else -> feedback.ok(done)
        }
    }

    private companion object {
        private const val NORMAL_CLOSURE = 1000
        private const val STATUS_CHARS = 80
        private const val BINARY_BYTES = 48
        private const val LARGE_TEXT_CHARS = 300_000
        private const val MANY_MESSAGES = 20
        private const val SEND_INTERVAL_MS = 150L
    }
}
