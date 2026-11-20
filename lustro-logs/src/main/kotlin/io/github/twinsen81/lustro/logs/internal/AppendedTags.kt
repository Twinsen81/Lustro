package io.github.twinsen81.lustro.logs.internal

import java.util.concurrent.ConcurrentHashMap

/**
 * The tags whose lines the app's logger appends to the tab, and when it first
 * appended a line of each.
 *
 * A logcat line of a tag that [isAppendedTag] takes stays out of the buffer
 * from the first append of that tag on, because the appended line has the
 * whole text. The older lines of the tag come from logcat: the logger could not
 * append them yet, for example because the app gives it the tab after it
 * creates the tab. So no line is lost, also when the logger never appends.
 *
 * [appended] and [drops] are safe to call from any thread.
 */
internal class AppendedTags(private val isAppendedTag: (String) -> Boolean = { false }) {
    // One entry for each tag that the app appended.
    private val firstAppendedAt = ConcurrentHashMap<String, Long>()

    /** Records that the app appended a line of [tag] at [at], in epoch milliseconds. */
    fun appended(tag: String, at: Long) {
        // After the first line of a tag, a lookup without a lock is enough.
        if (firstAppendedAt[tag] == null) firstAppendedAt.putIfAbsent(tag, at)
    }

    /**
     * Whether the logcat [line] stays out of the buffer: its tag is one that
     * [isAppendedTag] takes, and the app appended a line of the tag at the
     * line's time or before.
     */
    fun drops(line: LogLine): Boolean {
        val since = firstAppendedAt[line.tag] ?: return false
        return line.at >= since && isAppendedTagSafely(line.tag)
    }

    // The app's code decides, and a failure there must not end the reader: the line then stays.
    private fun isAppendedTagSafely(tag: String): Boolean =
        try {
            isAppendedTag(tag)
        } catch (_: RuntimeException) {
            false
        }
}
