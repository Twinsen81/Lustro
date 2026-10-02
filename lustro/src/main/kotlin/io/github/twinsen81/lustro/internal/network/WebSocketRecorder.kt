@file:Suppress("TooGenericExceptionCaught")

package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.network.WebSocketMessageInfo
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Request
import okhttp3.Response
import okio.ByteString
import okio.utf8Size

/**
 * Records one socket. The socket's threads call it: OkHttp's reader thread for
 * what arrives, and the app's threads for what it sends. Each call only puts a
 * task in a queue; cutting a payload at the cap, redacting it, and storing it
 * run on the capture thread. So a call costs the same for a small message and a
 * large one, and no call throws.
 *
 * While capture is paused, messages aren't recorded. Lifecycle events still are,
 * so a listed connection never shows as open after it closed.
 */
internal class WebSocketRecorder(
    request: Request,
    private val capture: WebSocketCapture,
) : WebSocketSource {
    override val id: String = UUID.randomUUID().toString()
    override val startedAt: Long = System.currentTimeMillis()

    // Changed on the capture thread only, one event at a time.
    @Volatile
    override var lifecycle: WebSocketLifecycle = WebSocketLifecycle()
        private set

    @Volatile
    override var transactionId: String? = null
        private set

    private val dropped = AtomicLong()
    override val droppedEvents: Long
        get() = dropped.get()

    // Set on the socket's own thread, before the app's listener hears of the
    // end. [lifecycle] changes later, on the capture thread, so it can't answer
    // whether a call from the app came after the end.
    @Volatile
    private var ended = false

    // Set by this socket's first task, on the capture thread, before the store has the socket.
    @Volatile
    private var description: Description? = null
    override val url: String
        get() = description?.url.orEmpty()
    override val requestHeaders: Map<String, String>
        get() = description?.requestHeaders.orEmpty()
    override val categories: List<String>
        get() = description?.categories.orEmpty()

    // Held only until the first task describes it: it can carry the app's tags.
    @Volatile
    private var request: Request? = request
    private val requestUrl: String = request.url.toString()
    private val outgoingInfo = MessageInfo(requestUrl, isOutgoing = true)
    private val incomingInfo = MessageInfo(requestUrl, isOutgoing = false)

    private class Description(val url: String, val requestHeaders: Map<String, String>, val categories: List<String>)

    private class MessageInfo(override val url: String, override val isOutgoing: Boolean) : WebSocketMessageInfo

    /** An event's place in the log, taken before the event's outcome is known. */
    class Pending(val seq: Long, val at: Long, val clears: Long) {
        var ready = false
        var chars = 0L
        var always = false
        var task: (() -> Unit)? = null
    }

    // Events wait here until every earlier one is ready, and then go to the
    // capture thread in order. A send takes its place before OkHttp gets the
    // message and is ready once send() returns, so the reply to a message never
    // comes before it in the log. Guarded by itself.
    private val waiting = ArrayDeque<Pending>()
    private var nextSeq = 1L

    /** The handshake's transaction, from the interceptor that captured it. */
    fun linkTransaction(id: String) {
        transactionId = id
    }

    /** Lists the connection, before it has any event. */
    fun created() {
        if (capture.isPaused()) return
        val clears = capture.store.clearCount
        capture.worker.offer(id, 0L, always = true) {
            describe()
            capture.store.record(this, null, creates = true, clears = clears)
        }
    }

    fun opened(response: Response) =
        guarded {
            lifecycleEvent { pending ->
                val headers = capture.redactHeaders(response.headers)
                update {
                    it.copy(
                        // close() before the socket opened: its close frame goes out right after.
                        state = if (it.state == WebSocketState.CLOSING) it.state else WebSocketState.OPEN,
                        openedAt = pending.at,
                        statusCode = response.code,
                        protocol = response.protocol.toString(),
                        responseHeaders = headers,
                    )
                }
                WebSocketEvent(pending.seq, pending.at, WebSocketEventKind.OPEN, statusCode = response.code)
            }
        }

    fun received(text: String) =
        guarded {
            if (capture.isPaused()) return@guarded
            val pending = reserve()
            complete(pending, text.length.toLong(), always = false) {
                store(pending, textEvent(pending, text, outgoing = false, enqueued = null))
            }
        }

    fun received(bytes: ByteString) =
        guarded {
            if (capture.isPaused()) return@guarded
            val pending = reserve()
            complete(pending, bytes.size.toLong(), always = false) {
                store(pending, binaryEvent(pending, bytes, outgoing = false, enqueued = null))
            }
        }

    /** Takes the place of a message the app is about to send, or returns `null` while capture is paused. */
    fun beginSend(): Pending? = guarded { if (capture.isPaused()) null else reserve() }

    fun sent(pending: Pending?, text: String, enqueued: Boolean) =
        guarded {
            if (pending == null) return@guarded
            complete(pending, text.length.toLong(), always = false) {
                store(pending, textEvent(pending, text, outgoing = true, enqueued = enqueued))
            }
        }

    fun sent(pending: Pending?, bytes: ByteString, enqueued: Boolean) =
        guarded {
            if (pending == null) return@guarded
            complete(pending, bytes.size.toLong(), always = false) {
                store(pending, binaryEvent(pending, bytes, outgoing = true, enqueued = enqueued))
            }
        }

    /** Gives up the place of a send or close that threw, so later events don't wait for it. */
    fun abandon(pending: Pending?) =
        guarded {
            if (pending != null) complete(pending, 0L, always = true, task = null)
        }

    fun beginClose(): Pending? = guarded { reserve() }

    fun closeCalled(pending: Pending?, code: Int, reason: String?, enqueued: Boolean) =
        guarded {
            if (pending == null) return@guarded
            complete(pending, 0L, always = true) {
                if (enqueued) {
                    update {
                        it.copy(
                            state = if (it.state.ended) it.state else WebSocketState.CLOSING,
                            closedBy = it.closedBy ?: CLOSED_BY_APP,
                        )
                    }
                }
                val event =
                    WebSocketEvent(pending.seq, pending.at, WebSocketEventKind.CLOSE, outgoing = true, enqueued = enqueued, code = code, reason = reason)
                store(pending, event, creates = false)
            }
        }

    fun cancelCalled() =
        guarded {
            // OkHttp ignores cancel() on a socket that has ended, so the log does too.
            if (ended) return@guarded
            lifecycleEvent { pending ->
                update { if (it.state.ended) it else it.copy(canceled = true) }
                WebSocketEvent(pending.seq, pending.at, WebSocketEventKind.CANCEL)
            }
        }

    fun closing(code: Int, reason: String) =
        guarded {
            lifecycleEvent { pending ->
                update {
                    it.copy(
                        state = if (it.state.ended) it.state else WebSocketState.CLOSING,
                        closeCode = code,
                        closeReason = reason,
                        closedBy = it.closedBy ?: CLOSED_BY_SERVER,
                    )
                }
                WebSocketEvent(pending.seq, pending.at, WebSocketEventKind.CLOSE, outgoing = false, code = code, reason = reason)
            }
        }

    fun closed(code: Int, reason: String) =
        guarded {
            ended = true
            lifecycleEvent { pending ->
                update { it.copy(state = WebSocketState.CLOSED, closedAt = pending.at, closeCode = code, closeReason = reason) }
                WebSocketEvent(pending.seq, pending.at, WebSocketEventKind.CLOSED, code = code, reason = reason)
            }
        }

    fun failed(t: Throwable, response: Response?) =
        guarded {
            ended = true
            lifecycleEvent { pending ->
                val error = t.toString().take(MAX_ERROR_CHARS)
                val status = response?.code
                // A response here is the one that refused the upgrade, and its headers say why.
                val headers = response?.let { capture.redactHeaders(it.headers) }
                update {
                    it.copy(
                        state = WebSocketState.FAILED,
                        closedAt = pending.at,
                        error = error,
                        statusCode = status ?: it.statusCode,
                        protocol = response?.protocol?.toString() ?: it.protocol,
                        responseHeaders = headers ?: it.responseHeaders,
                    )
                }
                WebSocketEvent(pending.seq, pending.at, WebSocketEventKind.FAILURE, statusCode = status, error = error)
            }
        }

    private fun reserve(): Pending =
        synchronized(waiting) {
            Pending(nextSeq++, System.currentTimeMillis(), capture.store.clearCount).also { waiting.addLast(it) }
        }

    private fun complete(pending: Pending, chars: Long, always: Boolean, task: (() -> Unit)?) {
        synchronized(waiting) {
            pending.chars = chars
            pending.always = always
            pending.task = task
            pending.ready = true
            while (waiting.firstOrNull()?.ready == true) {
                val next = waiting.removeFirst()
                val queued = next.task ?: continue
                val accepted =
                    capture.worker.offer(id, next.chars, next.always) {
                        describe()
                        queued()
                    }
                if (!accepted) dropped.incrementAndGet()
            }
        }
    }

    // A lifecycle event is small and there are few of them, so it is recorded
    // whatever the capture thread has waiting, and while capture is paused.
    private fun lifecycleEvent(event: (Pending) -> WebSocketEvent) {
        val pending = reserve()
        complete(pending, 0L, always = true) { store(pending, event(pending), creates = false) }
    }

    // A message lists a connection the store no longer has; a lifecycle event doesn't.
    private fun store(pending: Pending, event: WebSocketEvent, creates: Boolean = true) {
        capture.store.record(this, event, creates, pending.clears)
    }

    private fun textEvent(pending: Pending, text: String, outgoing: Boolean, enqueued: Boolean?): WebSocketEvent {
        val cap = capture.maxPayloadBytes()
        val size = text.utf8Size()
        val kept = if (size > cap) text.substring(0, utf8PrefixLength(text, cap)) else text
        val stored = capture.redactText(kept, if (outgoing) outgoingInfo else incomingInfo)
        return WebSocketEvent(
            seq = pending.seq,
            at = pending.at,
            kind = WebSocketEventKind.MESSAGE,
            outgoing = outgoing,
            binary = false,
            payloadBytes = size,
            truncated = stored != null && size > cap,
            text = stored,
            enqueued = enqueued,
        )
    }

    private fun binaryEvent(pending: Pending, bytes: ByteString, outgoing: Boolean, enqueued: Boolean?): WebSocketEvent {
        val cap = capture.maxPayloadBytes().coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        // A copy, so a redactor that changes the array doesn't change the app's message.
        val kept = (if (bytes.size > cap) bytes.substring(0, cap) else bytes).toByteArray()
        val stored = capture.redactBinary(kept, if (outgoing) outgoingInfo else incomingInfo)
        return WebSocketEvent(
            seq = pending.seq,
            at = pending.at,
            kind = WebSocketEventKind.MESSAGE,
            outgoing = outgoing,
            binary = true,
            payloadBytes = bytes.size.toLong(),
            truncated = stored != null && bytes.size > cap,
            bytes = stored,
            enqueued = enqueued,
        )
    }

    private fun update(change: (WebSocketLifecycle) -> WebSocketLifecycle) {
        lifecycle = change(lifecycle)
    }

    private fun describe() {
        if (description != null) return
        val request = request ?: return
        val shown = capture.redactUrl(displayUrl(requestUrl))
        description = Description(shown, capture.redactHeaders(request.headers), capture.classify(shown))
        this.request = null
    }

    private inline fun <T> guarded(block: () -> T): T? =
        try {
            block()
        } catch (t: Throwable) {
            capture.logFailure("A WebSocket capture failed", t)
            null
        }

    private companion object {
        private const val CLOSED_BY_APP = "app"
        private const val CLOSED_BY_SERVER = "server"
        private const val MAX_ERROR_CHARS = 500

        // OkHttp turns ws: into http: when the request is built; the console shows what the app asked for.
        private fun displayUrl(url: String): String =
            when {
                url.startsWith("https:") -> "wss:" + url.substring("https:".length)
                url.startsWith("http:") -> "ws:" + url.substring("http:".length)
                else -> url
            }
    }
}

/**
 * The length of the longest prefix of [text] that takes at most [maxBytes] in
 * UTF-8 and doesn't end between the two halves of a surrogate pair.
 */
internal fun utf8PrefixLength(text: String, maxBytes: Long): Int {
    var bytes = 0L
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val pair = c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()
        val size =
            when {
                c.code < ASCII_END -> 1
                c.code < TWO_BYTE_END -> 2
                pair -> 4
                else -> 3
            }
        if (bytes + size > maxBytes) break
        bytes += size
        i += if (pair) 2 else 1
    }
    return i
}

private const val ASCII_END = 0x80
private const val TWO_BYTE_END = 0x800
