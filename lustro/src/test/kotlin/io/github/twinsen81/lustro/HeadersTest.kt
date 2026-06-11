package io.github.twinsen81.lustro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic tests for [Headers]. */
class HeadersTest {
    @Test
    fun `builder preserves duplicate names and insertion order`() {
        val headers =
            Headers.Builder()
                .add("Set-Cookie", "a=1")
                .add("Accept", "text/html")
                .add("Set-Cookie", "b=2")
                .build()

        assertEquals(3, headers.size)
        assertEquals(listOf("Set-Cookie", "Accept", "Set-Cookie"), headers.names())
        assertEquals(
            listOf("Set-Cookie" to "a=1", "Accept" to "text/html", "Set-Cookie" to "b=2"),
            headers.toList(),
        )
    }

    @Test
    fun `get is case-insensitive and returns first match`() {
        val headers =
            Headers.Builder()
                .add("Content-Type", "application/json")
                .add("content-type", "text/plain")
                .build()

        assertEquals("application/json", headers.get("CONTENT-TYPE"))
        assertEquals("application/json", headers.get("content-type"))
    }

    @Test
    fun `getAll is case-insensitive and returns all matches in order`() {
        val headers =
            Headers.Builder()
                .add("X-Trace", "first")
                .add("Other", "x")
                .add("x-trace", "second")
                .build()

        assertEquals(listOf("first", "second"), headers.getAll("X-TRACE"))
        assertEquals(emptyList<String>(), headers.getAll("missing"))
    }

    @Test
    fun `get returns null for absent header`() {
        assertNull(Headers.of("A" to "1").get("B"))
    }

    @Test
    fun `builder set replaces all case-insensitive matches and appends`() {
        val headers =
            Headers.Builder()
                .add("Accept", "text/html")
                .add("X-Token", "old1")
                .add("x-token", "old2")
                .set("X-TOKEN", "new")
                .build()

        // The two old X-Token entries are removed; the new one is appended last.
        assertEquals(listOf("Accept", "X-TOKEN"), headers.names())
        assertEquals("new", headers.get("x-token"))
        assertEquals(listOf("new"), headers.getAll("x-token"))
    }

    @Test
    fun `set on absent name just appends`() {
        val h = Headers.Builder().add("A", "1").set("B", "2").build()
        assertEquals(listOf("A", "B"), h.names())
        assertEquals("2", h.get("B"))
        assertEquals(2, h.size)
    }

    @Test
    fun `of preserves order`() {
        val headers = Headers.of("First" to "1", "Second" to "2", "First" to "3")
        assertEquals(listOf("First", "Second", "First"), headers.names())
        assertEquals(listOf("1", "3"), headers.getAll("first"))
    }

    @Test
    fun `from uses map iteration order and one value per name`() {
        val map = linkedMapOf("Z" to "26", "A" to "1", "M" to "13")
        val headers = Headers.from(map)
        assertEquals(listOf("Z", "A", "M"), headers.names())
        assertEquals("1", headers.get("a"))
    }

    @Test
    fun `EMPTY is empty and reused`() {
        assertTrue(Headers.EMPTY.isEmpty())
        assertEquals(0, Headers.EMPTY.size)
        assertSame(Headers.EMPTY, Headers.of())
        assertSame(Headers.EMPTY, Headers.from(emptyMap()))
        assertSame(Headers.EMPTY, Headers.Builder().build())
    }

    @Test
    fun `forEach visits pairs in order`() {
        val seen = ArrayList<Pair<String, String>>()
        Headers.of("A" to "1", "B" to "2").forEach { n, v -> seen.add(n to v) }
        assertEquals(listOf("A" to "1", "B" to "2"), seen)
    }

    @Test
    fun `equals and hashCode are value-based over ordered entries`() {
        val a = Headers.of("A" to "1", "B" to "2")
        val b = Headers.Builder().add("A", "1").add("B", "2").build()
        val different = Headers.of("B" to "2", "A" to "1")

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, different)
    }

    @Test
    fun `isEmpty reflects content`() {
        assertTrue(Headers.of().isEmpty())
        assertFalse(Headers.of("A" to "1").isEmpty())
    }
}
