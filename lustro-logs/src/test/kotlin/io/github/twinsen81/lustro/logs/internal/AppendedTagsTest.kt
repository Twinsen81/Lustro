package io.github.twinsen81.lustro.logs.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppendedTagsTest {
    @Test
    fun `the drop of a tag starts at its first append that the check takes`() {
        var named = false
        val tags = AppendedTags { named && it == "AppLog" }

        tags.appended("AppLog", at = 5)
        assertFalse("a tag that the check rejects is not recorded", tags.drops(line("AppLog", at = 7)))

        named = true
        tags.appended("AppLog", at = 10)
        assertFalse(tags.drops(line("AppLog", at = 9)))
        assertTrue(tags.drops(line("AppLog", at = 10)))
        assertTrue(tags.drops(line("AppLog", at = 11)))
    }

    @Test
    fun `a check that throws keeps the lines of the tag, and the append does not throw`() {
        val tags = AppendedTags { error("the app's check failed") }

        tags.appended("AppLog", at = 5)

        assertFalse(tags.drops(line("AppLog", at = 6)))
    }

    private fun line(tag: String, at: Long) = LogLine(at, pid = 1, tid = 1, level = 'D', tag = tag, message = "m")
}
