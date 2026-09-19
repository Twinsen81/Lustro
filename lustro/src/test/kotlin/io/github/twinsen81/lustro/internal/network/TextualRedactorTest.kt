package io.github.twinsen81.lustro.internal.network

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TextualRedactor] is a linear-time scanner for four shapes. These tests pin it
 * byte-for-byte to the regexes in its KDoc, which stay here as the reference:
 * quadratic, but fast enough on short inputs. Where a pass follows nesting, which
 * a regex can't, the reference finds the matching bracket or close tag with a
 * plain loop over regex matches.
 */
class TextualRedactorTest {
    @Test
    fun `matches the reference on generated inputs`() {
        val random = Random(20260918)
        repeat(FUZZ_CASES) {
            val input = buildString { repeat(random.nextInt(1, 14)) { append(FRAGMENTS.random(random)) } }
            assertMatchesReference(input)
        }
    }

    @Test
    fun `matches the reference on edge cases`() {
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
            "{\"token\":12,\"id\":-1.5e3,\"key\":true,\"sig\":null}",
            "{\"token\" :\n{\"a\":[1,\"]}\\\"\"]},\"id\":{}}",
            "[\"token\",\":\",\"key\",1]",
            "{\"a\":[\"x\",\":\"],\"token\":\"s\"}",
            "\"token\":,\"key\":}\"sig\":]",
            "\"token\": x\"key\":{\"a\":1",
            "\"token\":{{{[[[",
            "\"token\":\"a\"b\"key\":1",
            "<token><id>1</id></token><key/><sig a=\"1\"/>x",
            "<token><token/>a<token>b</token>c</token>d",
            "<token><b>1</b></token ><token><b>1</b></tokenx>",
            "<ns:token><b/></ns:token></ns>",
            "<token><b>1</b></tok",
            "<token><token <b>x</token>",
        ).forEach(::assertMatchesReference)
    }

    @Test
    fun `a value cut off by the end of the text is masked through to the end`() {
        assertEquals("{\"id\":1,\"token\":\"[R]", redactor.redact("{\"id\":1,\"token\":\"abc"))
        assertEquals("\"token\": \"[R]", redactor.redact("\"token\": \"ab\\"))
        assertEquals("{\"id\":1,\"token\":\"[R]\"", redactor.redact("{\"id\":1,\"token\":12"))
        assertEquals("{\"id\":1,\"token\":\"[R]\"", redactor.redact("{\"id\":1,\"token\":{\"a\":[1,\"x"))
        assertEquals("<node id=\"1\" token=\"[R]", redactor.redact("<node id=\"1\" token=\"abc"))
        assertEquals("<token>[R]", redactor.redact("<token>abc"))
        assertEquals("<token>[R]</tok", redactor.redact("<token>abc</tok"))
        assertEquals("<a><token>[R]", redactor.redact("<a><token><id>1</id><b>2"))
        assertEquals("id=1&token=[R]", redactor.redact("id=1&token=ab"))
    }

    @Test
    fun `a cut-off value of a key that isn't sensitive is kept`() {
        assertEquals("{\"token\":\"[R]\",\"id\":\"abc", redactor.redact("{\"token\":\"s\",\"id\":\"abc"))
        assertEquals("{\"token\":\"[R]\",\"id\":{\"a\":[1", redactor.redact("{\"token\":\"s\",\"id\":{\"a\":[1"))
        assertEquals("<id>abc</i", redactor.redact("<id>abc</i"))
        assertEquals("<id><a>1</a>", redactor.redact("<id><a>1</a>"))
        assertEquals("<node id=\"abc", redactor.redact("<node id=\"abc"))
    }

    @Test
    fun `a sensitive key's number, literal, object, or array becomes a quoted placeholder`() {
        val json = "{\"token\":{\"id\":\"abc\",\"n\":[1,2]},\"api_key\":12345,\"keys\":[\"k1\",\"k2\"],\"sig\":false,\"n\":-1.5e3}"
        assertEquals(
            "{\"token\":\"[R]\",\"api_key\":\"[R]\",\"keys\":\"[R]\",\"sig\":\"[R]\",\"n\":-1.5e3}",
            redactor.redact(json),
        )
        assertEquals("{\"key\" : \"[R]\" }", redactor.redact("{\"key\" : null }"))
        // Brackets and quotes inside the value's strings don't end it.
        assertEquals("{\"token\":\"[R]\",\"id\":1}", redactor.redact("{\"token\":{\"a\":\"}]\\\"{\",\"b\":[\"]\"]},\"id\":1}"))
    }

    @Test
    fun `sensitive keys nested in values that aren't masked are still masked`() {
        assertEquals(
            "{\"user\":{\"name\":\"bob\",\"token\":\"[R]\"},\"list\":[{\"key\":\"[R]\"},{\"id\":2}]}",
            redactor.redact("{\"user\":{\"name\":\"bob\",\"token\":42},\"list\":[{\"key\":[1]},{\"id\":2}]}"),
        )
    }

    @Test
    fun `strings between keys don't hide the next key`() {
        // `,` and `],` read as a key and value; the scan must not skip "token"'s quote.
        assertEquals("{\"a\":[\"x\",\":\"],\"token\":\"[R]\"}", redactor.redact("{\"a\":[\"x\",\":\"],\"token\":\"s\"}"))
        assertEquals("[\" : \", \"id\"] \"key\": \"[R]\"", redactor.redact("[\" : \", \"id\"] \"key\": \"s\""))
    }

    @Test
    fun `a sensitive element is masked whole, children included`() {
        assertEquals("<token>[R]</token>", redactor.redact("<token><id>abc123</id></token>"))
        assertEquals(
            "<key_info a=\"1\">[R]</key_info><id>1</id>",
            redactor.redact("<key_info a=\"1\"><user>bob</user>pin<pin>1234</pin></key_info><id>1</id>"),
        )
        // Nested elements of the same name don't end it early.
        assertEquals("<token>[R]</token>d", redactor.redact("<token><token/>a<token>b</token>c</token>d"))
    }

    @Test
    fun `a self-closing sensitive tag has nothing to mask`() {
        assertEquals("<a><token/><b>1</b><sig x=\"1\" /></a>", redactor.redact("<a><token/><b>1</b><sig x=\"1\" /></a>"))
    }

    @Test
    fun `masks every sensitive element of an XML document, and of any prefix only what the whole shows`() {
        val random = Random(20260919)
        repeat(XML_DOCUMENTS) {
            val generator = XmlGenerator(random)
            val parts = List(random.nextInt(1, 4)) { generator.element(depth = 0) }
            val xml = parts.joinToString("") { it.first }
            val masked = parts.joinToString("") { it.second }
            assertEquals("input: ${xml.escaped()}", masked, redactor.redact(xml))
            for (end in 1 until xml.length) {
                val prefix = xml.substring(0, end)
                val out = redactor.redact(prefix)
                val message = "prefix: ${prefix.escaped()}, out: ${out.escaped()}, whole: ${masked.escaped()}"
                assertTrue(message, masked.startsWith(out) && out.length >= prefix.commonPrefixWith(masked).length)
            }
        }
    }

    @Test
    fun `non-ASCII letters belong to key names, as on Android's Unicode-aware regex engine`() {
        val out = redactor.redact("tokenкey=\"s\" <tokenк>s</tokenк> tokenк=s")
        assertEquals("tokenкey=\"[R]\" <tokenк>[R]</tokenк> tokenк=[R]", out)
    }

    private fun assertMatchesReference(input: String) {
        val expectedKeys = mutableListOf<String>()
        val actualKeys = mutableListOf<String>()
        val expected = Reference({ key -> expectedKeys += key; isSensitive(key) }, "[R]").redact(input)
        val actual = TextualRedactor({ key -> actualKeys += key; isSensitive(key) }, "[R]").redact(input)
        val message = "input: ${input.escaped()}"
        assertEquals(message, expected, actual)
        assertEquals("keys checked for $message", expectedKeys, actualKeys)
    }

    private fun String.escaped(): String = replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    /** The regex formulation of [TextualRedactor]'s four passes. A value may run to the end of the text. */
    private class Reference(
        private val isSensitiveKey: (String) -> Boolean,
        private val placeholder: String,
    ) {
        fun redact(body: String): String {
            var out = redactJson(body)
            out = ATTRIBUTE.replace(out) { m ->
                if (isSensitiveKey(m.groupValues[2])) "${m.groupValues[1]}$placeholder${m.groupValues[3]}" else m.value
            }
            out = redactXml(out)
            out = FORM_KV.replace(out) { m ->
                if (isSensitiveKey(m.groupValues[1])) "${m.groupValues[1]}=$placeholder" else m.value
            }
            return out
        }

        private fun redactJson(text: String): String {
            val out = Replacer(text)
            var pos = 0
            while (true) {
                val pair = JSON_KEY.find(text, pos) ?: break
                val key = pair.groupValues[1]
                val start = pair.range.last + 1
                val string = JSON_STRING.matchAt(text, start)
                val scalar = JSON_SCALAR.matchAt(text, start)
                pos =
                    when {
                        string != null ->
                            if (isSensitiveKey(key)) {
                                val closed = string.groups[1] != null
                                out.replace(start + 1, string.range.last + if (closed) 0 else 1, placeholder)
                                string.range.last + 1
                            } else {
                                // Not consumed: the value's quote may open the next key.
                                start
                            }
                        text.getOrNull(start) == '{' || text.getOrNull(start) == '[' ->
                            if (isSensitiveKey(key)) {
                                containerEnd(text, start).also { out.replace(start, it, "\"$placeholder\"") }
                            } else {
                                start + 1
                            }
                        scalar != null -> {
                            if (isSensitiveKey(key)) out.replace(start, scalar.range.last + 1, "\"$placeholder\"")
                            scalar.range.last + 1
                        }
                        // No value: the regex tries the next start.
                        else -> pair.range.first + 1
                    }
            }
            return out.result()
        }

        private fun containerEnd(text: String, from: Int): Int {
            var depth = 0
            for (token in JSON_TOKEN.findAll(text, from)) {
                when (token.value[0]) {
                    '{', '[' -> depth++
                    '}', ']' -> if (--depth == 0) return token.range.last + 1
                }
            }
            return text.length
        }

        private fun redactXml(text: String): String {
            val out = Replacer(text)
            var pos = 0
            while (true) {
                val open = text.indexOf('<', pos)
                if (open < 0) break
                val leaf = XML_ELEMENT.matchAt(text, open)
                val tag = XML_OPEN_TAG.matchAt(text, open)
                if (leaf != null) {
                    val content = leaf.groups[1]!!.range.last + 1
                    if (isSensitiveKey(leaf.groupValues[2])) {
                        if (leaf.groups[3] != null) {
                            out.replace(content, leaf.range.last + 1, "$placeholder</${leaf.groupValues[3]}>")
                        } else {
                            out.replace(content, leaf.range.last + 1 - leaf.groupValues[4].length, placeholder)
                        }
                    }
                    pos = leaf.range.last + 1
                } else if (tag != null && !tag.value.endsWith("/>") && isSensitiveKey(tag.groupValues[1])) {
                    val close = closeTag(text, tag.groupValues[1], tag.range.last + 1)
                    if (close == null) {
                        out.replace(tag.range.last + 1, text.length, placeholder)
                        break
                    }
                    out.replace(tag.range.last + 1, close.first, placeholder)
                    pos = close.last + 1
                } else {
                    pos = open + 1
                }
            }
            return out.result()
        }

        private fun closeTag(text: String, name: String, from: Int): IntRange? {
            val close = Regex("</${Regex.escape(name)}\\s*>")
            val nested = Regex("<${Regex.escape(name)}(?![\\w.\\-:])([^>]*>)?")
            var depth = 1
            var pos = from
            while (true) {
                val open = text.indexOf('<', pos)
                if (open < 0) return null
                val closeMatch = close.matchAt(text, open)
                val nestedMatch = nested.matchAt(text, open)
                pos = open + 1
                if (closeMatch != null) {
                    if (--depth == 0) return closeMatch.range
                } else if (nestedMatch != null) {
                    if (nestedMatch.groups[1] == null) return null
                    if (!nestedMatch.value.endsWith("/>")) depth++
                    pos = nestedMatch.range.last + 1
                }
            }
        }

        private companion object {
            val JSON_KEY = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*")
            val JSON_STRING = Regex("\"(?:[^\"\\\\]|\\\\.)*(?:(\")|\\\\?\\z)")
            val JSON_SCALAR = Regex("[^\\s,}\\]\"]+")

            // A string (closed, or running to the end), a bracket, or a run of anything else.
            val JSON_TOKEN = Regex("(?s)\"(?:[^\"\\\\]|\\\\.)*(?:\"|\\\\?\\z)|[{}\\[\\]]|[^\"{}\\[\\]]+")
            val ATTRIBUTE = Regex("(([A-Za-z_][\\w.\\-:]*)\\s*=\\s*\")[^\"]*(?:(\")|\\z)")
            val XML_ELEMENT = Regex("(<([A-Za-z_][\\w.\\-:]*)\\b[^>]*>)[^<]*(?:</(\\2)\\s*>|(<(?:/[\\w.\\-:]*\\s*)?)?\\z)")
            val XML_OPEN_TAG = Regex("<([A-Za-z_][\\w.\\-:]*)[^>]*>")
            val FORM_KV = Regex("\\b([A-Za-z_][\\w.\\-]*)=(?!\")([^&;\\s]*)")
        }
    }

    /** Copies a text with ranges replaced, in order. */
    private class Replacer(private val text: String) {
        private val out = StringBuilder()
        private var copied = 0

        fun replace(start: Int, end: Int, replacement: String) {
            out.append(text, copied, start).append(replacement)
            copied = end
        }

        fun result(): String = out.append(text, copied, text.length).toString()
    }

    /** Random elements, each paired with what [redactor] turns it into. */
    private class XmlGenerator(private val random: Random) {
        fun element(depth: Int): Pair<String, String> {
            val name = NAMES.random(random)
            val open = "<$name" + if (random.nextInt(3) == 0) " id=\"${random.nextInt(100)}\"" else ""
            // A sensitive self-closing tag is left out: at the end of the text,
            // the leaf rule masks what follows it.
            if (!isSensitive(name) && random.nextInt(4) == 0) return "$open/>".let { it to it }
            val content = StringBuilder()
            val maskedContent = StringBuilder()
            repeat(random.nextInt(if (depth < 3) 4 else 2)) {
                val (child, maskedChild) =
                    if (depth < 3 && random.nextBoolean()) element(depth + 1) else TEXTS.random(random).let { it to it }
                content.append(child)
                maskedContent.append(maskedChild)
            }
            val close = "</$name>"
            return "$open>$content$close" to "$open>${if (isSensitive(name)) "[R]" else maskedContent}$close"
        }

        private companion object {
            val NAMES = listOf("token", "api_key", "ns:token", "keyId", "sig", "a", "item", "ns:item", "b-c", "d.e")
            val TEXTS = listOf("x", "hello world", "1234", " ", "token", "a/b", "key: 1", "\n  ")
        }
    }

    private companion object {
        const val FUZZ_CASES = 50_000
        const val XML_DOCUMENTS = 1_000

        // ASCII only: the JVM's regex `\w`/`\s` are ASCII-only, while the scanner
        // (like Android's engine) is Unicode-aware; the two agree on ASCII.
        val FRAGMENTS =
            listOf(
                "\"", "\\", ":", "=", "<", ">", "/", "&", ";", " ", "\n", "\r", "\t", ".", "-", "_", "1", "a", "Z",
                "token", "key", "id", "\"token\"", "\"id\"", "=\"", "\":\"", "\": \"", "</", "<token>", "</token>",
                "<id>", "</id>", "<a:token>", "</a>", "password=", "x.token", "\\\"", "k:v", "sig", "=&", "  ",
                "{", "}", "[", "]", ",", "12", "true", "\"token\":", "\"id\":", "\":{", "\":[", "/>", "<token/>",
                "<a>", "</token >", "<token ",
            )

        val redactor = TextualRedactor({ isSensitive(it) }, "[R]")

        fun isSensitive(key: String): Boolean = key.lowercase().let { "token" in it || "key" in it || "sig" in it }
    }
}
