package io.github.twinsen81.lustro.logs.internal

import java.util.concurrent.ConcurrentHashMap

/**
 * The tags whose lines the app's logger appends to the tab, and when it first
 * appended a line of each.
 *
 * A logcat line of a tag stays out of the buffer from the first append of that
 * tag that [isAppendedTag] takes, because the appended line has the whole text.
 * The older lines of the tag come from logcat: the logger could not append
 * them yet, for example because the app gives it the tab after it creates the
 * tab. So no line is lost, also when the logger never appends.
 *
 * [appended] and [drops] are safe to call from any thread.
 */
internal class AppendedTags(private val isAppendedTag: (String) -> Boolean = { false }) {
    // Only the tags that isAppendedTag takes, so that the tags of other appended lines use no memory.
    private val firstAppendedAt = ConcurrentHashMap<String, Long>()

    // Set while isAppendedTag runs on this thread. A check that logs through the app's
    // logger comes back here, and must not call itself again without end.
    private val checking = ThreadLocal<Boolean>()

    /**
     * Records that the app appended a line of [tag] at [at], in epoch milliseconds.
     * A call from inside [isAppendedTag] records nothing, so its line keeps its
     * logcat copy.
     */
    fun appended(tag: String, at: Long) {
        // After the first line of a tag, a lookup without a lock is enough.
        if (firstAppendedAt[tag] != null || checking.get() == true) return
        checking.set(true)
        try {
            if (isAppendedTagSafely(tag)) firstAppendedAt.putIfAbsent(tag, at)
        } finally {
            checking.remove()
        }
    }

    /** Whether the logcat [line] stays out of the buffer: the app appended a line of its tag at the line's time or before. */
    fun drops(line: LogLine): Boolean {
        val since = firstAppendedAt[line.tag] ?: return false
        return line.at >= since
    }

    // The app's code decides, on the app's thread, and nothing it throws may reach the app's
    // logger, an Error such as the NotImplementedError of TODO() included: the tag's lines then stay.
    private fun isAppendedTagSafely(tag: String): Boolean =
        try {
            isAppendedTag(tag)
        } catch (_: Throwable) {
            false
        }
}
