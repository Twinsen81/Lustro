package io.github.twinsen81.lustro.network

import io.github.twinsen81.lustro.MediaType
import java.math.BigDecimal
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [DefaultRedactor]. Pure JVM logic (org.json + java.net URL codecs),
 * so no Robolectric is required.
 */
class DefaultRedactorTest {
    private val redactor = DefaultRedactor


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
    fun `sensitive names match in any case`() {
        assertEquals("[REDACTED]", redactor.redactHeaderValue("X-API-KEY", "v"))
        assertEquals("[REDACTED]", redactor.redactHeaderValue("x-SeSsIoN-id", "v"))
        // Lowercasing turns the Kelvin sign into "k".
        assertEquals("[REDACTED]", redactor.redactHeaderValue("X-\u212Aey", "v"))
        assertEquals("v", redactor.redactHeaderValue("X-Request-Id", "v"))
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
        assertEquals(body, redactor.redactBody(body, MediaType.JSON))
    }

    // An inspector exists for sessions about number precision, repeated keys, and
    // what a server really sent, so a body with nothing to mask must be stored
    // exactly as it arrived — not as org.json would write it back out.
    @Test
    fun `json with nothing to mask is stored byte for byte`() {
        val body =
            "{ \"id\": 12345678901234567890, \"price\": 1.10, \"ratio\": 1e2, \"big\": 9007199254740993, " +
                "\"path\": \"a\\/b\", \"caf\\u00e9\": \"cr\\u00e8me\", \"dup\": 1, \"dup\": 2, \"note\": null }"
        assertEquals(body, redactor.redactBody(body, MediaType.JSON))

        val array = "[\n  1.0,\n  {\"a\": [2e1]}\n]"
        assertEquals(array, redactor.redactBody(array, MediaType.JSON))
        assertEquals(array, redactor.redactBody(array, null))
    }

    // A body that is a bare JSON string has no key to judge it by, and it can still
    // carry a secret. It keeps taking the textual path, which masks one.
    @Test
    fun `a json body that is just a string is still masked`() {
        val out = redactor.redactBody("\"https://host/file?token=s3cret\"", MediaType.JSON)
        assertTrue("masked: $out", out.startsWith("\"https://host/file?token=[REDACTED]"))
        assertTrue("secret gone: $out", !out.contains("s3cret"))
        assertEquals("42", redactor.redactBody("42", MediaType.JSON))
    }

    // Masking a value means rebuilding the text from the parse tree, so a body that
    // does contain a sensitive key is re-serialized. Pinned so the cost of the
    // structured path stays visible: it applies to these bodies and no others.
    @Test
    fun `json with a sensitive key is re-serialized`() {
        val out = redactor.redactBody("""{ "price": 1.10, "token": "abc" }""", MediaType.JSON)
        assertTrue("token masked", out.contains(""""token":"[REDACTED]""""))
        assertTrue("secret gone", !out.contains("abc"))
        // org.json wrote the tree back out: the spacing and the trailing zero are gone.
        assertTrue("re-serialized: $out", out.contains(""""price":1.1"""))
    }

    // The decision to store a body as it arrived is made on its TEXT, not on the
    // parse tree: org.json on Android skips comments and lets a repeated key shadow
    // an earlier one, so a secret can sit in the body without ever reaching the
    // tree. Storing such a body verbatim would publish it. These assertions hold on
    // either parser — the JVM's org.json rejects both shapes, which sends them down
    // the textual path — so they pin the property, not the route taken to it.
    @Test
    fun `a secret the parser would drop is never stored`() {
        val bodies =
            listOf(
                """{"a":{"password":"s3cret"},"a":{"x":1}}""",
                """[{"t":{"api_key":"s3cret"},"t":{}}]""",
                """{"a":1 /* "password":"s3cret" */}""",
                "{\"a\":1 // \"password\":\"s3cret\"\n}",
                """{"a":1, "b":{"deep":[{"secret":"s3cret"}]}, "b":2}""",
            )
        for (body in bodies) {
            val out = redactor.redactBody(body, MediaType.JSON)
            assertTrue("secret stored for $body -> $out", !out.contains("s3cret"))
        }
    }

    // A quoted value is not a key: a body that merely mentions a sensitive name is
    // still stored as it arrived, as the structured path never masked it either.
    @Test
    fun `a sensitive name in a value does not force a rewrite`() {
        val body = """{ "a": "password", "b": ["token", {"c": "api_key"}] }"""
        assertEquals(body, redactor.redactBody(body, MediaType.JSON))
    }



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
    fun `JSON cut off partway through a secret masks the part that arrived`() {
        val body = "{\"items\":[{\"id\":1}],\"password\":\"hunter2-hun"
        assertEquals("{\"items\":[{\"id\":1}],\"password\":\"[REDACTED]", redactor.redactBody(body, MediaType.JSON))
    }

    @Test
    fun `SSE frame whose token has only partly arrived is masked`() {
        val body = "data: {\"n\":1}\n\ndata: {\"token\":\"abc"
        val out = redactor.redactBody(body, MediaType.parse("text/event-stream"))
        assertEquals("data: {\"n\":1}\n\ndata: {\"token\":\"[REDACTED]", out)
    }

    @Test
    fun `JSON cut off at the cap masks every sensitive value, whatever its type`() {
        val body = "{\"session\":{\"id\":\"abc123\",\"user\":\"bob\"},\"api_key\":12345,\"keys\":[\"k1\",\"k2\"],\"n\":"
        assertEquals(
            "{\"session\":\"[REDACTED]\",\"api_key\":\"[REDACTED]\",\"keys\":\"[REDACTED]\",\"n\":",
            redactor.redactBody(body, MediaType.JSON),
        )
    }

    @Test
    fun `NDJSON and SSE frames mask numbers and objects under sensitive keys`() {
        assertEquals(
            "{\"api_key\":\"[REDACTED]\"}\n{\"n\":1}",
            redactor.redactBody("{\"api_key\":12345}\n{\"n\":1}", MediaType.JSON),
        )
        assertEquals(
            "data: {\"auth\":\"[REDACTED]\"}\n\n",
            redactor.redactBody("data: {\"auth\":{\"code\":\"xyz\"}}\n\n", EVENT_STREAM),
        )
    }

    @Test
    fun `JSON that takes the textual path is masked like JSON that parses`() {
        val random = Random(20260919)
        repeat(JSON_DOCUMENTS) {
            val (json, masked) = JsonGenerator(random).document()
            // The generator masks what the structured path masks.
            assertSimilarJson(json, masked, redactor.redactBody(json, MediaType.JSON))
            if (json == masked) {
                // Nothing to mask (the generator's masked copy is its input) -> stored verbatim.
                assertEquals(json, redactor.redactBody(json, MediaType.JSON))
            } else {
                // A sensitive key anywhere in the TEXT rules the verbatim path out,
                // even where a repeated key shadows the whole subtree holding it, so
                // the parse can't drop a secret into a body that is then stored.
                val shadowed = "{\"a\":$json,\"a\":{}}"
                assertTrue("stored as-is: $shadowed", redactor.redactBody(shadowed, MediaType.JSON) != shadowed)
            }
            // Two NDJSON lines or SSE frames don't parse as one value.
            assertEquals(json, "$masked\n$masked", redactor.redactBody("$json\n$json", MediaType.JSON))
            assertEquals(json, "data: $masked\n\n", redactor.redactBody("data: $json\n\n", EVENT_STREAM))
            // Cut off anywhere, as at the capture cap, it shows no more than the whole.
            for (end in 1 until json.length) {
                val prefix = json.substring(0, end)
                val out = redactor.redactBody(prefix, MediaType.JSON)
                assertTrue(
                    "prefix: $prefix, out: $out, whole: $masked",
                    masked.startsWith(out) && out.length >= prefix.commonPrefixWith(masked).length,
                )
            }
        }
    }

    @Test
    fun `XML masks a sensitive element's children`() {
        val xml = MediaType.parse("application/xml")
        assertEquals("<session>[REDACTED]</session>", redactor.redactBody("<session><id>abc123</id></session>", xml))
        assertEquals(
            "<credentials>[REDACTED]</credentials><n>1</n>",
            redactor.redactBody("<credentials><user>bob</user><pin>1234</pin></credentials><n>1</n>", xml),
        )
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

    // Redaction runs on the app's HTTP thread. A backtracking regex needs minutes
    // on these bodies (and overflows the JVM's stack on long strings); a linear
    // scan needs milliseconds.
    @Test(timeout = 10_000)
    fun `text bodies at the capture cap that defeat backtracking redact quickly and unchanged`() {
        val size = 256 * 1024
        listOf(
            "a".repeat(size),
            "a.".repeat(size / 2),
            "<a ".repeat(size / 3) + ">",
            "{\"note\":\"" + "\\\"".repeat(size / 2),
            "\"k\":\"" + "v".repeat(size),
        ).forEach { body -> assertEquals(body, redactor.redactBody(body, MediaType.TEXT)) }
    }

    @Test(timeout = 10_000)
    fun `nested sensitive values at the capture cap redact quickly`() {
        val size = 256 * 1024
        // Framed as SSE, so the JSON bodies take the textual path.
        listOf(
            "data: {\"token\":" + "{".repeat(size) to "data: {\"token\":\"[REDACTED]\"",
            "data: {\"token\":[" + "\"\\\"]\",".repeat(size / 6) to "data: {\"token\":\"[REDACTED]\"",
            "<token>" + "<token>".repeat(size / 7) to "<token>[REDACTED]",
            "<token>" + "<token ".repeat(size / 7) to "<token>[REDACTED]",
        ).forEach { (body, expected) -> assertEquals(expected, redactor.redactBody(body, EVENT_STREAM)) }
        listOf(
            "data: " + "{\"a\":".repeat(size / 5),
            "data: [" + "\"x\",\":\",".repeat(size / 8),
            "<token></tok".repeat(size / 12),
            "<a><b>".repeat(size / 6),
            "<a/>".repeat(size / 4),
        ).forEach { body -> redactor.redactBody(body, EVENT_STREAM) }
    }

    @Test(timeout = 10_000)
    fun `JSON truncated at the capture cap is masked quickly`() {
        // A base64url blob reads as one long identifier run.
        val blob = "aGVsbG8_d29ybGQ-".repeat(4 * 1024)
        val body =
            buildString {
                append("{\"items\":[")
                var i = 0
                while (length < 256 * 1024) append("{\"id\":$i,\"access_token\":\"tok-${i++}\",\"thumb\":\"$blob\"},")
                append("{\"id\":$i,\"thumb\":\"${blob.take(1000)}")
            }
        val out = redactor.redactBody(body, MediaType.JSON)
        assertTrue("every token masked", !out.contains("tok-"))
        assertTrue("masked in place", out.startsWith("{\"items\":[{\"id\":0,\"access_token\":\"[REDACTED]\",\"thumb\":\"$blob\"},"))
    }

    private fun assertSimilarJson(input: String, expected: String, actual: String) {
        assertEquals("input: $input", parsed(expected), parsed(actual))
    }

    // Keys in any order, numbers by value: the structured path reorders and reformats them.
    private fun parsed(json: String): Any? = canonical(JSONTokener(json).nextValue())

    private fun canonical(value: Any?): Any? =
        when (value) {
            is JSONObject -> value.keys().asSequence().associateWith { canonical(value.get(it)) }
            is JSONArray -> List(value.length()) { canonical(value.get(it)) }
            is Number -> BigDecimal(value.toString()).stripTrailingZeros()
            else -> value
        }

    /** Random JSON documents, each paired with what the redactor turns it into. */
    private class JsonGenerator(private val random: Random) {
        fun document(): Pair<String, String> = if (random.nextBoolean()) obj(0) else array(0)

        private fun value(depth: Int): Pair<String, String> =
            when (random.nextInt(if (depth < 3) 4 else 2)) {
                0 -> STRINGS.random(random).let { it to it }
                1 -> SCALARS.random(random).let { it to it }
                2 -> obj(depth + 1)
                else -> array(depth + 1)
            }

        private fun obj(depth: Int): Pair<String, String> {
            val json = StringBuilder("{")
            val masked = StringBuilder("{")
            (SENSITIVE_KEYS + KEYS).shuffled(random).take(random.nextInt(5)).forEachIndexed { i, key ->
                val head = (if (i > 0) "," else "") + space() + "\"$key\"" + space() + ":" + space()
                val (value, maskedValue) = value(depth)
                json.append(head).append(value)
                masked.append(head).append(if (key in SENSITIVE_KEYS) "\"[REDACTED]\"" else maskedValue)
            }
            val tail = space() + "}"
            return json.append(tail).toString() to masked.append(tail).toString()
        }

        private fun array(depth: Int): Pair<String, String> {
            val json = StringBuilder("[")
            val masked = StringBuilder("[")
            repeat(random.nextInt(4)) { i ->
                val head = (if (i > 0) "," else "") + space()
                val (value, maskedValue) = value(depth)
                json.append(head).append(value)
                masked.append(head).append(maskedValue)
            }
            val tail = space() + "]"
            return json.append(tail).toString() to masked.append(tail).toString()
        }

        private fun space(): String = SPACES.random(random)

        private companion object {
            val SENSITIVE_KEYS = listOf("token", "api_key", "session", "Authorization", "password", "keys", "sig")
            val KEYS = listOf("id", "name", "n", "items", "note", "data", "value")

            // Strings that look like keys, brackets, or separators, and escapes. No
            // `=` or `<`: the form, attribute, and XML passes would mask those in
            // strings, where the structured path keeps them.
            val STRINGS =
                listOf(
                    """""""", """"abc"""", """"token"""", """":"""", """" : """", """"}]{[,"""",
                    """"a\"b"""", """"back\\slash"""", """"line\nbreak"""", """"\u00e9t\u00e9"""",
                    """"\"token\": 1"""", """"x: {"""", """"a\/b"""", """"api_key"""",
                )
            val SCALARS = listOf("0", "-1", "12.5", "1e3", "-2.5E-3", "true", "false", "null", "12345678901234")
            val SPACES = listOf("", "", " ", "\n  ", "\t")
        }
    }

    private companion object {
        const val JSON_DOCUMENTS = 500
        val EVENT_STREAM = MediaType.parse("text/event-stream")
    }
}
