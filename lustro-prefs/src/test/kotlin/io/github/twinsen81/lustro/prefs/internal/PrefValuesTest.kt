package io.github.twinsen81.lustro.prefs.internal

import io.github.twinsen81.lustro.prefs.internal.PrefValues.appendItem
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The typed parsing of `POST edit`, and the items that the routes write. */
class PrefValuesTest {
    @Test
    fun `a string is a JSON string only`() {
        assertEquals("hello", parse("string", "\"hello\""))
        assertEquals("", parse("string", "\"\""))
        rejects("string", "5", "true", "[\"a\"]")
    }

    @Test
    fun `an int is a whole number in range, also as a string`() {
        assertEquals(5, parse("int", "5"))
        assertEquals(-5, parse("int", "\" -5 \""))
        assertEquals(5, parse("int", "5.0"))
        assertEquals(1000, parse("int", "\"1e3\""))
        assertEquals(Int.MAX_VALUE, parse("int", Int.MAX_VALUE.toString()))
        assertEquals(Int.MIN_VALUE, parse("int", Int.MIN_VALUE.toString()))
        rejects("int", "2147483648", "-2147483649", "5.5", "\"x\"", "\"\"", "true", "[5]")
    }

    @Test
    fun `a long keeps every digit, past a double's precision`() {
        assertEquals(9_007_199_254_740_993L, parse("long", "9007199254740993"))
        assertEquals(Long.MAX_VALUE, parse("long", "\"${Long.MAX_VALUE}\""))
        assertEquals(Long.MIN_VALUE, parse("long", Long.MIN_VALUE.toString()))
        assertEquals(42L, parse("long", "42"))
        rejects("long", "9223372036854775808", "1.5", "\"0x10\"", "false")
    }

    @Test
    fun `a whole number with a huge exponent is refused without writing out its digits`() {
        val start = System.nanoTime()
        rejects("long", "\"1e100000000\"", "\"-1e2147483647\"", "\"1e-100000000\"", "\"1e19\"")
        rejects("int", "\"1e100000000\"")
        assertTrue("took ${(System.nanoTime() - start) / 1_000_000} ms", System.nanoTime() - start < 1_000_000_000L)
        assertEquals(0L, parse("long", "\"0e-100000000\""))
        assertEquals(1_000_000_000_000_000_000L, parse("long", "\"1e18\""))
        assertEquals(1L, parse("long", "\"100e-2\""))
    }

    @Test
    fun `a float is a finite number, or one of the names JSON has no number for`() {
        assertEquals(1.5f, parse("float", "1.5"))
        assertEquals(1.5f, parse("float", "\"1.5\""))
        assertEquals(3f, parse("float", "3"))
        assertTrue((parse("float", "\"NaN\"") as Float).isNaN())
        assertEquals(Float.POSITIVE_INFINITY, parse("float", "\"Infinity\""))
        assertEquals(Float.NEGATIVE_INFINITY, parse("float", "\"-Infinity\""))
        rejects("float", "1e39", "\"1e39\"", "\"abc\"", "true", "[1]")
    }

    @Test
    fun `a boolean is true or false, also as a string in any case`() {
        assertEquals(true, parse("boolean", "true"))
        assertEquals(false, parse("boolean", "false"))
        assertEquals(true, parse("boolean", "\"TRUE\""))
        assertEquals(false, parse("boolean", "\" False \""))
        rejects("boolean", "1", "0", "\"yes\"", "\"\"")
    }

    @Test
    fun `a string set is a JSON array of strings, and a repeat is kept once`() {
        assertEquals(setOf("a", "b"), parse("string_set", "[\"a\",\"b\",\"a\"]"))
        assertEquals(emptySet<String>(), parse("string_set", "[]"))
        rejects("string_set", "\"a\"", "[1]", "[\"a\",null]", "{\"a\":1}")
    }

    @Test
    fun `only the six types are editable`() {
        assertEquals(listOf("string", "int", "long", "float", "boolean", "string_set"), PrefType.EDITABLE.map { it.wireName })
        assertEquals(null, PrefType.forWireName("unknown"))
        assertEquals(null, PrefType.forWireName("String"))
    }

    @Test
    fun `an item has the value and the size of every type`() {
        assertEquals("""{"key":"name","type":"string","value":"Ann \"A\"","bytes":7}""", item("name", "Ann \"A\""))
        assertEquals("""{"key":"n","type":"int","value":-3,"bytes":4}""", item("n", -3))
        assertEquals("""{"key":"n","type":"long","value":9007199254740993,"bytes":8}""", item("n", 9_007_199_254_740_993L))
        assertEquals("""{"key":"n","type":"float","value":0.25,"bytes":4}""", item("n", 0.25f))
        assertEquals("""{"key":"n","type":"boolean","value":true,"bytes":1}""", item("n", true))
        assertEquals("""{"key":"tags","type":"string_set","value":["a","bé","c"],"bytes":5}""", item("tags", setOf("c", "bé", "a")))
    }

    @Test
    fun `a float that is not finite is written as its name, which edit takes back`() {
        assertEquals("""{"key":"n","type":"float","value":"NaN","bytes":4}""", item("n", Float.NaN))
        assertEquals("""{"key":"n","type":"float","value":"-Infinity","bytes":4}""", item("n", Float.NEGATIVE_INFINITY))
        assertEquals(Float.NEGATIVE_INFINITY, parse("float", "\"-Infinity\""))
    }

    @Test
    fun `a masked item has no value, but its type and size`() {
        assertEquals("""{"key":"token","type":"string","bytes":6,"masked":true}""", item("token", "secret", masked = true))
        assertEquals("""{"key":"tags","type":"string_set","bytes":2,"masked":true}""", item("tags", setOf("a", "b"), masked = true))
    }

    @Test
    fun `a long string is cut at the cap, and not inside a surrogate pair`() {
        val json = JSONObject(item("k", "abcdef", maxChars = 4))
        assertEquals("abcd", json.getString("value"))
        assertTrue(json.getBoolean("truncated"))
        assertEquals(6, json.getInt("bytes"))

        val emoji = JSONObject(item("k", "ab😀", maxChars = 3))
        assertEquals("ab", emoji.getString("value"))
        assertEquals(6, emoji.getInt("bytes"))

        assertFalse(JSONObject(item("k", "abcd", maxChars = 4)).has("truncated"))
    }

    @Test
    fun `a string set is cut when its strings together pass the cap`() {
        val cut = JSONObject(item("k", setOf("aaa", "bbb", "ccc"), maxChars = 5))
        assertEquals(JSONArray(listOf("aaa", "bb")).toString(), cut.getJSONArray("value").toString())
        assertTrue(cut.getBoolean("truncated"))

        val exact = JSONObject(item("k", setOf("aaa", "bb", "c"), maxChars = 5))
        assertEquals(JSONArray(listOf("aaa", "bb")).toString(), exact.getJSONArray("value").toString())
        assertTrue("c was left out", exact.getBoolean("truncated"))

        assertFalse(JSONObject(item("k", setOf("aa", "bbb"), maxChars = 5)).has("truncated"))
    }

    @Test
    fun `the size counts UTF-8 bytes`() {
        assertEquals(1L, PrefValues.bytes("a"))
        assertEquals(2L, PrefValues.bytes("é"))
        assertEquals(3L, PrefValues.bytes("€"))
        assertEquals(4L, PrefValues.bytes("😀"))
        assertEquals("\uD83D".toByteArray().size.toLong(), PrefValues.bytes("\uD83D"))
        assertEquals(0L, PrefValues.bytes(null))
    }

    // The value as org.json reads it from a request body.
    private fun parse(type: String, valueJson: String): Any =
        PrefValues.parse(PrefType.forWireName(type)!!, JSONObject("{\"value\":$valueJson}").get("value"))

    private fun rejects(type: String, vararg valuesJson: String) {
        for (valueJson in valuesJson) {
            try {
                val parsed = parse(type, valueJson)
                fail("$type took $valueJson as $parsed")
            } catch (_: BadValueException) {
            }
        }
    }

    private fun item(key: String, value: Any?, masked: Boolean = false, maxChars: Int = Int.MAX_VALUE): String =
        buildString { appendItem(key, value, masked, maxChars) }
}
