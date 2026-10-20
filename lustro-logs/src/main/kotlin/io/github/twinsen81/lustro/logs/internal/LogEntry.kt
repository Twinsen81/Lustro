package io.github.twinsen81.lustro.logs.internal

/** One line of `logcat -v threadtime`, parsed. [at] is in epoch milliseconds. */
internal class LogLine(
    val at: Long,
    val pid: Int,
    val tid: Int,
    val level: Char,
    val tag: String,
    val message: String,
)

/**
 * A line in the buffer, with the number of times it came in a row ([count]) when
 * the buffer folds repeats. [seq] is the place in the stream where the line came
 * first, and it identifies the entry: a repeat replaces the entry with a copy
 * that has the same [seq], a larger [count], and the time of the repeat in
 * [lastAt]. Immutable, so a response can read it without the buffer's lock.
 */
internal class LogEntry(
    val seq: Long,
    val at: Long,
    val pid: Int,
    val tid: Int,
    val level: Char,
    val tag: String,
    val message: String,
    val count: Int = 1,
    val lastAt: Long = at,
) {
    // The strings are UTF-16 on the heap; the constant covers the object headers.
    val sizeBytes: Int get() = ENTRY_OVERHEAD_BYTES + 2 * (tag.length + message.length)

    fun isSameLine(line: LogLine): Boolean =
        level == line.level && pid == line.pid && tid == line.tid && tag == line.tag && message == line.message

    fun repeatedAt(time: Long): LogEntry = LogEntry(seq, at, pid, tid, level, tag, message, count + 1, time)

    companion object {
        const val ENTRY_OVERHEAD_BYTES: Int = 96

        fun of(seq: Long, line: LogLine): LogEntry =
            LogEntry(seq, line.at, line.pid, line.tid, line.level, line.tag, line.message)
    }
}
