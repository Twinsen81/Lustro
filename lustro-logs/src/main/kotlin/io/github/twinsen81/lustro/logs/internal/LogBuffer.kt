package io.github.twinsen81.lustro.logs.internal

import io.github.twinsen81.lustro.CursorCodec
import kotlin.random.Random

/**
 * The lines the tab holds: a ring of slots with a budget of [maxBytes], whose
 * oldest slots go first when it is full.
 *
 * The slots form the stream that `GET entries` serves with the stream envelope:
 * [total] counts every slot ever added, and a cursor is a position in that
 * count. A slot holds an entry, or nothing after its entry moved to a later slot.
 *
 * With [compact] on, a line that repeats the newest entry folds into it, as a
 * larger count. While no response has read the buffer since that entry came,
 * the entry changes in its slot, which costs nothing. After a response read it,
 * a client may hold a cursor past the slot and would not see the change, so the
 * entry moves to a new slot at the end. Then a flood of one line costs a slot
 * for each poll, not a copy of the line for each repeat.
 *
 * [clear] empties the ring and starts a new epoch, so a cursor from before gets
 * a reset, not a `dropped` count for the lines that were cleared.
 */
internal class LogBuffer(private val maxBytes: Int) {
    private val lock = Any()

    // Guarded by lock.
    private val slots = ArrayDeque<LogEntry?>()
    private var usedBytes = 0L
    private var total = 0L
    private var epoch = Random.nextLong()

    // `total` when a response last read the buffer: a client can hold a cursor at it.
    private var readAtTotal = -1L

    // Whether the next line may fold into the newest entry. False after a clear, a
    // pause, or a change of compact, so that a fold never spans lines it didn't see.
    private var foldable = false

    @Volatile
    var paused: Boolean = false
        set(value) {
            synchronized(lock) {
                field = value
                foldable = false
            }
        }

    @Volatile
    var compact: Boolean = true
        set(value) {
            synchronized(lock) {
                field = value
                foldable = false
            }
        }

    /** Adds [line], unless the buffer is [paused]. */
    fun append(line: LogLine) {
        synchronized(lock) {
            if (paused) return
            val tail = if (compact && foldable) slots.lastOrNull() else null
            if (tail != null && tail.isSameLine(line)) {
                val repeated = tail.repeatedAt(line.at)
                if (readAtTotal < total) {
                    slots[slots.size - 1] = repeated
                } else {
                    slots[slots.size - 1] = null
                    addSlot(repeated, bytes = SLOT_BYTES)
                }
            } else {
                val entry = LogEntry.of(seq = total, line)
                addSlot(entry, bytes = SLOT_BYTES + entry.sizeBytes)
            }
            foldable = true
            evict()
        }
    }

    /**
     * Empties the buffer and starts a new epoch. Returns the cursor that sees
     * the buffer as empty, so its next poll gets only the lines after the clear.
     */
    fun clear(): String =
        synchronized(lock) {
            slots.clear()
            usedBytes = 0L
            epoch = Random.nextLong()
            foldable = false
            readAtTotal = total
            CursorCodec.encode(total, epoch)
        }

    /**
     * Returns the slots that a response to [cursor] needs, at one moment: from
     * the first slot after [cursor] when it is a cursor of this epoch, or all of
     * them. Set [issuesCursor] when the response gives the client a cursor.
     */
    fun snapshot(cursor: String?, issuesCursor: Boolean): Snapshot =
        synchronized(lock) {
            if (issuesCursor) readAtTotal = total
            val evicted = total - slots.size
            // The same test as DebugResponse.streamEnvelope, which takes this
            // snapshot's total and size: a cursor it answers with a delta starts here.
            val seen = CursorCodec.decode(cursor, epoch)?.takeIf { it in 0..total }
            val first = if (seen == null) 0 else (seen - evicted).coerceIn(0L, slots.size.toLong()).toInt()
            val copy = arrayOfNulls<LogEntry>(slots.size - first)
            for (index in copy.indices) copy[index] = slots[first + index]
            Snapshot(epoch, total, slots.size, first, copy)
        }

    /** How many bytes the held lines take by the buffer's count. For tests. */
    fun usedBytes(): Long = synchronized(lock) { usedBytes }

    private fun addSlot(entry: LogEntry, bytes: Int) {
        slots.addLast(entry)
        usedBytes += bytes
        total++
    }

    // Keeps the newest slot even when it alone is over the budget.
    private fun evict() {
        while (usedBytes > maxBytes && slots.size > 1) {
            val entry = slots.removeFirst()
            usedBytes -= SLOT_BYTES + (entry?.sizeBytes ?: 0)
        }
    }

    /**
     * The slots from [first] to [retained] of a buffer that had [total] slots
     * in [epoch]. A slot is `null` when its entry moved to a later one.
     */
    class Snapshot(val epoch: Long, val total: Long, val retained: Int, val first: Int, private val slots: Array<LogEntry?>) {
        /** The slot at [index] of the retained slots, which must be at least [first]. */
        operator fun get(index: Int): LogEntry? = slots[index - first]
    }

    companion object {
        // A reference in the ring and its share of the ring's spare capacity.
        const val SLOT_BYTES: Int = 16
    }
}
