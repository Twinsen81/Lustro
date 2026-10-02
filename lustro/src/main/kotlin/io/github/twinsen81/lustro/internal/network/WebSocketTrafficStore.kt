package io.github.twinsen81.lustro.internal.network

import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import okio.utf8Size

internal enum class WebSocketEventKind(val wireName: String) {
    OPEN("open"),
    MESSAGE("message"),

    // A close frame: the app's close() call when outgoing, the peer's frame when not.
    CLOSE("close"),
    CLOSED("closed"),
    CANCEL("cancel"),
    FAILURE("failure"),
}

/**
 * One entry of a connection's log: a message, or a lifecycle event. Never
 * changed after it is stored, so readers can share it.
 */
internal class WebSocketEvent(
    val seq: Long,
    // Epoch milliseconds.
    val at: Long,
    val kind: WebSocketEventKind,
    val outgoing: Boolean? = null,
    val binary: Boolean = false,
    // The size of the whole payload, also when only a prefix of it is kept.
    val payloadBytes: Long = 0,
    val truncated: Boolean = false,
    val text: String? = null,
    val bytes: ByteArray? = null,
    // What send() or close() returned. true means OkHttp queued it, not that the peer got it.
    val enqueued: Boolean? = null,
    val code: Int? = null,
    val reason: String? = null,
    val statusCode: Int? = null,
    val error: String? = null,
) {
    val retainedBytes: Long = (text?.utf8Size() ?: bytes?.size?.toLong() ?: 0L) + OVERHEAD_BYTES

    private companion object {
        // Counts an entry with no payload, so a flood of those still fills the budget.
        private const val OVERHEAD_BYTES = 64L
    }
}

internal enum class WebSocketState(val wireName: String) {
    CONNECTING("connecting"),
    OPEN("open"),
    CLOSING("closing"),
    CLOSED("closed"),
    FAILED("failed"),
    ;

    val ended: Boolean
        get() = this == CLOSED || this == FAILED
}

/** Where a connection is in its life. A changed copy replaces it on each lifecycle event. */
internal data class WebSocketLifecycle(
    val state: WebSocketState = WebSocketState.CONNECTING,
    val openedAt: Long? = null,
    val closedAt: Long? = null,
    val statusCode: Int? = null,
    val protocol: String? = null,
    val responseHeaders: Map<String, String>? = null,
    val closeCode: Int? = null,
    val closeReason: String? = null,
    // "app" or "server": whose close frame came first.
    val closedBy: String? = null,
    val canceled: Boolean = false,
    val error: String? = null,
)

/** What the store reads from a live socket. Every member is safe to read on any thread. */
internal interface WebSocketSource {
    val id: String
    val startedAt: Long
    val url: String
    val requestHeaders: Map<String, String>
    val categories: List<String>
    val lifecycle: WebSocketLifecycle
    val transactionId: String?

    /** Events the capture thread never got, because it was behind. */
    val droppedEvents: Long
}

/** A connection as a reader sees it: a copy taken under the store's lock. */
internal class WebSocketConnection(
    val id: String,
    val url: String,
    val categories: List<String>,
    val startedAt: Long,
    val lifecycle: WebSocketLifecycle,
    val transactionId: String?,
    val requestHeaders: Map<String, String>,
    val sentCount: Long,
    val sentBytes: Long,
    val receivedCount: Long,
    val receivedBytes: Long,
    val storedEvents: Int,
    val evictedEvents: Long,
    val droppedEvents: Long,
)

/**
 * A connection's stored events, oldest first. A log cursor counts stored
 * events, not [WebSocketEvent.seq], which skips the events that were never
 * stored: [stored] is how many this log ever had, so the first one here is
 * number `stored - events.size + 1`.
 */
internal class WebSocketEventLog(
    val events: List<WebSocketEvent>,
    val stored: Long,
    // Tells this log's cursors apart from those of any other, and from those of
    // this connection's log before a clear.
    val epoch: Long,
)

/**
 * Thread-safe in-memory store for captured WebSocket connections and their
 * event logs. Three limits bound it: the number of connections, the number of
 * events per connection, and the bytes of every stored payload together.
 *
 * A connection the store no longer has, after a clear or an eviction, comes
 * back with its next message, because a socket can stay open for hours.
 */
internal class WebSocketTrafficStore(
    maxConnections: Int = DEFAULT_MAX_CONNECTIONS,
    maxEventsPerConnection: Int = DEFAULT_MAX_EVENTS,
    budgetBytes: Long = DEFAULT_BUDGET_BYTES,
) {
    @Volatile
    var maxConnections: Int = maxConnections

    @Volatile
    var maxEventsPerConnection: Int = maxEventsPerConnection

    @Volatile
    var budgetBytes: Long = budgetBytes

    /** Tells this store's polling cursors apart from those of earlier stores. */
    val epoch: Long = Random.nextLong()

    /** Bumped by [clear], so an event from before a clear is never stored after it. */
    @Volatile
    var clearCount: Long = 0L
        private set

    private val lock = Any()

    // In the order the connections were first stored, oldest first.
    private val connections = LinkedHashMap<String, Entry>()
    private var retainedBytes = 0L
    private val sequence = AtomicLong()

    private class Entry(val source: WebSocketSource) {
        val epoch = Random.nextLong()
        val events = ArrayDeque<WebSocketEvent>()
        var bytes = 0L
        var stored = 0L
        var sentCount = 0L
        var sentBytes = 0L
        var receivedCount = 0L
        var receivedBytes = 0L
        var evicted = 0L
    }

    /**
     * Stores [event] in the log of [source]'s connection. When the store has no
     * such connection, [creates] says whether to add it: a message does, and a
     * lifecycle event of a connection that was cleared doesn't. [clears] is
     * [clearCount] from when the event happened.
     */
    fun record(source: WebSocketSource, event: WebSocketEvent?, creates: Boolean, clears: Long) {
        synchronized(lock) {
            if (clears != clearCount) return
            var entry = connections[source.id]
            if (entry == null) {
                if (!creates) return
                entry = Entry(source)
                connections[source.id] = entry
                trimConnections(keep = entry)
            }
            if (event != null) append(entry, event)
        }
        sequence.incrementAndGet()
    }

    private fun append(entry: Entry, event: WebSocketEvent) {
        entry.events.addLast(event)
        entry.stored++
        entry.bytes += event.retainedBytes
        retainedBytes += event.retainedBytes
        if (event.kind == WebSocketEventKind.MESSAGE) {
            if (event.outgoing == true) {
                // A message send() refused was never sent.
                if (event.enqueued != false) {
                    entry.sentCount++
                    entry.sentBytes += event.payloadBytes
                }
            } else {
                entry.receivedCount++
                entry.receivedBytes += event.payloadBytes
            }
        }
        while (entry.events.size > maxEventsPerConnection.coerceAtLeast(1)) evictOldestEvent(entry)
        trimToBudget(entry)
    }

    private fun evictOldestEvent(entry: Entry) {
        val evicted = entry.events.removeFirst()
        entry.bytes -= evicted.retainedBytes
        retainedBytes -= evicted.retainedBytes
        entry.evicted++
    }

    // Payload bytes are what costs memory, and a connection's row costs almost
    // none, so the budget only ever shortens a log: each time, the one that
    // holds the most. The event just stored is kept even when it is over the
    // whole budget on its own, because the cap on a payload bounds it.
    private fun trimToBudget(current: Entry) {
        while (retainedBytes > budgetBytes) {
            val largest =
                connections.values
                    .filter { it.events.size > if (it === current) 1 else 0 }
                    .maxByOrNull { it.bytes }
                    ?: return
            evictOldestEvent(largest)
        }
    }

    // Prefers the oldest connection that has ended: one still open would come
    // back with its next message, and lose its log on the way.
    private fun trimConnections(keep: Entry) {
        while (connections.size > maxConnections.coerceAtLeast(1)) {
            val victim =
                connections.values.firstOrNull { it !== keep && it.source.lifecycle.state.ended }
                    ?: connections.values.firstOrNull { it !== keep }
                    ?: return
            connections.remove(victim.source.id)
            retainedBytes -= victim.bytes
        }
    }

    fun clear() {
        synchronized(lock) {
            clearCount++
            connections.clear()
            retainedBytes = 0L
        }
        sequence.incrementAndGet()
    }

    fun getSequence(): Long = sequence.get()

    /** Newest first. */
    fun getConnections(): List<WebSocketConnection> =
        synchronized(lock) { connections.values.map { it.snapshot() } }.asReversed()

    fun getConnection(id: String): WebSocketConnection? = synchronized(lock) { connections[id]?.snapshot() }

    /** Oldest first, or `null` when the store has no such connection. */
    fun getEvents(id: String): WebSocketEventLog? =
        synchronized(lock) {
            connections[id]?.let { WebSocketEventLog(it.events.toList(), it.stored, it.epoch) }
        }

    /** The connections whose handshake was captured as a transaction, by transaction id. */
    fun transactionLinks(): Map<String, String> =
        synchronized(lock) {
            val links = HashMap<String, String>()
            for (entry in connections.values) entry.source.transactionId?.let { links[it] = entry.source.id }
            links
        }

    /** Bytes of every stored payload together. Exposed for tests. */
    fun retainedBytes(): Long = synchronized(lock) { retainedBytes }

    private fun Entry.snapshot(): WebSocketConnection =
        WebSocketConnection(
            id = source.id,
            url = source.url,
            categories = source.categories,
            startedAt = source.startedAt,
            lifecycle = source.lifecycle,
            transactionId = source.transactionId,
            requestHeaders = source.requestHeaders,
            sentCount = sentCount,
            sentBytes = sentBytes,
            receivedCount = receivedCount,
            receivedBytes = receivedBytes,
            storedEvents = events.size,
            evictedEvents = evicted,
            droppedEvents = source.droppedEvents,
        )

    private companion object {
        private const val DEFAULT_MAX_CONNECTIONS = 100
        private const val DEFAULT_MAX_EVENTS = 1000
        private const val DEFAULT_BUDGET_BYTES = 16L * 1024 * 1024
    }
}
