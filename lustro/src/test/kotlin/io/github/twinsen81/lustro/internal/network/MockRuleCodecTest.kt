package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [MockRuleCodec], the one place rules are read, written, and checked.
 *
 * The checks exist because [LustroNetworkInterceptor] builds a real OkHttp
 * response from a rule inside the app's own call, so the last test here asserts
 * what really matters: a rule the codec accepts is one that call can serve.
 */
class MockRuleCodecTest {
    private fun parse(json: String, generateMissingId: Boolean = true): MockRuleParseResult =
        MockRuleCodec.parse(JSONObject(json), generateMissingId = generateMissingId)

    private fun invalid(json: String): MockRuleParseResult.Invalid {
        val result = parse(json)
        assertTrue("expected $json to be rejected, got $result", result is MockRuleParseResult.Invalid)
        return result as MockRuleParseResult.Invalid
    }

    private fun valid(json: String): MockRuleImpl {
        val result = parse(json)
        assertTrue("expected $json to be accepted, got $result", result is MockRuleParseResult.Valid)
        return (result as MockRuleParseResult.Valid).rule
    }

    @Test
    fun `a full rule round-trips through the serializer`() {
        val rule = valid(
            """
            {"id":"r1","enabled":false,"name":"My Mock","urlPattern":"regex:.*/v2/.*","method":"POST",
             "statusCode":503,"responseHeaders":{"Content-Type":"application/json","X-Custom":"v"},
             "responseBody":"{\"ok\":false}"}
            """.trimIndent(),
        )
        assertEquals(rule, valid(serializeOne(rule).toString()))
    }

    @Test
    fun `a blank id is generated only when asked for`() {
        assertTrue(valid("""{"urlPattern":"/x"}""").id.isNotBlank())
        assertEquals("id", (MockRuleCodec.parse(JSONObject("""{"urlPattern":"/x"}"""), generateMissingId = false)
            as MockRuleParseResult.Invalid).field)
    }

    @Test
    fun `a blank urlPattern is rejected`() {
        assertEquals("urlPattern", invalid("""{"id":"r","name":"x"}""").field)
    }

    @Test
    fun `a status outside 100 to 599 is rejected`() {
        for (status in listOf(-1, 0, 99, 600, 1000)) {
            assertEquals("statusCode", invalid("""{"urlPattern":"/x","statusCode":$status}""").field)
        }
        for (status in listOf(100, 200, 418, 599)) {
            assertEquals(status, valid("""{"urlPattern":"/x","statusCode":$status}""").statusCode)
        }
    }

    @Test
    fun `a Content-Type that is not a media type is rejected`() {
        val rejection = invalid("""{"urlPattern":"/x","responseHeaders":{"Content-Type":"not a media type"}}""")
        assertEquals("responseHeaders", rejection.field)
        assertTrue(rejection.message, "not a media type" in rejection.message)
    }

    @Test
    fun `header names and values OkHttp rejects are rejected`() {
        assertEquals(
            "responseHeaders",
            invalid("""{"urlPattern":"/x","responseHeaders":{"Bad Name":"v"}}""").field,
        )
        assertEquals(
            "responseHeaders",
            invalid("""{"urlPattern":"/x","responseHeaders":{"X-Custom":"line\nbreak"}}""").field,
        )
    }

    @Test
    fun `hitCount is never read from input`() {
        assertEquals(0, valid("""{"urlPattern":"/x","hitCount":42}""").hitCount)
    }

    @Test
    fun `duplicate header names are folded into one JSON member`() {
        val rule =
            MockRuleImpl(
                id = "r",
                name = "n",
                urlPattern = "/x",
                responseHeaders =
                    Headers.Builder()
                        .add("Set-Cookie", "a=1")
                        .add("Set-Cookie", "b=2")
                        .build(),
            )
        val headers = serializeOne(rule).getJSONObject("responseHeaders")

        assertEquals("a=1, b=2", headers.getString("Set-Cookie"))
    }

    @Test
    fun `every accepted rule can be built into an OkHttp response`() {
        val accepted =
            listOf(
                """{"urlPattern":"/x"}""",
                """{"urlPattern":"/x","statusCode":599,"responseBody":"boom"}""",
                """{"urlPattern":"/x","responseHeaders":{"Content-Type":"text/plain; charset=utf-8"}}""",
                """{"urlPattern":"/x","responseHeaders":{"X-Tab":"a\tb","X-Tilde":"~"}}""",
            )
        for (json in accepted) {
            val rule = valid(json)
            val response = buildLikeTheInterceptor(rule)
            assertEquals(rule.statusCode, response.code)
        }
    }

    @Test
    fun `a rejected header really is one OkHttp would refuse`() {
        val rule =
            MockRuleImpl(
                id = "r",
                name = "n",
                urlPattern = "/x",
                responseHeaders = Headers.of("Bad Name" to "v"),
            )
        assertNotNull(MockRuleCodec.validate(rule))
        val failure = runCatching { buildLikeTheInterceptor(rule) }.exceptionOrNull()
        assertTrue("expected OkHttp to refuse the header, got $failure", failure is IllegalArgumentException)
    }

    @Test
    fun `validate passes a rule the codec produced`() {
        assertNull(MockRuleCodec.validate(valid("""{"urlPattern":"/x","statusCode":201}""")))
    }

    private fun serializeOne(rule: MockRuleImpl): JSONObject =
        JSONArray(MockRuleCodec.toJsonArray(listOf(rule))).getJSONObject(0)

    /** Mirrors [LustroNetworkInterceptor]'s mock branch. */
    private fun buildLikeTheInterceptor(rule: MockRuleImpl): Response {
        val mediaType =
            (rule.responseHeaders.get("Content-Type") ?: "application/json").toMediaType()
        return Response.Builder()
            .request(Request.Builder().url("https://example.com/x").build())
            .protocol(Protocol.HTTP_1_1)
            .code(rule.statusCode)
            .message("Mocked")
            .body(rule.responseBody.toResponseBody(mediaType))
            .apply { rule.responseHeaders.forEach { name, value -> addHeader(name, value) } }
            .build()
    }
}
