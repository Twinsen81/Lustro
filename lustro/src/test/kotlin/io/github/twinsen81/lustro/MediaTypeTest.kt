package io.github.twinsen81.lustro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure-logic tests for [MediaType.parse] and the well-known constants. */
class MediaTypeTest {
    @Test
    fun `parses bare type slash subtype`() {
        val mt = MediaType.parse("application/json")!!
        assertEquals("application", mt.type)
        assertEquals("json", mt.subtype)
        assertNull(mt.charsetName)
        assertEquals("application/json", mt.toString())
    }

    @Test
    fun `parses charset parameter and lower-cases it`() {
        val mt = MediaType.parse("text/plain; charset=UTF-8")!!
        assertEquals("text", mt.type)
        assertEquals("plain", mt.subtype)
        assertEquals("utf-8", mt.charsetName)
        assertEquals("utf-8", mt.parameter("charset"))
        // toString preserves the original verbatim.
        assertEquals("text/plain; charset=UTF-8", mt.toString())
    }

    @Test
    fun `parses other parameters preserving non-charset value casing`() {
        val mt = MediaType.parse("multipart/form-data; boundary=AaBbCc; charset=UTF-8")!!
        assertEquals("multipart", mt.type)
        assertEquals("form-data", mt.subtype)
        assertEquals("AaBbCc", mt.parameter("boundary"))
        assertEquals("utf-8", mt.parameter("charset"))
    }

    @Test
    fun `parameter lookup is case-insensitive`() {
        val mt = MediaType.parse("text/plain; Charset=UTF-8")!!
        assertEquals("utf-8", mt.parameter("CHARSET"))
        assertEquals("utf-8", mt.charsetName)
    }

    @Test
    fun `strips surrounding quotes from quoted parameter values`() {
        val mt = MediaType.parse("text/plain; charset=\"utf-8\"")!!
        assertEquals("utf-8", mt.charsetName)
    }

    @Test
    fun `lower-cases type and subtype`() {
        val mt = MediaType.parse("APPLICATION/JSON")!!
        assertEquals("application", mt.type)
        assertEquals("json", mt.subtype)
    }

    @Test
    fun `returns null for malformed input`() {
        assertNull(MediaType.parse(""))
        assertNull(MediaType.parse("application"))
        assertNull(MediaType.parse("/json"))
        assertNull(MediaType.parse("application/"))
        assertNull(MediaType.parse("/"))
    }

    @Test
    fun `equals by canonical form ignores original casing but compares parameters`() {
        val a = MediaType.parse("Application/JSON; charset=UTF-8")!!
        val b = MediaType.parse("application/json; charset=utf-8")!!
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())

        val noCharset = MediaType.parse("application/json")!!
        assertNotEquals(a, noCharset)
    }

    @Test
    fun `JSON constant`() {
        assertEquals("application", MediaType.JSON.type)
        assertEquals("json", MediaType.JSON.subtype)
        assertEquals("utf-8", MediaType.JSON.charsetName)
        assertEquals("application/json; charset=utf-8", MediaType.JSON.toString())
    }

    @Test
    fun `TEXT constant`() {
        assertEquals("text", MediaType.TEXT.type)
        assertEquals("plain", MediaType.TEXT.subtype)
        assertEquals("utf-8", MediaType.TEXT.charsetName)
        assertEquals("text/plain; charset=utf-8", MediaType.TEXT.toString())
    }

    @Test
    fun `OCTET_STREAM constant`() {
        assertEquals("application", MediaType.OCTET_STREAM.type)
        assertEquals("octet-stream", MediaType.OCTET_STREAM.subtype)
        assertNull(MediaType.OCTET_STREAM.charsetName)
        assertEquals("application/octet-stream", MediaType.OCTET_STREAM.toString())
    }
}
