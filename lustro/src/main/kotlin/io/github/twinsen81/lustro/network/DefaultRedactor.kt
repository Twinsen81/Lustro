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
 *   frames) — via a framing-agnostic, key-name-based fallback so no captured
 *   value is ever stored raw. As on the structured path, a sensitive key's whole
 *   value is masked, whether a string, a number, or a nested object or array, and
 *   so is everything inside a sensitive XML element.
 *
 * A captured body can end partway through a value, when it's cut off at the
 * capture cap or captured while a stream is still arriving. A sensitive value
 * cut off that way is masked through to the end of the body.
 *
 * Redacted values are never stored.
 */
public object DefaultRedactor : Redactor {
    private const val PLACEHOLDER = "[REDACTED]"

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
     * key-name-based [redactTextually] so a captured secret is never stored raw.
     *
     * A body with nothing to mask is returned unchanged, byte for byte, so the
     * inspector shows what was really on the wire. Only a body that does contain
     * a sensitive key comes back re-serialized (see [redactJson]).
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
     * A body with no sensitive key comes back as the ORIGINAL text: re-serializing
     * it would edit what the inspector shows and copies without masking anything —
     * `org.json` widens integers past `Long` into doubles, normalizes `1.10` and
     * `1e2`, unescapes `\uXXXX`, drops all but the last of a repeated key, and
     * rewrites whitespace. A body that does contain a sensitive key still pays
     * those edits: masking a value means rebuilding the text from the parse tree.
     */
    private fun redactJson(body: String): String? =
        try {
            val tokener = JSONTokener(body)
            val redacted =
                when (val value = tokener.nextValue()) {
                    is JSONObject -> if (redactJsonObject(value)) value.toString() else body
                    is JSONArray -> if (redactJsonArray(value)) value.toString() else body
                    else -> null
                }
            // nextClean() skips whitespace and returns the NUL char (Char.MIN_VALUE)
            // at end-of-input; any other char means a second value follows (NDJSON /
            // concatenated), so the body is not a single JSON value -> textual.
            if (redacted != null && tokener.nextClean() == Char.MIN_VALUE) redacted else null
        } catch (_: Exception) {
            null
        }

    /** Masks sensitive values in [obj] in place; returns whether anything was masked. */
    private fun redactJsonObject(obj: JSONObject): Boolean {
        var masked = false
        val keys = obj.keys().asSequence().toList()
        for (key in keys) {
            if (isSensitiveKey(key)) {
                obj.put(key, PLACEHOLDER)
                masked = true
                continue
            }
            when (val value = obj.get(key)) {
                is JSONObject -> if (redactJsonObject(value)) masked = true
                is JSONArray -> if (redactJsonArray(value)) masked = true
                else -> Unit
            }
        }
        return masked
    }

    /** Masks sensitive values in [arr] in place; returns whether anything was masked. */
    private fun redactJsonArray(arr: JSONArray): Boolean {
        var masked = false
        for (i in 0 until arr.length()) {
            when (val value = arr.get(i)) {
                is JSONObject -> if (redactJsonObject(value)) masked = true
                is JSONArray -> if (redactJsonArray(value)) masked = true
                else -> Unit
            }
        }
        return masked
    }

    /**
     * Framing-agnostic fallback that masks the VALUE of any sensitive key wherever
     * it appears in arbitrary text, so a captured body that isn't strict JSON or a
     * form (NDJSON / concatenated JSON, SSE `data:` frames, XML, `text/plain`) is
     * never stored raw. It is deliberately CONSERVATIVE: only the value of a
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
