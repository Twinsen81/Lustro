@file:Suppress("TooGenericExceptionCaught")

package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.network.NetworkClassifier
import io.github.twinsen81.lustro.network.Redactor
import io.github.twinsen81.lustro.network.WebSocketMessageInfo
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * A [WebSocket.Factory] that creates its sockets with [delegate] and records
 * what they send and receive.
 *
 * OkHttp gives a socket's messages to the [WebSocketListener] and takes them
 * through [WebSocket.send], and nothing else in its public API sees them, so
 * capture wraps both. The app gets the wrapped socket from [newWebSocket] and in
 * every listener call, so a message it sends from a callback is recorded too,
 * and a socket it compares with the one it created is the same object.
 *
 * Capture never changes what the app sees: the delegate's return values come
 * back as they are, what the app's listener throws reaches OkHttp, and a
 * failure inside capture is logged and goes no further.
 */
internal class CapturingWebSocketFactory(
    private val delegate: WebSocket.Factory,
    private val capture: WebSocketCapture,
) : WebSocket.Factory {
    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
        val recorder = capture.newRecorder(request)
        val socket = CapturingWebSocket(request, recorder)
        // The tag reaches the interceptor with the handshake, which links its
        // transaction to this socket and doesn't ask the capture filter again.
        val tagged = request.newBuilder().tag(WebSocketCaptureTag::class.java, WebSocketCaptureTag(recorder)).build()
        val real =
            try {
                delegate.newWebSocket(tagged, CapturingWebSocketListener(listener, socket, recorder))
            } catch (t: Throwable) {
                // OkHttp refuses a request that isn't a GET, for example.
                recorder?.failed(t, null)
                throw t
            }
        socket.attach(real)
        return socket
    }
}

/**
 * On the handshake request of a socket from [CapturingWebSocketFactory]. A null
 * [recorder] means the capture filter left the socket out.
 */
internal class WebSocketCaptureTag(val recorder: WebSocketRecorder?)

internal class CapturingWebSocket(
    private val request: Request,
    private val recorder: WebSocketRecorder?,
) : WebSocket {
    // OkHttp can call the listener before newWebSocket returns the socket, on
    // another thread or on this one, so the listener attaches it too.
    @Volatile
    private var real: WebSocket? = null

    fun attach(real: WebSocket) {
        if (this.real == null) this.real = real
    }

    // The request the app passed, without the tag.
    override fun request(): Request = request

    override fun queueSize(): Long = real?.queueSize() ?: 0L

    override fun send(text: String): Boolean {
        val real = real ?: return false
        val pending = recorder?.beginSend()
        val enqueued =
            try {
                real.send(text)
            } catch (t: Throwable) {
                recorder?.abandon(pending)
                throw t
            }
        recorder?.sent(pending, text, enqueued)
        return enqueued
    }

    override fun send(bytes: ByteString): Boolean {
        val real = real ?: return false
        val pending = recorder?.beginSend()
        val enqueued =
            try {
                real.send(bytes)
            } catch (t: Throwable) {
                recorder?.abandon(pending)
                throw t
            }
        recorder?.sent(pending, bytes, enqueued)
        return enqueued
    }

    override fun close(code: Int, reason: String?): Boolean {
        val real = real ?: return false
        val pending = recorder?.beginClose()
        val enqueued =
            try {
                real.close(code, reason)
            } catch (t: Throwable) {
                // An invalid code or a reason that is too long: no close frame was queued.
                recorder?.abandon(pending)
                throw t
            }
        recorder?.closeCalled(pending, code, reason, enqueued)
        return enqueued
    }

    override fun cancel() {
        recorder?.cancelCalled()
        real?.cancel()
    }
}

internal class CapturingWebSocketListener(
    private val delegate: WebSocketListener,
    private val socket: CapturingWebSocket,
    private val recorder: WebSocketRecorder?,
) : WebSocketListener() {
    override fun onOpen(webSocket: WebSocket, response: Response) {
        socket.attach(webSocket)
        recorder?.opened(response)
        delegate.onOpen(socket, response)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        socket.attach(webSocket)
        recorder?.received(text)
        delegate.onMessage(socket, text)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        socket.attach(webSocket)
        recorder?.received(bytes)
        delegate.onMessage(socket, bytes)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        socket.attach(webSocket)
        recorder?.closing(code, reason)
        delegate.onClosing(socket, code, reason)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        socket.attach(webSocket)
        recorder?.closed(code, reason)
        delegate.onClosed(socket, code, reason)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        socket.attach(webSocket)
        recorder?.failed(t, response)
        delegate.onFailure(socket, t, response)
    }
}

/**
 * What every socket of a Network tab shares: where captures go, and the app's
 * hooks. The hooks are the app's code, so each call to one is guarded.
 */
internal class WebSocketCapture(
    val store: WebSocketTrafficStore,
    private val redactor: Redactor,
    private val classifier: NetworkClassifier,
    private val captureFilter: SafeCaptureFilter,
    val isPaused: () -> Boolean,
    val maxPayloadBytes: () -> Long,
    // Its own thread, so a flood of messages can't make HTTP captures wait.
    val worker: CaptureWorker = CaptureWorker(executor = CaptureWorker.newCaptureExecutor("lustro-ws-capture")),
) {
    private val failureLogged = AtomicBoolean(false)

    /** A recorder for a new socket, or `null` when the capture filter leaves the socket out. Never throws. */
    fun newRecorder(request: Request): WebSocketRecorder? =
        try {
            if (captureFilter.shouldCapture(request.url.toString(), request.method, request.headers.toApiHeaders())) {
                WebSocketRecorder(request, this).also { it.created() }
            } else {
                null
            }
        } catch (t: Throwable) {
            logFailure("Could not start a WebSocket capture, so its socket is not recorded", t)
            null
        }

    /** Waits up to [timeoutMs] until the captures reported so far are stored. For tests. */
    fun awaitCaptures(timeoutMs: Long = 5_000L): Boolean = worker.awaitIdle(timeoutMs)

    // The query is the part of a URL that carries a secret, so without the
    // redactor's answer it isn't stored.
    fun redactUrl(url: String): String =
        try {
            redactor.redactUrl(url)
        } catch (t: Throwable) {
            logCaptureFailure("Could not redact a WebSocket URL; dropped its query", t)
            url.substringBefore('?').substringBefore('#')
        }

    fun redactHeaders(headers: okhttp3.Headers): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (i in 0 until headers.size) {
            val name = headers.name(i)
            val redacted =
                try {
                    redactor.redactHeaderValue(name, headers.value(i))
                } catch (t: Throwable) {
                    logCaptureFailure("Could not redact a WebSocket header; masked it", t)
                    PLACEHOLDER
                }
            val existing = out[name]
            out[name] = if (existing != null) "$existing, $redacted" else redacted
        }
        return out
    }

    fun classify(url: String): List<String> =
        try {
            classifier.classify(url)
        } catch (_: Throwable) {
            emptyList()
        }

    // A payload the redactor fails on is dropped: never stored unredacted.
    // redactBody runs here and not as the default of redactWebSocketText: a
    // redactor that delegates to another one, and overrides only redactBody,
    // would get the other one's default, which calls the other one's redactBody.
    fun redactText(text: String, message: WebSocketMessageInfo): String? =
        try {
            redactor.redactWebSocketText(redactor.redactBody(text, null), message)
        } catch (t: Throwable) {
            logCaptureFailure("Could not redact a WebSocket message; dropped its payload", t)
            null
        }

    fun redactBinary(bytes: ByteArray, message: WebSocketMessageInfo): ByteArray? =
        try {
            redactor.redactWebSocketBinary(bytes, message)
        } catch (t: Throwable) {
            logCaptureFailure("Could not redact a WebSocket message; dropped its payload", t)
            null
        }

    // Capture runs on every message, so only the first failure is logged.
    fun logFailure(message: String, t: Throwable) {
        if (failureLogged.compareAndSet(false, true)) logCaptureFailure("$message; later failures are not logged", t)
    }

    private companion object {
        private const val PLACEHOLDER = "[REDACTED]"
    }
}
