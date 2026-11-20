package io.github.twinsen81.lustro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class JsonTest {
    @Test
    fun `reads every kind of value, and keeps the order of the members`() {
        val value = Json.parse(""" { "b": [1, -2.5e3, true, false, null], "a": {"x": "\"\\\/\b\f\n\r\t\u00e9\ud83d\ude00"} } """)
        val members = value as Map<*, *>
        assertEquals(listOf("b", "a"), members.keys.toList())
        val items = members["b"] as List<*>
        assertEquals(listOf(JsonNumber("1"), JsonNumber("-2.5e3"), true, false), items.take(4))
        assertSame(JsonNull, items[4])
        assertEquals("\"\\/\b\u000C\n\r\té😀", (members["a"] as Map<*, *>)["x"])
    }

    @Test
    fun `writes compact and pretty text that reads back the same`() {
        val text = """{"a":[1,{"b":null}],"c":{},"d":[],"e":"q\"\n"}"""
        val value = Json.parse(text)
        assertEquals(text, Json.write(value, pretty = false))
        assertEquals(
            """
            {
              "a": [
                1,
                {
                  "b": null
                }
              ],
              "c": {},
              "d": [],
              "e": "q\"\n"
            }
            """.trimIndent(),
            Json.write(value, pretty = true),
        )
    }

    @Test
    fun `refuses text that is not JSON, and says where`() {
        val cases =
            mapOf(
                "" to "unexpected end of the text at character 1",
                "{} x" to "unexpected text after the value at character 4",
                "{\"a\" 1}" to "expected ':' at character 6",
                "[1,]" to "unexpected character ']' at character 4",
                "\"a" to "unterminated string at character 3",
                "\"\\x\"" to "bad escape \\x at character 4",
                "01" to "unexpected text after the value at character 2",
                "1." to "bad number at character 3",
                "tru" to "unexpected character 't' at character 1",
                "{'a':1}" to "expected a member name at character 2",
                "\"a\u0001\"" to "control character in a string at character 3",
            )
        for ((text, message) in cases) {
            val thrown = assertThrows(text, JsonException::class.java) { Json.parse(text) }
            assertEquals(text, message, thrown.message)
        }
        assertThrows(JsonException::class.java) { Json.parse("[".repeat(300) + "]".repeat(300)) }
    }

    @Test
    fun `a number is a long only when it is a whole number in range`() {
        assertEquals(42L, JsonNumber("42").toLongOrNull())
        assertEquals(-7L, JsonNumber("-7.000").toLongOrNull())
        assertEquals(1000L, JsonNumber("1e3").toLongOrNull())
        assertEquals(Long.MAX_VALUE, JsonNumber("9223372036854775807").toLongOrNull())
        assertNull(JsonNumber("9223372036854775808").toLongOrNull())
        assertNull(JsonNumber("1.5").toLongOrNull())
        assertNull(JsonNumber("1e999999999").toLongOrNull())
        assertNull(JsonNumber("1.5e-999999999").toLongOrNull())
        assertEquals(0L, JsonNumber("0e-999999999").toLongOrNull())
    }
}
