package io.github.twinsen81.lustro.logs.internal

import io.github.twinsen81.lustro.CursorCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogBufferTest {
    @Test
    fun `lines get the place where they came as their seq`() {
        val buffer = LogBuffer(1 shl 20)
        buffer.append(line("a"))
        buffer.append(line("b"))
        buffer.append(line("c"))

        val snapshot = buffer.snapshot(null, issuesCursor = false)
        assertEquals(3, snapshot.retained)
        assertEquals(listOf(0L, 1L, 2L), entries(snapshot).map { it.seq })
        assertEquals(listOf("a", "b", "c"), entries(snapshot).map { it.message })
    }

    @Test
    fun `the oldest lines go when the budget is full, and the newest stays even when it alone is over`() {
        val oneLine = LogBuffer.SLOT_BYTES + LogEntry.of(0, line("x".repeat(50))).sizeBytes
        val buffer = LogBuffer(oneLine * 2)
        repeat(5) { buffer.append(line("x".repeat(49) + it)) }

        val snapshot = buffer.snapshot(null, issuesCursor = false)
        assertEquals(5L, snapshot.total)
        assertEquals(listOf(3L, 4L), entries(snapshot).map { it.seq })
        assertTrue(buffer.usedBytes() <= oneLine * 2)

        buffer.append(line("y".repeat(10_000)))
        val after = buffer.snapshot(null, issuesCursor = false)
        assertEquals(listOf(5L), entries(after).map { it.seq })
    }

    @Test
    fun `a repeat folds into the newest entry in its slot while no response read it`() {
        val buffer = LogBuffer(1 shl 20)
        buffer.append(line("tick", at = 100))
        buffer.append(line("tick", at = 200))
        buffer.append(line("tick", at = 300))

        val snapshot = buffer.snapshot(null, issuesCursor = true)
        assertEquals(1L, snapshot.total)
        val only = entries(snapshot).single()
        assertEquals(0L, only.seq)
        assertEquals(3, only.count)
        assertEquals(100L, only.at)
        assertEquals(300L, only.lastAt)
    }

    @Test
    fun `after a response read the buffer, a repeat moves the entry to a new slot once, and folds there`() {
        val buffer = LogBuffer(1 shl 20)
        buffer.append(line("other"))
        buffer.append(line("tick"))
        buffer.snapshot(null, issuesCursor = true)
        val used = buffer.usedBytes()

        buffer.append(line("tick"))
        buffer.append(line("tick"))

        val snapshot = buffer.snapshot(null, issuesCursor = false)
        assertEquals(3L, snapshot.total)
        assertNull(snapshot[1])
        val moved = snapshot[2]!!
        assertEquals(1L, moved.seq)
        assertEquals(3, moved.count)
        // The move costs a slot, not a copy of the line.
        assertEquals(used + LogBuffer.SLOT_BYTES, buffer.usedBytes())
    }

    @Test
    fun `a text response does not count as a read`() {
        val buffer = LogBuffer(1 shl 20)
        buffer.append(line("tick"))
        buffer.snapshot(null, issuesCursor = false)
        buffer.append(line("tick"))
        assertEquals(1L, buffer.snapshot(null, issuesCursor = false).total)
    }

    @Test
    fun `lines differ by level, tag, pid, tid, or message, and compact off keeps every line`() {
        val buffer = LogBuffer(1 shl 20)
        buffer.append(line("m"))
        buffer.append(line("m", level = 'W'))
        buffer.append(line("m", tag = "Other"))
        buffer.append(line("m", tid = 9))
        buffer.append(line("n"))
        assertEquals(5, buffer.snapshot(null, issuesCursor = false).retained)

        buffer.compact = false
        buffer.append(line("n"))
        buffer.append(line("n"))
        assertEquals(7, buffer.snapshot(null, issuesCursor = false).retained)
    }

    @Test
    fun `a paused buffer drops lines, and a repeat does not fold across the pause`() {
        val buffer = LogBuffer(1 shl 20)
        buffer.append(line("tick"))
        buffer.paused = true
        buffer.append(line("dropped"))
        buffer.paused = false
        buffer.append(line("tick"))

        val messages = entries(buffer.snapshot(null, issuesCursor = false))
        assertEquals(listOf("tick", "tick"), messages.map { it.message })
        assertEquals(listOf(1, 1), messages.map { it.count })
    }

    @Test
    fun `clear empties the buffer, keeps counting, and its cursor sees only later lines`() {
        val buffer = LogBuffer(1 shl 20)
        buffer.append(line("a"))
        val before = buffer.snapshot(null, issuesCursor = true)
        val cursor = buffer.clear()

        buffer.append(line("b"))
        val after = buffer.snapshot(cursor, issuesCursor = true)
        assertNotEquals(before.epoch, after.epoch)
        assertEquals(2L, after.total)
        assertEquals(1, after.retained)
        assertEquals(0, after.first)
        assertEquals(1L, after[0]!!.seq)
    }

    @Test
    fun `a snapshot for a cursor of this epoch starts after it, and for any other cursor at the start`() {
        val buffer = LogBuffer(1 shl 20)
        listOf("a", "b", "c").forEach { buffer.append(line(it)) }
        val epoch = buffer.snapshot(null, issuesCursor = false).epoch

        assertEquals(2, buffer.snapshot(CursorCodec.encode(2, epoch), issuesCursor = false).first)
        assertEquals(0, buffer.snapshot(CursorCodec.encode(2, epoch + 1), issuesCursor = false).first)
        assertEquals(0, buffer.snapshot(CursorCodec.encode(9, epoch), issuesCursor = false).first)
        assertEquals(0, buffer.snapshot("not-a-cursor", issuesCursor = false).first)
    }

    private fun entries(snapshot: LogBuffer.Snapshot): List<LogEntry> =
        (snapshot.first until snapshot.retained).mapNotNull { snapshot[it] }
}

internal fun line(
    message: String,
    at: Long = 1_000L,
    level: Char = 'I',
    tag: String = "Tag",
    pid: Int = 100,
    tid: Int = 101,
): LogLine = LogLine(at, pid, tid, level, tag, message)
