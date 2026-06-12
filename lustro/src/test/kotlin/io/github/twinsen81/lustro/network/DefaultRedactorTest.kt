package io.github.twinsen81.lustro.network

import io.github.twinsen81.lustro.MediaType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [DefaultRedactor]. Pure JVM logic (org.json + java.net URL codecs),
 * so no Robolectric is required.
 */
class DefaultRedactorTest {
    private val redactor = DefaultRedactor

    // region headers

    @Test
    fun `redacts authorization header`() {
        assertEquals("[REDACTED]", redactor.redactHeaderValue("Authorization", "Bearer abc.def"))
    }

    @Test
    fun `redacts cookie and set-cookie headers case-insensitively`() {
        assertEquals("[REDACTED]", redactor.redactHeaderValue("Cookie", "session=1"))
        assertEquals("[REDACTED]", redactor.redactHeaderValue("set-cookie", "a=b"))
        assertEquals("[REDACTED]", redactor.redactHeaderValue("PROXY-AUTHORIZATION", "x"))
    }

    @Test
    fun `redacts custom auth headers by name fragment`() {
        assertEquals("[REDACTED]", redactor.redactHeaderValue("X-Api-Key", "abc123"))
        assertEquals("[REDACTED]", redactor.redactHeaderValue("X-Auth-Token", "t.t.t"))
        assertEquals("[REDACTED]", redactor.redactHeaderValue("X-Session-Id", "s"))
    }

    @Test
    fun `leaves non-sensitive headers untouched`() {
        assertEquals("application/json", redactor.redactHeaderValue("Content-Type", "application/json"))
        assertEquals("gzip", redactor.redactHeaderValue("Accept-Encoding", "gzip"))
    }

    @Test
    fun `public headers that merely resemble secrets by name pass through`() {
        // CORS flag: name contains "credential" but the value is just a boolean.
        assertEquals("true", redactor.redactHeaderValue("Access-Control-Allow-Credentials", "true"))
        // Server challenges: names contain "auth" but they describe HOW to
        // authenticate; essential when debugging 401/407 responses.
        assertEquals(
            "Bearer realm=\"api\"",
            redactor.redactHeaderValue("WWW-Authenticate", "Bearer realm=\"api\""),
        )
        assertEquals(
            "Basic realm=\"proxy\"",
            redactor.redactHeaderValue("proxy-authenticate", "Basic realm=\"proxy\""),
        )
    }

    @Test
    fun `authenticate exemption does not weaken authorization redaction`() {
        assertEquals("[REDACTED]", redactor.redactHeaderValue("Authorization", "Bearer abc"))
        assertEquals("[REDACTED]", redactor.redactHeaderValue("Proxy-Authorization", "Basic xyz"))
        assertEquals("[REDACTED]", redactor.redactHeaderValue("X-Credential-Id", "c1"))
    }

    // endregion

    // region url query

    @Test
    fun `redacts sensitive url query params and keeps the rest`() {
        val redacted =
            redactor.redactUrl("https://api.example.com/v1/items?token=SEKRIT&page=2&api_key=ABC&q=hello")
        assertTrue("token masked", redacted.contains("token=%5BREDACTED%5D"))
        assertTrue("api_key masked", redacted.contains("api_key=%5BREDACTED%5D"))
        assertTrue("page kept", redacted.contains("page=2"))
        assertTrue("q kept", redacted.contains("q=hello"))
        assertTrue("no plaintext secret", !redacted.contains("SEKRIT"))
    }

    @Test
    fun `recognizes various sensitive query keys`() {
        for (key in listOf("password", "secret", "auth", "session", "bearer", "signature", "sig", "key")) {
            val redacted = redactor.redactUrl("https://x/y?$key=value")
            assertTrue("$key should be masked", redacted.contains("$key=%5BREDACTED%5D"))
        }
    }

    @Test
    fun `url without query is returned unchanged`() {
        val url = "https://api.example.com/v1/items"
        assertEquals(url, redactor.redactUrl(url))
    }

    @Test
    fun `url fragment is preserved`() {
        val redacted = redactor.redactUrl("https://x/y?token=abc#frag")
        assertTrue(redacted.endsWith("#frag"))
        assertTrue(redacted.contains("token=%5BREDACTED%5D"))
    }

    // endregion

    // region json body

    @Test
    fun `redacts sensitive json fields including nested`() {
        val body =
            """{"username":"alice","password":"hunter2","nested":{"access_token":"t"},"items":[{"secret":"s"}]}"""
        val out = JSONObject(redactor.redactBody(body, MediaType.JSON))
        assertEquals("alice", out.getString("username"))
        assertEquals("[REDACTED]", out.getString("password"))
        assertEquals("[REDACTED]", out.getJSONObject("nested").getString("access_token"))
        assertEquals("[REDACTED]", out.getJSONArray("items").getJSONObject(0).getString("secret"))
    }

    @Test
    fun `redacts json when content type is null but body looks like json`() {
        val out = JSONObject(redactor.redactBody("""{"token":"abc","keep":"me"}""", null))
        assertEquals("[REDACTED]", out.getString("token"))
        assertEquals("me", out.getString("keep"))
    }

    @Test
    fun `non-sensitive json is left untouched`() {
        val body = """{"name":"alice","count":3}"""
        val out = JSONObject(redactor.redactBody(body, MediaType.JSON))
        assertEquals("alice", out.getString("name"))
        assertEquals(3, out.getInt("count"))
    }

    // endregion

    // region form body

    @Test
    fun `redacts sensitive form fields and keeps the rest`() {
        val form = "username=alice&password=hunter2&grant_type=password&client_secret=xyz"
        val out =
            redactor.redactBody(form, MediaType.parse("application/x-www-form-urlencoded"))
        assertTrue("password field masked", out.contains("password=%5BREDACTED%5D"))
        assertTrue("client_secret masked", out.contains("client_secret=%5BREDACTED%5D"))
        assertTrue("username kept", out.contains("username=alice"))
        // grant_type=password: the *name* is grant_type (not sensitive), so value kept.
        assertTrue("grant_type kept", out.contains("grant_type=password"))
        assertTrue(!out.contains("hunter2"))
        assertTrue(!out.contains("xyz"))
    }

    @Test
    fun `non-json non-form body is returned unchanged`() {
        val text = "this is just text with a password word"
        assertEquals(text, redactor.redactBody(text, MediaType.TEXT))
    }

    @Test
    fun `empty body returned as-is`() {
        assertEquals("", redactor.redactBody("", MediaType.JSON))
    }

    // endregion

    // region textual fallback (non-strict-JSON / SSE / XML / plain)

    @Test
    fun `NDJSON masks the sensitive token without storing it raw`() {
        // Two concatenated JSON objects do not parse as one value, so the structured
        // path fails and the textual fallback must still mask the token.
        val body = "{\"access_token\":\"x\"}\n{\"n\":1}"
        val out = redactor.redactBody(body, MediaType.JSON)
        assertTrue("token masked", out.contains("\"access_token\":\"[REDACTED]\""))
        assertTrue("value gone", !out.contains("\"x\""))
        assertTrue("non-sensitive field kept", out.contains("\"n\":1"))
    }

    @Test
    fun `SSE data frame token is masked`() {
        val body = "data: {\"token\":\"abc123\"}\n\n"
        val out = redactor.redactBody(body, MediaType.parse("text/event-stream"))
        assertTrue("token masked", out.contains("\"token\":\"[REDACTED]\""))
        assertTrue("secret gone", !out.contains("abc123"))
        assertTrue("framing preserved", out.startsWith("data: {"))
    }

    @Test
    fun `XML element text and sensitive attribute are masked`() {
        val body = "<root><password>hunter2</password><node token=\"abc\" id=\"7\"/></root>"
        val out = redactor.redactBody(body, MediaType.parse("application/xml"))
        assertTrue("element text masked", out.contains("<password>[REDACTED]</password>"))
        assertTrue("attribute masked", out.contains("token=\"[REDACTED]\""))
        assertTrue("password value gone", !out.contains("hunter2"))
        assertTrue("attribute value gone", !out.contains("\"abc\""))
        assertTrue("non-sensitive attribute kept", out.contains("id=\"7\""))
    }

    @Test
    fun `plain text form-style sensitive pair is masked but ordinary prose is untouched`() {
        val body = "note=hello&password=hunter2"
        val out = redactor.redactBody(body, MediaType.TEXT)
        assertTrue("password masked", out.contains("password=[REDACTED]"))
        assertTrue("secret gone", !out.contains("hunter2"))
        assertTrue("non-sensitive pair kept", out.contains("note=hello"))

        val prose = "the quick brown fox has no secrets here"
        assertEquals("plain prose is byte-identical", prose, redactor.redactBody(prose, MediaType.TEXT))
    }

    // endregion
}
