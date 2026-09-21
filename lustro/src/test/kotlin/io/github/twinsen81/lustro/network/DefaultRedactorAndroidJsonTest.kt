package io.github.twinsen81.lustro.network

import io.github.twinsen81.lustro.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [DefaultRedactor] against the JSON parser it actually runs on. Android's
 * `org.json` reads more than the grammar — it skips comments and lets a repeated
 * key shadow an earlier one — so a body can carry a secret the parse tree never
 * holds. The rest of the redactor's tests run on the JVM's `org.json`, which
 * rejects both shapes, and so can't tell a body that is stored as it arrived from
 * one that is rebuilt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultRedactorAndroidJsonTest {
    private val redactor = DefaultRedactor

    @Test
    fun `a secret under a key a later duplicate shadows is dropped, not stored`() {
        assertEquals(
            """{"a":{"x":1}}""",
            redactor.redactBody("""{"a":{"password":"s3cret"},"a":{"x":1}}""", MediaType.JSON),
        )
        assertEquals(
            """[{"t":{}}]""",
            redactor.redactBody("""[{"t":{"api_key":"s3cret"},"t":{}}]""", MediaType.JSON),
        )
        // The shadowed key is spelled with an escape, as the parse tree would hold it.
        val escaped = redactor.redactBody("""{"a":{"token":"s3cret"},"a":{"x":1}}""", MediaType.JSON)
        assertEquals("""{"a":{"x":1}}""", escaped)
    }

    @Test
    fun `a secret in a comment the parser skips is dropped, not stored`() {
        assertEquals("""{"a":1}""", redactor.redactBody("""{"a":1 /* "password":"s3cret" */}""", MediaType.JSON))
        assertEquals("""{"a":1}""", redactor.redactBody("{\"a\":1 // \"password\":\"s3cret\"\n}", MediaType.JSON))
    }

    @Test
    fun `a body with nothing sensitive in it is stored as it arrived`() {
        val body =
            "{ \"id\": 12345678901234567890, \"price\": 1.10, \"ratio\": 1e2, \"big\": 9007199254740993, " +
                "\"path\": \"a\\/b\", \"caf\\u00e9\": \"cr\\u00e8me\", \"dup\": 1, \"dup\": 2, \"note\": null }"
        assertEquals(body, redactor.redactBody(body, MediaType.JSON))
    }

    @Test
    fun `a sensitive key is still masked`() {
        val out = redactor.redactBody("""{ "price": 1.10, "token": "abc" }""", MediaType.JSON)
        assertTrue("token masked: $out", out.contains(""""token":"[REDACTED]""""))
        assertTrue("secret gone: $out", !out.contains("abc"))
    }
}
