package io.github.twinsen81.lustro.logs.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ThreadtimeParserTest {
    private val utc = TimeZone.getTimeZone("UTC")

    // 2026-10-19 14:22:20.010 UTC
    private val now = 1_792_419_740_010L
    private val parser = ThreadtimeParser(utc) { now }

    @Test
    fun `parses every field of a threadtime line`() {
        val line = parser.parse("10-19 14:22:20.010  4321  4350 D OkHttp  : --> GET https://example.com/a")!!

        assertEquals(now, line.at)
        assertEquals(4321, line.pid)
        assertEquals(4350, line.tid)
        assertEquals('D', line.level)
        assertEquals("OkHttp", line.tag)
        assertEquals("--> GET https://example.com/a", line.message)
    }

    @Test
    fun `a line with its zone, as -v UTC prints it, is read in that zone, whatever the parser's zone`() {
        val madrid = ThreadtimeParser(TimeZone.getTimeZone("Europe/Madrid")) { now }
        val utcLine = madrid.parse("10-19 14:22:20.010 +0000  4321  4350 D OkHttp  : --> GET /a")!!
        assertEquals(now, utcLine.at)
        assertEquals(4321, utcLine.pid)
        assertEquals("OkHttp", utcLine.tag)
        assertEquals("--> GET /a", utcLine.message)
        assertEquals(now, madrid.parse("10-19 16:22:20.010 +0200  4321  4350 D OkHttp  : m")!!.at)
        assertEquals(now, madrid.parse("10-19 09:22:20.010 -0500  4321  4350 D OkHttp  : m")!!.at)
        // Without a zone, the parser's own.
        assertEquals(now, madrid.parse("10-19 16:22:20.010  4321  4350 D OkHttp  : m")!!.at)
    }

    @Test
    fun `a tag longer than the padding, or with spaces and colons, ends at the first colon and space`() {
        assertEquals("ActivityTaskManager", parser.parse("10-19 14:22:20.010  1  2 I ActivityTaskManager: Displayed")!!.tag)
        assertEquals("My Tag", parser.parse("10-19 14:22:20.010  1  2 I My Tag  : m")!!.tag)
        val colon = parser.parse("10-19 14:22:20.010  1  2 W net:retry: attempt 2: timeout")!!
        assertEquals("net:retry", colon.tag)
        assertEquals("attempt 2: timeout", colon.message)
    }

    @Test
    fun `an empty message is kept, with or without the space after the colon`() {
        assertEquals("", parser.parse("10-19 14:22:20.010  1  2 I Tag     : ")!!.message)
        assertEquals("", parser.parse("10-19 14:22:20.010  1  2 I Tag     :")!!.message)
    }

    @Test
    fun `pids of more than five digits and every level parse, and A is read as F`() {
        for (level in "VDIWEF") {
            assertEquals(level, parser.parse("10-19 14:22:20.010 123456 1234567 $level T: m")!!.level)
        }
        assertEquals('F', parser.parse("10-19 14:22:20.010  1  2 A T: m")!!.level)
        assertEquals(123456, parser.parse("10-19 14:22:20.010 123456 1234567 I T: m")!!.pid)
    }

    @Test
    fun `banners, errors, and broken lines are not log lines`() {
        assertNull(parser.parse("--------- beginning of main"))
        assertNull(parser.parse("logcat: unrecognized option '--pid=1'"))
        assertNull(parser.parse(""))
        assertNull(parser.parse("10-19 14:22:20.010  1  2 X T: unknown level"))
        assertNull(parser.parse("10-19 14:22:20.010  1  2 I no separator"))
        assertNull(parser.parse("13-19 14:22:20.010  1  2 I T: month 13"))
        assertNull(parser.parse("10-19 14:22:20,010  1  2 I T: comma"))
    }

    @Test
    fun `a line from December read in January is from the year before`() {
        val january = Calendar.getInstance(utc).apply {
            clear()
            set(2027, Calendar.JANUARY, 1, 0, 0, 5)
        }.timeInMillis
        val line = ThreadtimeParser(utc) { january }.parse("12-31 23:59:59.500  1  2 I T: m")!!
        assertEquals(january - 5_500, line.at)
    }

    @Test
    fun `the format prints the time as the line had it`() {
        val text = "10-19 14:22:20.010"
        val line = parser.parse("$text  1  2 I T: m")
        assertNotNull(line)
        assertEquals(text, ThreadtimeFormat(utc).format(line!!.at))
    }
}
