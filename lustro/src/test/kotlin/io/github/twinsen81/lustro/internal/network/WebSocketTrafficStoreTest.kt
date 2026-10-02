package io.github.twinsen81.lustro.internal.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebSocketTrafficStoreTest {
    private class Source(override val id: String) : WebSocketSource {
        override val startedAt: Long = 1_000L
        override val url: String = "wss://example.com/$id"
        override val requestHeaders: Map<String, String> = emptyMap()
        override val categories: List<String> = emptyList()
        override var lifecycle: WebSocketLifecycle = WebSocketLifecycle()
        override var transactionId: String? = null
        override var droppedEvents: Long = 0L

        fun end() {
            lifecycle = lifecycle.copy(state = WebSocketState.CLOSED)
        }
    }

    private var seq = 0L

    private fun message(text: String, outgoing: Boolean = false, enqueued: Boolean? = null): WebSocketEvent =
        WebSocketEvent(
            seq = ++seq,
            at = 2_000L + seq,
            kind = WebSocketEventKind.MESSAGE,
            outgoing = outgoing,
            payloadBytes = text.length.toLong(),
            text = text,
            enqueued = enqueued,
        )

    private fun WebSocketTrafficStore.add(source: Source, event: WebSocketEvent?, creates: Boolean = true) =
        record(source, event, creates, clearCount)

    private fun WebSocketTrafficStore.texts(id: String): List<String?> = getEvents(id)!!.events.map { it.text }

    @Test
    fun `a log past its limit drops its oldest events and counts them`() {
        val store = WebSocketTrafficStore(maxEventsPerConnection = 2)
        val socket = Source("a")
        listOf("one", "two", "three").forEach { store.add(socket, message(it)) }

        assertEquals(listOf("two", "three"), store.texts("a"))
        val connection = store.getConnection("a")!!
        assertEquals(1, connection.evictedEvents)
        // The counts are of every message, also the one the log no longer has.
        assertEquals(3, connection.receivedCount)
        assertEquals(11, connection.receivedBytes)
        assertEquals(3, store.getEvents("a")!!.stored)
    }

    @Test
    fun `the byte budget shortens the log that holds the most, and keeps its newest event`() {
        val store = WebSocketTrafficStore(budgetBytes = 1_000)
        val small = Source("small")
        val large = Source("large")
        store.add(small, message("x".repeat(100)))
        repeat(3) { store.add(large, message("y".repeat(400))) }

        assertEquals(1, store.getEvents("small")!!.events.size)
        assertEquals(1, store.getEvents("large")!!.events.size)
        assertEquals(2, store.getConnection("large")!!.evictedEvents)
        assertEquals(true, store.retainedBytes() <= 1_000)

        // One payload over the whole budget is still kept: the cap on a payload bounds it.
        store.add(large, message("z".repeat(5_000)))
        assertEquals(listOf<String?>("z".repeat(5_000)), store.texts("large"))
    }

    @Test
    fun `past the connection limit an ended connection goes first, and an open one only when none has ended`() {
        val store = WebSocketTrafficStore(maxConnections = 2)
        val first = Source("first")
        val second = Source("second").apply { end() }
        store.add(first, null)
        store.add(second, null)

        store.add(Source("third"), null)
        assertEquals(listOf("third", "first"), store.getConnections().map { it.id })

        store.add(Source("fourth"), null)
        assertEquals(listOf("fourth", "third"), store.getConnections().map { it.id })
    }

    @Test
    fun `a message lists a connection the store doesn't have, and a lifecycle event doesn't`() {
        val store = WebSocketTrafficStore()
        val socket = Source("a")

        store.add(socket, WebSocketEvent(++seq, 2_000L, WebSocketEventKind.CLOSED, code = 1000), creates = false)
        assertNull(store.getConnection("a"))

        store.add(socket, message("hello"))
        assertEquals(1, store.getConnection("a")!!.storedEvents)
    }

    @Test
    fun `an event from before a clear isn't stored after it, and the next log has a new epoch`() {
        val store = WebSocketTrafficStore()
        val socket = Source("a")
        store.add(socket, message("before"))
        val epoch = store.getEvents("a")!!.epoch
        val clears = store.clearCount

        store.clear()
        store.record(socket, message("late"), creates = true, clears = clears)
        assertNull(store.getConnection("a"))

        store.add(socket, message("after"))
        assertEquals(listOf<String?>("after"), store.texts("a"))
        assertNotEquals(epoch, store.getEvents("a")!!.epoch)
    }

    @Test
    fun `a message that send refused isn't counted as sent`() {
        val store = WebSocketTrafficStore()
        val socket = Source("a")
        store.add(socket, message("queued", outgoing = true, enqueued = true))
        store.add(socket, message("refused", outgoing = true, enqueued = false))

        val connection = store.getConnection("a")!!
        assertEquals(1, connection.sentCount)
        assertEquals(6, connection.sentBytes)
        assertEquals(2, connection.storedEvents)
    }

    @Test
    fun `the sequence moves with every change, so a poll sees it`() {
        val store = WebSocketTrafficStore()
        val socket = Source("a")
        val start = store.getSequence()
        store.add(socket, null)
        store.add(socket, message("hello"))
        store.clear()
        assertEquals(start + 3, store.getSequence())
    }

    @Test
    fun `a handshake's transaction leads to its connection`() {
        val store = WebSocketTrafficStore()
        val socket = Source("a").apply { transactionId = "tx-1" }
        store.add(socket, null)
        store.add(Source("b"), null)
        assertEquals(mapOf("tx-1" to "a"), store.transactionLinks())
    }
}
