package io.github.twinsen81.lustro.internal.network

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [TextualRedactor] is a linear-time scanner for four regex shapes. These tests
 * pin it byte-for-byte to the regexes themselves, which stay here as the
 * reference: quadratic, but fast enough on short inputs.
 */
class TextualRedactorTest {
    @Test
    fun `matches the regex reference on generated inputs`() {
        val random = Random(20260918)
        repeat(FUZZ_CASES) {
            val input = buildString { repeat(random.nextInt(1, 14)) { append(FRAGMENTS.random(random)) } }
            assertMatchesReference(input)
        }
    }

    @Test
    fun `matches the regex reference on edge cases`() {
        listOf(
            "",
            "\"token\"",
            "\"token\":\"",
            "\"token\" : \"a\\\"b\" \"k\":\"v\"",
            "\"a\":\"x\\\n\", \"token\":\"s\"",
            "\"a\\\"\\\"\\\":\"x\"",
            "token=\"a\" id=\"b\" key =  \"c\"",
            "1token=\"a\" -key=\"b\" _secret=\"c\"",
            "a.b.c.token=\"v",
            "<token>s</token><id>1</id><key a=\"1\">v</key >",
            "<soap:Envelope>s</soap><ns:token>v</ns:token><a->x</a-><a <b>x</a>",
            "<token>a<b>c</b></token>",
            "a=1&token=2;key=3 password=\"x\" pwd= sig=",
            "x.token=1&a-key=2&1token=3&b_key=4",
            "\"id\":\"1\",\"token\":\"abc",
            "\"token\" : \"ab\\",
            "\"token\":\"ab\\\n",
            "\"a\":\"x\\\n\", \"token\":\"s",
            "<n id=\"1\" token=\"abc",
            "<token>abc",
            "<token>abc<",
            "<token>abc</tok",
            "<token>abc</token \n",
            "<token>abc<b",
            "<a:token>v</a",
            "<token->x",
            "<a <token>s",
            "a=1&token=ab",
        ).forEach(::assertMatchesReference)
    }

    @Test
    fun `a value cut off by the end of the text is masked through to the end`() {
        assertEquals("{\"id\":1,\"token\":\"[R]", redactor.redact("{\"id\":1,\"token\":\"abc"))
        assertEquals("\"token\": \"[R]", redactor.redact("\"token\": \"ab\\"))
        assertEquals("<node id=\"1\" token=\"[R]", redactor.redact("<node id=\"1\" token=\"abc"))
        assertEquals("<token>[R]", redactor.redact("<token>abc"))
        assertEquals("<token>[R]</tok", redactor.redact("<token>abc</tok"))
        assertEquals("id=1&token=[R]", redactor.redact("id=1&token=ab"))
    }

    @Test
    fun `a cut-off value of a key that isn't sensitive is kept`() {
        assertEquals("{\"token\":\"[R]\",\"id\":\"abc", redactor.redact("{\"token\":\"s\",\"id\":\"abc"))
        assertEquals("<id>abc</i", redactor.redact("<id>abc</i"))
        assertEquals("<node id=\"abc", redactor.redact("<node id=\"abc"))
    }

    @Test
    fun `non-ASCII letters belong to key names, as on Android's Unicode-aware regex engine`() {
        val out = redactor.redact("tokenкey=\"s\" <tokenк>s</tokenк> tokenк=s")
        assertEquals("tokenкey=\"[R]\" <tokenк>[R]</tokenк> tokenк=[R]", out)
    }

    private fun assertMatchesReference(input: String) {
        val expectedKeys = mutableListOf<String>()
        val actualKeys = mutableListOf<String>()
        val expected = RegexReference({ key -> expectedKeys += key; isSensitive(key) }, "[R]").redact(input)
        val actual = TextualRedactor({ key -> actualKeys += key; isSensitive(key) }, "[R]").redact(input)
        val message = "input: ${input.escaped()}"
        assertEquals(message, expected, actual)
        assertEquals("keys checked for $message", expectedKeys, actualKeys)
    }

    private fun String.escaped(): String = replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    /** The regex formulation of [TextualRedactor]'s four passes. A value may run to the end of the text. */
    private class RegexReference(
        private val isSensitiveKey: (String) -> Boolean,
        private val placeholder: String,
    ) {
        fun redact(body: String): String {
            var out = body
            out = JSON_KV.replace(out) { m ->
                if (isSensitiveKey(m.groupValues[2])) "${m.groupValues[1]}$placeholder${m.groupValues[3]}" else m.value
            }
            out = ATTRIBUTE.replace(out) { m ->
                if (isSensitiveKey(m.groupValues[2])) "${m.groupValues[1]}$placeholder${m.groupValues[3]}" else m.value
            }
            out = XML_ELEMENT.replace(out) { m ->
                when {
                    !isSensitiveKey(m.groupValues[2]) -> m.value
                    m.groups[3] != null -> "${m.groupValues[1]}$placeholder</${m.groupValues[3]}>"
                    else -> "${m.groupValues[1]}$placeholder${m.groupValues[4]}"
                }
            }
            out = FORM_KV.replace(out) { m ->
                if (isSensitiveKey(m.groupValues[1])) "${m.groupValues[1]}=$placeholder" else m.value
            }
            return out
        }

        private companion object {
            val JSON_KV = Regex("(\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\")(?:[^\"\\\\]|\\\\.)*(?:(\")|\\\\?\\z)")
            val ATTRIBUTE = Regex("(([A-Za-z_][\\w.\\-:]*)\\s*=\\s*\")[^\"]*(?:(\")|\\z)")
            val XML_ELEMENT = Regex("(<([A-Za-z_][\\w.\\-:]*)\\b[^>]*>)[^<]*(?:</(\\2)\\s*>|(<(?:/[\\w.\\-:]*\\s*)?)?\\z)")
            val FORM_KV = Regex("\\b([A-Za-z_][\\w.\\-]*)=(?!\")([^&;\\s]*)")
        }
    }

    private companion object {
        const val FUZZ_CASES = 50_000

        // ASCII only: the JVM's regex `\w`/`\s` are ASCII-only, while the scanner
        // (like Android's engine) is Unicode-aware; the two agree on ASCII.
        val FRAGMENTS =
            listOf(
                "\"", "\\", ":", "=", "<", ">", "/", "&", ";", " ", "\n", "\r", "\t", ".", "-", "_", "1", "a", "Z",
                "token", "key", "id", "\"token\"", "\"id\"", "=\"", "\":\"", "\": \"", "</", "<token>", "</token>",
                "<id>", "</id>", "<a:token>", "</a>", "password=", "x.token", "\\\"", "k:v", "sig", "=&", "  ",
            )

        val redactor = TextualRedactor({ isSensitive(it) }, "[R]")

        fun isSensitive(key: String): Boolean = key.lowercase().let { "token" in it || "key" in it || "sig" in it }
    }
}
