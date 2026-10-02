package io.github.twinsen81.lustro.sample

import java.util.concurrent.atomic.AtomicInteger
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
 * Callbacks arrive on OkHttp's threads.
 */
internal class WebSocketDemo(
    private val sockets: WebSocket.Factory,
    private val onStatus: (String) -> Unit,
) {
    @Volatile
    private var socket: WebSocket? = null
    private val sent = AtomicInteger()

    private val listener =
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket === socket) onStatus("✓ WS open (${response.code})")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                onStatus("WS ⇠ ${text.take(STATUS_CHARS)}" + if (text.length > STATUS_CHARS) "… (${text.length} chars)" else "")
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onStatus("WS ⇠ ${bytes.size} bytes")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // The peer closed first: answer, or OkHttp keeps the socket half open.
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onStatus("✓ WS closed ($code${if (reason.isEmpty()) "" else " $reason"})")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onStatus("✗ WS ${response?.code ?: ""}\n${t.message}")
            }
        }

    fun connect(url: String) {
        socket?.cancel()
        onStatus("→ WS $url")
        socket = sockets.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    fun sendText() = send { it.send("hello ${sent.incrementAndGet()}") }

    // The default redactor masks the token before the console stores the message.
    fun sendJsonWithToken() = send { it.send("""{"type":"auth","token":"not-a-real-one","user":"ada"}""") }

    fun sendBinary() = send { it.send(ByteArray(BINARY_BYTES) { index -> index.toByte() }.toByteString()) }

    // Past the default capture cap of 256 KiB, so the console keeps its first part.
    fun sendLargeText() = send { it.send("0123456789".repeat(LARGE_TEXT_CHARS / 10)) }

    // One message at a time, so the console's log grows while you watch it. The
    // echo server also closes the connection when many messages arrive at once.
    fun sendMany() =
        send { socket ->
            thread(isDaemon = true, name = "websocket-demo") {
                repeat(MANY_MESSAGES) {
                    if (!socket.send("""{"n":${sent.incrementAndGet()}}""")) return@thread
                    Thread.sleep(SEND_INTERVAL_MS)
                }
            }
            true
        }

    fun close() {
        val closing = socket?.close(NORMAL_CLOSURE, "done") ?: false
        if (!closing) onStatus("No open WebSocket")
    }

    fun cancel() {
        socket?.cancel() ?: onStatus("No WebSocket")
    }

    private fun send(action: (WebSocket) -> Boolean) {
        val socket = socket
        when {
            socket == null -> onStatus("Connect the WebSocket first")
            !action(socket) -> onStatus("✗ WS send refused: the socket is closing or closed")
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
