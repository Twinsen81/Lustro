package io.github.twinsen81.lustro.logs.internal

import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppendedLineTest {
    @Test
    fun `a line keeps its text whole up to the cap, with the time, process, and thread it was given`() {
        val message = "x".repeat(10_000)
        val line = AppendedLine.of(Log.DEBUG, "App", message, null, maxChars = 64 * 1024, at = 5L, pid = 1, tid = 2)
        assertEquals(message, line.message)
        assertEquals('D', line.level)
        assertEquals("App", line.tag)
        assertEquals(5L, line.at)
        assertEquals(1, line.pid)
        assertEquals(2, line.tid)
    }

    @Test
    fun `a longer text keeps the first characters and says how many it cut`() {
        assertEquals("abcd… cut (6 more characters)", AppendedLine.cut("abcdefghij", 4))
        // A surrogate pair is never cut in half.
        assertEquals("abc… cut (3 more characters)", AppendedLine.cut("abc😀d", 4))
    }

    @Test
    fun `the cap is 64 K characters, and an eighth of a small buffer`() {
        assertEquals(64 * 1024, AppendedLine.maxChars(4 * 1024 * 1024))
        assertEquals(1_000, AppendedLine.maxChars(16_000))
        assertEquals(1, AppendedLine.maxChars(1))
    }

    @Test
    fun `a throwable adds its stack trace after a line break, as Log does`() {
        val error = IllegalStateException("no key")
        val line = AppendedLine.of(Log.ERROR, "App", "sync failed", error, maxChars = 64 * 1024, at = 0L, pid = 1, tid = 1)
        assertTrue(line.message, line.message.startsWith("sync failed\njava.lang.IllegalStateException: no key\n\tat "))
        assertTrue(line.message, !line.message.endsWith("\n"))

        val alone = AppendedLine.of(Log.ERROR, "App", "", error, maxChars = 64 * 1024, at = 0L, pid = 1, tid = 1)
        assertTrue(alone.message, alone.message.startsWith("java.lang.IllegalStateException: no key\n"))
    }

    @Test
    fun `a throwable that fails to print still gives a line`() {
        val broken =
            object : RuntimeException() {
                override fun toString(): String = throw UnsupportedOperationException("no")
            }
        val line = AppendedLine.of(Log.WARN, "App", "m", broken, maxChars = 1_000, at = 0L, pid = 1, tid = 1)
        assertTrue(line.message, line.message.startsWith("m\n"))
        assertTrue(line.message, line.message.contains("could not be printed"))
    }

    @Test
    fun `a priority maps to logcat's letter, and one out of range to the nearest`() {
        val levels = listOf(0, Log.VERBOSE, Log.DEBUG, Log.INFO, Log.WARN, Log.ERROR, Log.ASSERT, 99).map(AppendedLine::levelOf)
        assertEquals(listOf('V', 'V', 'D', 'I', 'W', 'E', 'F', 'F'), levels)
    }
}
