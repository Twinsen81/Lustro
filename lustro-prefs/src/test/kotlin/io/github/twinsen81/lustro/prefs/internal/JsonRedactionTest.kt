package io.github.twinsen81.lustro.prefs.internal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonRedactionTest {
    @Test
    fun `slashes that Android's serializer escapes come back as they are in the file`() {
        // As Android's JSONStringer writes {"avatar":"https://cdn.x/a.png","path":"C:\/","token":"[REDACTED]"}:
        // every slash escaped, and a backslash before a slash as an escaped backslash and an escaped slash.
        val android = """{"avatar":"https:\/\/cdn.x\/a.png","path":"C:\\\/","token":"[REDACTED]"}"""

        val shown = JsonRedaction.unescapeSlashes(android)

        assertEquals("""{"avatar":"https://cdn.x/a.png","path":"C:\\/","token":"[REDACTED]"}""", shown)
        val parsed = JSONObject(shown)
        assertEquals("https://cdn.x/a.png", parsed.getString("avatar"))
        assertEquals("C:\\/", parsed.getString("path"))
    }

    @Test
    fun `a redacted value keeps the URLs of its other fields`() {
        val redacted = JsonRedaction.redact("""{"avatar":"https://cdn.x/a.png","access_token":"t-1"}""")!!

        assertTrue(redacted, redacted.contains("\"https://cdn.x/a.png\""))
        assertEquals("[REDACTED]", JSONObject(redacted).getString("access_token"))
    }
}
