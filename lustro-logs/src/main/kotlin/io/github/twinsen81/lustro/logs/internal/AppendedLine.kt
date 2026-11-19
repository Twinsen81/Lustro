package io.github.twinsen81.lustro.logs.internal

import android.util.Log

/** Builds the [LogLine] of a line that the app appends, not logcat. */
internal object AppendedLine {
    /** The most characters that an appended message keeps, with its stack trace. */
    const val MAX_CHARS: Int = 64 * 1024

    // A buffer of bufferBytes holds at least eight appended lines: the text takes two bytes a character.
    private const val BUFFER_SHARE = 16

    /** The cap of an appended message in a buffer of [bufferBytes]: [MAX_CHARS], or an eighth of the buffer when that is less. */
    fun maxChars(bufferBytes: Int): Int = minOf(MAX_CHARS, bufferBytes / BUFFER_SHARE).coerceAtLeast(1)

    /**
     * Returns the line of [message] and, after a line break, the stack trace of
     * [throwable], as `Log.e(tag, message, throwable)` writes them. The text keeps
     * its first [maxChars] characters, and then says how many it cut.
     */
    fun of(
        priority: Int,
        tag: String,
        message: String,
        throwable: Throwable?,
        maxChars: Int,
        at: Long,
        pid: Int,
        tid: Int,
    ): LogLine {
        val trace = throwable?.let(::stackTraceOf)
        val text =
            when {
                trace == null -> message
                message.isEmpty() -> trace
                else -> "$message\n$trace"
            }
        return LogLine(at, pid, tid, levelOf(priority), tag, cut(text, maxChars))
    }

    /** logcat's letter for an `android.util.Log` priority. A priority out of range takes the nearest one. */
    fun levelOf(priority: Int): Char =
        when {
            priority <= Log.VERBOSE -> 'V'
            priority == Log.DEBUG -> 'D'
            priority == Log.INFO -> 'I'
            priority == Log.WARN -> 'W'
            priority == Log.ERROR -> 'E'
            else -> 'F'
        }

    fun cut(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        // Don't keep half of a surrogate pair.
        val end = if (Character.isHighSurrogate(text[maxChars - 1])) maxChars - 1 else maxChars
        return text.substring(0, end) + "… cut (" + (text.length - end) + " more characters)"
    }

    // A throwable whose own code fails while it prints must not fail the app's logger.
    private fun stackTraceOf(throwable: Throwable): String =
        try {
            throwable.stackTraceToString().trimEnd()
        } catch (e: RuntimeException) {
            "${throwable.javaClass.name} (its stack trace could not be printed: $e)"
        }
}
