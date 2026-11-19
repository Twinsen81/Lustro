package io.github.twinsen81.lustro

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-logic tests for [DebugRequest.formParams]. */
class DebugRequestFormParamsTest {
    private fun form(body: String?, contentType: String? = "application/x-www-form-urlencoded") =
        DebugRequest(
            path = "save",
            method = "POST",
            body = body?.toByteArray(Charsets.UTF_8),
            contentType = contentType?.let { MediaType.parse(it) },
        ).formParams()

    @Test
    fun `parses each name to its values in order`() {
        assertEquals(
            mapOf("id" to listOf("42"), "tag" to listOf("a", "b"), "note" to listOf("x")),
            form("id=42&tag=a&note=x&tag=b"),
        )
    }

    @Test
    fun `decodes plus as a space and percent escapes as UTF-8`() {
        assertEquals(
            mapOf("title" to listOf("Café au lait"), "q" to listOf("a&b=c"), "emoji" to listOf("🚩")),
            form("title=Caf%C3%A9+au+lait&q=a%26b%3Dc&emoji=%F0%9F%9A%A9"),
        )
    }

    @Test
    fun `follows the rules of the query parameters for names and empty values`() {
        assertEquals(
            mapOf("flag" to listOf(""), "empty" to listOf(""), "name" to listOf("v"), "" to listOf("x")),
            form("flag&empty=&&+name+=v&=x"),
        )
    }

    @Test
    fun `leaves out a pair with a malformed escape`() {
        assertEquals(mapOf("ok" to listOf("1")), form("bad=%zz&ok=1&worse%=2"))
    }

    @Test
    fun `accepts a charset parameter and any case of the type`() {
        assertEquals(mapOf("a" to listOf("1")), form("a=1", "Application/X-WWW-Form-Urlencoded; charset=UTF-8"))
    }

    @Test
    fun `is empty for another content type or no body`() {
        assertEquals(emptyMap<String, List<String>>(), form("""{"a":1}""", "application/json"))
        assertEquals(emptyMap<String, List<String>>(), form("a=1", null))
        assertEquals(emptyMap<String, List<String>>(), form(null))
        assertEquals(emptyMap<String, List<String>>(), form(""))
    }
}
