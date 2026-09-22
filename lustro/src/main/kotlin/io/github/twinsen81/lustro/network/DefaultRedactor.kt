package io.github.twinsen81.lustro.network

import io.github.twinsen81.lustro.MediaType
import io.github.twinsen81.lustro.internal.network.TextualRedactor
import java.net.URLDecoder
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * The default [Redactor] applied at capture time.
 *
 * Redacts:
 * - Header values to `[REDACTED]` for `Authorization`, `Proxy-Authorization`,
 *   `Cookie`, `Set-Cookie`, and any header whose NAME contains a sensitive token
 *   (e.g. `X-Api-Key`, `X-Auth-Token`), so custom auth headers are covered too.
 *   Well-known PUBLIC headers whose names merely resemble a secret
 *   (`Access-Control-Allow-Credentials`, `WWW-Authenticate`,
 *   `Proxy-Authenticate`) are exempt — they carry CORS metadata / server
 *   challenges, not credentials.
 * - URL query parameters, JSON object fields, and form fields whose name
 *   contains a sensitive token (`token`, `key`, `secret`, `password`, `passwd`,
 *   `pwd`, `auth`, `access_token`, `refresh_token`, `api_key`, `apikey`,
 *   `client_secret`, `session`, `credential`, `bearer`, `signature`, `sig`).
 * - Sensitive values in any other captured text body — SSE, XML, plain text, and
 *   JSON that does not parse as a single object/array (NDJSON / concatenated
 *   frames) — via a framing-agnostic, key-name-based fallback. As on the
 *   structured path, a sensitive key's whole value is masked, whether a string,
 *   a number, or a nested object or array, and so is everything inside a
 *   sensitive XML element.
 *
 * A captured body can end partway through a value, when it's cut off at the
 * capture cap or captured while a stream is still arriving. A sensitive value
 * cut off that way is masked through to the end of the body.
 *
 * What it masks is never stored. Every rule above keys off a NAME, though, so
 * this is a reduction of exposure and not a guarantee: a credential that no
 * sensitive name points at is stored as it arrived. Known gaps are listed under
 * "Capture-time redaction" in `SECURITY.md`; consumers whose traffic needs more
 * pass their own [Redactor] to `NetworkDebugTab.create(...)`.
 */
public object DefaultRedactor : Redactor {
    private const val PLACEHOLDER = "[REDACTED]"

    // What hasOnlyInsensitiveKeys expects the token at hand to be.
    private const val EXPECT_VALUE = 0
    private const val EXPECT_KEY = 1
    private const val EXPECT_SEPARATOR = 2

    private val JSON_LITERALS = listOf("true", "false", "null")

    private val textualRedactor = TextualRedactor(isSensitiveKey = ::isSensitiveKey, placeholder = PLACEHOLDER)

    private val SENSITIVE_HEADERS =
        setOf("authorization", "proxy-authorization", "cookie", "set-cookie")

    // Well-known public headers whose NAMES contain a sensitive fragment but
    // whose values are not secrets: the CORS allow-credentials flag (literally
    // "true"/"false") and the WWW-/Proxy-Authenticate server challenges (they
    // describe HOW to authenticate — essential when debugging 401s). Checked
    // before the fragment heuristic.
    private val NON_SENSITIVE_HEADERS =
        setOf("access-control-allow-credentials", "www-authenticate", "proxy-authenticate")

    private val SENSITIVE_KEY_FRAGMENTS =
        listOf(
            "token",
            "key",
            "secret",
            "password",
            "passwd",
            "pwd",
            "auth",
            "session",
            "credential",
            "bearer",
            "signature",
            "sig",
        )

    /** Returns [url] with sensitive query parameters redacted. */
    override fun redactUrl(url: String): String {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return url
        val base = url.substring(0, queryStart)
        val fragmentStart = url.indexOf('#', queryStart)
        val query =
            if (fragmentStart >= 0) url.substring(queryStart + 1, fragmentStart) else url.substring(queryStart + 1)
        val fragment = if (fragmentStart >= 0) url.substring(fragmentStart) else ""
        if (query.isEmpty()) return url

        val redactedQuery =
            query.split('&').joinToString("&") { pair ->
                val eq = pair.indexOf('=')
                if (eq < 0) {
                    pair
                } else {
                    val name = pair.substring(0, eq)
                    val decodedName = tryUrlDecode(name)
                    if (isSensitiveKey(decodedName)) {
                        "$name=${urlEncode(PLACEHOLDER)}"
                    } else {
                        pair
                    }
                }
            }
        return "$base?$redactedQuery$fragment"
    }

    /**
     * Returns the header [value], or `[REDACTED]` when [name] is a well-known
     * sensitive header OR its name matches a sensitive key fragment — so custom
     * auth headers (`X-Api-Key`, `X-Auth-Token`, …) are masked, not just the
     * standard `Authorization` / `Cookie` set. Well-known public headers that
     * merely resemble a secret by name ([NON_SENSITIVE_HEADERS]) pass through.
     */
    override fun redactHeaderValue(name: String, value: String): String {
        val lower = name.lowercase()
        return when {
            lower in NON_SENSITIVE_HEADERS -> value
            lower in SENSITIVE_HEADERS || isSensitiveKey(name) -> PLACEHOLDER
            else -> value
        }
    }

    /**
     * Returns [body] with sensitive values redacted. EVERY captured text body is
     * covered (the capture layer only retains text-like bodies): the structured
     * JSON/form paths are the precise fast paths, and anything else — including
     * JSON that fails to parse (NDJSON / concatenated frames), SSE
     * (`text/event-stream`), XML, and `text/plain` — falls through to the
     * key-name-based [redactTextually]. Every path keys off a NAME, so a value
     * no sensitive name points at is returned as it arrived; see the class KDoc.
     *
     * A JSON body with no sensitive key in it is returned unchanged, byte for
     * byte, so the inspector shows what was really on the wire; one that has a
     * sensitive key, or that the parser reads more loosely than the JSON grammar,
     * comes back rebuilt from the parse tree (see [redactJson]).
     */
    override fun redactBody(body: String, contentType: MediaType?): String {
        if (body.isEmpty()) return body
        val subtype = contentType?.subtype.orEmpty()
        return when {
            // Structured JSON is the precise fast path; on a parse failure we still
            // mask textually rather than storing the raw body.
            subtype == "json" || subtype.endsWith("+json") || looksLikeJson(body) ->
                redactJson(body) ?: redactTextually(body)
            subtype == "x-www-form-urlencoded" -> redactForm(body)
            // event-stream / xml / text/* / unknown framing.
            else -> redactTextually(body)
        }
    }

    private fun looksLikeJson(body: String): Boolean {
        val trimmed = body.trimStart()
        return trimmed.startsWith("{") || trimmed.startsWith("[")
    }

    // Runs for every key and element name in a captured body, so an ASCII name is
    // matched without allocating a lowercase copy. Other names still go through
    // lowercase(), which can turn one char into several.
    private fun isSensitiveKey(name: String): Boolean {
        if (name.any { it >= '\u0080' }) {
            val lower = name.lowercase()
            return SENSITIVE_KEY_FRAGMENTS.any { lower.contains(it) }
        }
        return SENSITIVE_KEY_FRAGMENTS.any { name.containsIgnoringAsciiCase(it) }
    }

    /** Whether this ASCII text contains the lowercase [fragment], in any case. */
    private fun String.containsIgnoringAsciiCase(fragment: String): Boolean {
        for (start in 0..length - fragment.length) {
            var matched = 0
            while (matched < fragment.length && this[start + matched].lowercaseAscii() == fragment[matched]) matched++
            if (matched == fragment.length) return true
        }
        return false
    }

    private fun Char.lowercaseAscii(): Char = if (this in 'A'..'Z') this + ('a' - 'A') else this

    /**
     * Structured-JSON redaction. Returns the redacted JSON string, or `null` when
     * the body is not a SINGLE complete JSON object/array so the caller can fall
     * back to [redactTextually] instead of storing the body raw. We tokenise and
     * reject trailing content because `org.json` parses leniently — it would
     * happily read only the first object of an NDJSON / concatenated body and
     * silently drop the rest (losing data AND any secrets in the later frames).
     *
     * A body whose own text is a strict JSON object or array with no sensitive key
     * in it comes back UNCHANGED (see [hasOnlyInsensitiveKeys]): parsing and
     * re-serializing it would
     * edit what the inspector shows and copies without masking anything — `org.json`
     * widens integers past `Long` into doubles, normalizes `1.10` and `1e2`,
     * unescapes `\uXXXX`, drops all but the last of a repeated key, and rewrites
     * whitespace. Every other body goes down the paths above: rebuilt from the
     * parse tree, or `null` for [redactTextually] when it isn't one complete JSON
     * value. The rebuild is what masking a value needs, and what makes a body the
     * parser reads loosely safe: `org.json` on Android skips comments and lets a
     * repeated key shadow an earlier one, so text it never puts in the tree — a
     * secret in a comment or under a shadowed key — is dropped instead of stored.
     */
    private fun redactJson(body: String): String? =
        try {
            if (hasOnlyInsensitiveKeys(body)) {
                body
            } else {
                val tokener = JSONTokener(body)
                val redacted =
                    when (val value = tokener.nextValue()) {
                        is JSONObject -> redactJsonObject(value).toString()
                        is JSONArray -> redactJsonArray(value).toString()
                        else -> null
                    }
                // nextClean() skips whitespace and returns the NUL char (Char.MIN_VALUE)
                // at end-of-input; any other char means a second value follows (NDJSON /
                // concatenated), so the body is not a single JSON value -> textual.
                if (redacted != null && tokener.nextClean() == Char.MIN_VALUE) redacted else null
            }
        } catch (_: Exception) {
            null
        }

    private fun redactJsonObject(obj: JSONObject): JSONObject {
        val keys = obj.keys().asSequence().toList()
        for (key in keys) {
            if (isSensitiveKey(key)) {
                obj.put(key, PLACEHOLDER)
                continue
            }
            when (val value = obj.get(key)) {
                is JSONObject -> obj.put(key, redactJsonObject(value))
                is JSONArray -> obj.put(key, redactJsonArray(value))
                else -> Unit
            }
        }
        return obj
    }

    private fun redactJsonArray(arr: JSONArray): JSONArray {
        for (i in 0 until arr.length()) {
            when (val value = arr.get(i)) {
                is JSONObject -> arr.put(i, redactJsonObject(value))
                is JSONArray -> arr.put(i, redactJsonArray(value))
                else -> Unit
            }
        }
        return arr
    }

    /**
     * Whether [body] is a single STRICT JSON value — the grammar, nothing the
     * parser merely tolerates — in which no key anywhere is sensitive. That is the
     * one case where the body can be stored as it arrived: the decision is made on
     * the source text, so a key the parse tree never shows still counts, whether
     * it sits under a repeated key that shadows it or inside a comment.
     *
     * The scan is a flat loop over the text with an explicit container stack, so a
     * deeply nested body costs memory rather than call frames.
     */
    private fun hasOnlyInsensitiveKeys(body: String): Boolean {
        // One char per open container: '{' or '['.
        val containers = StringBuilder()
        var i = skipJsonSpace(body, 0)
        // Only an object or an array, the two the tree path handles. A body that is
        // a bare JSON string can hold a secret with no key to spot it by
        // ("https://host/file?token=..."), and it keeps going to [redactTextually],
        // which masks one.
        if (i == body.length || (body[i] != '{' && body[i] != '[')) return false
        // What the next token must be: a value, an object key, or a separator
        // after a completed value.
        var expect = EXPECT_VALUE
        while (i < body.length) {
            val c = body[i]
            when (expect) {
                EXPECT_VALUE ->
                    when {
                        c == '{' || c == '[' -> {
                            containers.append(c)
                            i = skipJsonSpace(body, i + 1)
                            val close = if (c == '{') '}' else ']'
                            if (i < body.length && body[i] == close) {
                                containers.setLength(containers.length - 1)
                                i++
                                expect = EXPECT_SEPARATOR
                            } else {
                                expect = if (c == '{') EXPECT_KEY else EXPECT_VALUE
                            }
                        }
                        c == '"' -> {
                            i = jsonStringEnd(body, i)
                            if (i < 0) return false
                            expect = EXPECT_SEPARATOR
                        }
                        else -> {
                            i = jsonScalarEnd(body, i)
                            if (i < 0) return false
                            expect = EXPECT_SEPARATOR
                        }
                    }
                EXPECT_KEY -> {
                    if (c != '"') return false
                    val end = jsonStringEnd(body, i)
                    if (end < 0 || !isInsensitiveKey(body.substring(i + 1, end - 1))) return false
                    i = skipJsonSpace(body, end)
                    if (i == body.length || body[i] != ':') return false
                    i++
                    expect = EXPECT_VALUE
                }
                else -> {
                    val open = containers.lastOrNull() ?: return false
                    when {
                        c == ',' -> {
                            i++
                            expect = if (open == '{') EXPECT_KEY else EXPECT_VALUE
                        }
                        c == '}' && open == '{' || c == ']' && open == '[' -> {
                            containers.setLength(containers.length - 1)
                            i++
                        }
                        else -> return false
                    }
                }
            }
            i = skipJsonSpace(body, i)
        }
        // A complete value, with every container closed and nothing left over.
        return expect == EXPECT_SEPARATOR && containers.isEmpty()
    }

    /**
     * Whether the key whose SOURCE text is [source] (the characters between its
     * quotes) is non-sensitive. Escapes are resolved first, so a key written as
     * `tok\u0065n` is matched as the `token` the parse tree would hold.
     */
    private fun isInsensitiveKey(source: String): Boolean {
        if (source.indexOf('\\') < 0) return !isSensitiveKey(source)
        val unescaped = JSONTokener("\"$source\"").nextValue() as? String ?: return false
        return !isSensitiveKey(unescaped)
    }

    private fun skipJsonSpace(text: String, from: Int): Int {
        var i = from
        // The four characters JSON counts as whitespace; a parser that also skips
        // comments must not be followed here, or their text would pass unread.
        while (i < text.length && (text[i] == ' ' || text[i] == '\t' || text[i] == '\n' || text[i] == '\r')) i++
        return i
    }

    /** Index just past the string starting at [from], or -1 when it isn't one. */
    private fun jsonStringEnd(text: String, from: Int): Int {
        var i = from + 1
        while (i < text.length) {
            when (val c = text[i]) {
                '"' -> return i + 1
                '\\' -> {
                    val escape = text.getOrNull(i + 1) ?: return -1
                    if (escape == 'u') {
                        if (i + 6 > text.length || !text.substring(i + 2, i + 6).all { it.isHexDigit() }) return -1
                        i += 6
                    } else {
                        if (escape !in "\"\\/bfnrt") return -1
                        i += 2
                    }
                }
                else -> if (c < ' ') return -1 else i++
            }
        }
        return -1
    }

    /** Index just past the number or literal starting at [from], or -1 when it isn't one. */
    private fun jsonScalarEnd(text: String, from: Int): Int {
        for (literal in JSON_LITERALS) {
            if (text.startsWith(literal, from)) return from + literal.length
        }
        var i = from
        if (i < text.length && text[i] == '-') i++
        val intStart = i
        while (i < text.length && text[i].isAsciiDigit()) i++
        // No digits, or a leading zero followed by more of them.
        if (i == intStart || (text[intStart] == '0' && i - intStart > 1)) return -1
        if (i < text.length && text[i] == '.') {
            i++
            val fractionStart = i
            while (i < text.length && text[i].isAsciiDigit()) i++
            if (i == fractionStart) return -1
        }
        if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
            i++
            if (i < text.length && (text[i] == '+' || text[i] == '-')) i++
            val exponentStart = i
            while (i < text.length && text[i].isAsciiDigit()) i++
            if (i == exponentStart) return -1
        }
        return i
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    private fun Char.isHexDigit(): Boolean = isAsciiDigit() || this in 'a'..'f' || this in 'A'..'F'

    /**
     * Framing-agnostic fallback that masks the VALUE of any sensitive key wherever
     * it appears in arbitrary text, so a captured body that isn't strict JSON or a
     * form (NDJSON / concatenated JSON, SSE `data:` frames, XML, `text/plain`) is
     * still scanned. It is deliberately CONSERVATIVE: only the value of a
     * sensitive key (per [isSensitiveKey]) is replaced; every other byte is left
     * untouched. Covers four shapes, case-insensitively:
     * - JSON-ish `"<key>": <value>`, where the value is a string, a number, a
     *   literal, or a whole object or array,
     * - form/query-ish `<key>=<value>`,
     * - XML elements `<key>...</key>`, children included,
     * - XML / HTML attributes `<key>="..."` (and `name="..."`-style attributes).
     *
     * Linear in the body size (see [TextualRedactor]): it runs on bodies up to
     * the capture cap, and on the app's own HTTP thread when capture falls behind.
     */
    private fun redactTextually(body: String): String = textualRedactor.redact(body)

    private fun redactForm(body: String): String =
        body.split('&').joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) {
                pair
            } else {
                val name = pair.substring(0, eq)
                if (isSensitiveKey(tryUrlDecode(name))) {
                    "$name=${urlEncode(PLACEHOLDER)}"
                } else {
                    pair
                }
            }
        }

    private fun tryUrlDecode(value: String): String =
        try {
            URLDecoder.decode(value, "UTF-8")
        } catch (_: Exception) {
            value
        }

    private fun urlEncode(value: String): String =
        try {
            URLEncoder.encode(value, "UTF-8")
        } catch (_: Exception) {
            value
        }
}
