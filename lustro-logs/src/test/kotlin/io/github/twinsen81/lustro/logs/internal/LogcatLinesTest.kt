package io.github.twinsen81.lustro.logs.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class LogcatLinesTest {
    private fun lines(output: String, maxLineBytes: Int = LogcatLines.MAX_LINE_BYTES): List<String> {
        val lines = LogcatLines(output.byteInputStream(), maxLineBytes)
        val read = ArrayList<String>()
        while (lines.next()) read += lines.text()
        return read
    }

    @Test
    fun `lines end only at a line feed, so a lone carriage return stays in its line`() {
        assertEquals(listOf("before\rafter", "next"), lines("before\rafter\nnext\n"))
    }

    @Test
    fun `a carriage return before the line feed goes, and empty lines stay`() {
        assertEquals(listOf("a", "", "b\r\r"), lines("a\r\n\nb\r\r\r\n"))
    }

    @Test
    fun `the last line can have no line feed`() {
        assertEquals(listOf("a", "b"), lines("a\nb"))
        assertEquals(emptyList<String>(), lines(""))
    }

    @Test
    fun `a line longer than the limit keeps its start, and the next line is whole`() {
        assertEquals(listOf("abcd", "ef"), lines("abcdefgh\nef\n", maxLineBytes = 4))
    }

    @Test
    fun `a line longer than the chunk read at once is whole`() {
        val long = "x".repeat(20_000)
        assertEquals(listOf(long.take(LogcatLines.MAX_LINE_BYTES), "y"), lines("$long\ny\n"))
        val fits = "z".repeat(10_000)
        assertEquals(listOf(fits), lines("$fits\n"))
    }
}
