package io.github.twinsen81.lustro.work.internal

import android.util.Log
import androidx.work.WorkInfo
import io.github.twinsen81.lustro.CursorCodec
import kotlin.random.Random

/**
 * One event of the listeners: [json] is the whole event object, written when the
 * event came. [id], [workerClass], and [type] stay apart for the filters.
 */
internal class WorkEvent(val seq: Long, val type: String, val id: String, val workerClass: String?, val json: String) {
    val sizeBytes: Int get() = WorkEvents.EVENT_BYTES + json.length * 2
}

/**
 * The events that the tab's listeners record, in a buffer of [maxBytes] whose
 * oldest events go first when it is full. They live on the app's heap only, so
 * the events of a process end with it.
 *
 * The events form the stream that `GET events` serves with the stream envelope:
 * [Snapshot.total] counts every event ever added, and a cursor is a position in
 * that count. The [epoch] is new in each process.
 */
internal class WorkEvents(private val maxBytes: Int, private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Any()

    // Guarded by lock.
    private val events = ArrayDeque<WorkEvent>()
    private var usedBytes = 0L
    private var total = 0L

    val epoch: Long = Random.nextLong()

    @Volatile
    private var failed = false

    /**
     * Adds an event of [type] for [info]; [details] writes the members that the type
     * adds, each after a comma. Runs on WorkManager's event dispatcher, so it never
     * throws: a failure is logged once and the event is lost.
     */
    fun record(type: String, info: WorkInfo, details: StringBuilder.() -> Unit = {}) {
        try {
            val id = info.id.toString()
            val workerClass = info.workerClassName
            val body =
                buildString {
                    append(",\"at\":").append(clock())
                    append(",\"type\":\"").append(type).append('"')
                    append(",\"id\":\"").append(id).append('"')
                    append(",\"workerClass\":")
                    WorkJson.appendNullable(this, workerClass)
                    append(",\"state\":\"").append(info.state.name).append('"')
                    append(",\"runAttempt\":").append(info.runAttemptCount)
                    append(",\"generation\":").append(info.generation)
                    details()
                    append('}')
                }
            synchronized(lock) {
                val event = WorkEvent(total, type, id, workerClass, "{\"seq\":$total$body")
                events.addLast(event)
                usedBytes += event.sizeBytes
                total++
                // The newest event stays even when it alone is over the budget.
                while (usedBytes > maxBytes && events.size > 1) usedBytes -= events.removeFirst().sizeBytes
            }
        } catch (e: Exception) {
            failedOnce(e)
        } catch (e: LinkageError) {
            failedOnce(e)
        }
    }

    /**
     * The events that a response to [cursor] needs, at one moment: from the first
     * event after [cursor] when it is a cursor of this buffer, or all of them.
     */
    fun snapshot(cursor: String?): Snapshot =
        synchronized(lock) {
            val evicted = total - events.size
            // The same test as DebugResponse.streamEnvelope, which takes this
            // snapshot's total and size: a cursor it answers with a delta starts here.
            val seen = CursorCodec.decode(cursor, epoch)?.takeIf { it in 0..total }
            val first = if (seen == null) 0 else (seen - evicted).coerceIn(0L, events.size.toLong()).toInt()
            Snapshot(total, events.size, first, events.subList(first, events.size).toList())
        }

    /** The retained events of the work with [id], oldest first. */
    fun of(id: String): List<WorkEvent> = synchronized(lock) { events.filter { it.id == id } }

    /** How many bytes the held events take by the buffer's count. For tests. */
    fun usedBytes(): Long = synchronized(lock) { usedBytes }

    private fun failedOnce(e: Throwable) {
        if (failed) return
        failed = true
        Log.w(TAG, "The WorkManager tab could not record an event; it logs this once", e)
    }

    /** The events from [first] to [retained] of a buffer that had [total] events. */
    class Snapshot(val total: Long, val retained: Int, val first: Int, private val events: List<WorkEvent>) {
        /** The event at [index] of the retained events, which must be at least [first]. */
        operator fun get(index: Int): WorkEvent = events[index - first]
    }

    companion object {
        // The event object, its strings' headers, and its place in the deque.
        const val EVENT_BYTES: Int = 96

        const val TAG: String = "LustroWork"
    }
}
