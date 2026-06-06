package io.github.twinsen81.lustro.network

import io.github.twinsen81.lustro.MediaType
import java.net.URLDecoder
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * The default [Redactor] applied at capture time.
 *
 * Redacts:
 * - `Authorization`, `Proxy-Authorization`, `Cookie`, and `Set-Cookie` header
 *   values to `[REDACTED]`.
 * - URL query parameters, JSON object fields, and form fields whose name
 *   contains a sensitive token (`token`, `key`, `secret`, `password`, `passwd`,
 *   `pwd`, `auth`, `access_token`, `refresh_token`, `api_key`, `apikey`,
 *   `client_secret`, `session`, `credential`, `bearer`, `signature`, `sig`).
 * - Sensitive values in any other captured text body — SSE, XML, plain text, and
 *   JSON that does not parse as a single object/array (NDJSON / concatenated
 *   frames) — via a framing-agnostic, key-name-based fallback so no captured
 *   value is ever stored raw.
 *
 * Redacted values are never stored.
 */
public object DefaultRedactor : Redactor {
    private const val PLACEHOLDER = "[REDACTED]"

    // Framing-agnostic masking patterns for [redactTextually]. Each captures the
    // key so the value is replaced only for sensitive keys; they are mutually
    // non-overlapping by shape (quoted-key JSON vs bare-key attribute vs element
    // vs bare-key form) so the four passes never corrupt each other's output.

    // JSON-ish "<key>" <sep> "<value>": group 1 = prefix through the value's
    // opening quote, group 2 = key. Value handles escaped quotes.
    private val JSON_KV_REGEX = Regex("(\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\")(?:[^\"\\\\]|\\\\.)*\"")

    // Attribute <key>="<value>": group 1 = prefix through the opening quote,
    // group 2 = attribute name.
    private val ATTRIBUTE_REGEX = Regex("(([A-Za-z_][\\w.\\-:]*)\\s*=\\s*\")[^\"]*\"")

    // XML element <key ...>text</key>: group 1 = open tag through '>', group 2 =
    // open-tag name, group 3 = close-tag name (a backreference to group 2). Inner
    // text is leaf text only (no nested elements).
    private val XML_ELEMENT_REGEX = Regex("(<([A-Za-z_][\\w.\\-:]*)\\b[^>]*>)[^<]*</(\\2)\\s*>")

    // Form/query-ish <key>=<value>: group 1 = key, group 2 = the unquoted value.
    // The (?!\") lookahead skips quoted attribute values so it doesn't double-touch
    // an attribute already masked by [ATTRIBUTE_REGEX].
    private val FORM_KV_REGEX = Regex("\\b([A-Za-z_][\\w.\\-]*)=(?!\")([^&;\\s]*)")

    private val SENSITIVE_HEADERS =
        setOf("authorization", "proxy-authorization", "cookie", "set-cookie")

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

    /** Returns the header [value], or `[REDACTED]` for a sensitive [name]. */
    override fun redactHeaderValue(name: String, value: String): String =
        if (name.lowercase() in SENSITIVE_HEADERS) PLACEHOLDER else value

    /**
     * Returns [body] with sensitive values redacted. EVERY captured text body is
     * covered (the capture layer only retains text-like bodies): the structured
     * JSON/form paths are the precise fast paths, and anything else — including
     * JSON that fails to parse (NDJSON / concatenated frames), SSE
     * (`text/event-stream`), XML, and `text/plain` — falls through to the
     * key-name-based [redactTextually] so a captured secret is never stored raw.
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

    private fun isSensitiveKey(name: String): Boolean {
        val lower = name.lowercase()
        return SENSITIVE_KEY_FRAGMENTS.any { lower.contains(it) }
    }

    /**
     * Structured-JSON redaction. Returns the redacted JSON string, or `null` when
     * the body is not a SINGLE complete JSON object/array so the caller can fall
     * back to [redactTextually] instead of storing the body raw. We tokenise and
     * reject trailing content because `org.json` parses leniently — it would
     * happily read only the first object of an NDJSON / concatenated body and
     * silently drop the rest (losing data AND any secrets in the later frames).
     */
    private fun redactJson(body: String): String? =
        try {
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
     * Framing-agnostic fallback that masks the VALUE of any sensitive key wherever
     * it appears in arbitrary text, so a captured body that isn't strict JSON or a
     * form (NDJSON / concatenated JSON, SSE `data:` frames, XML, `text/plain`) is
     * never stored raw. It is deliberately CONSERVATIVE: only the value of a
     * sensitive key (per [isSensitiveKey]) is replaced; every other byte is left
     * untouched. Covers four shapes, case-insensitively:
     * - JSON-ish `"<key>": "<value>"` / `"<key>":"<value>"`,
     * - form/query-ish `<key>=<value>`,
     * - XML element text `<key>...</key>`,
     * - XML / HTML attributes `<key>="..."` (and `name="..."`-style attributes).
     */
    private fun redactTextually(body: String): String {
        var out = body
        // JSON-ish: "<key>"<sep>"<value>". Group 1 = the prefix through the value's
        // opening quote (preserved verbatim so the exact separator survives),
        // group 2 = the key. Only the value bytes change.
        out = JSON_KV_REGEX.replace(out) { match ->
            if (isSensitiveKey(match.groupValues[2])) "${match.groupValues[1]}$PLACEHOLDER\"" else match.value
        }
        // XML / HTML attribute: <key>="<value>". Group 1 = the prefix through the
        // opening quote, group 2 = the attribute name.
        out = ATTRIBUTE_REGEX.replace(out) { match ->
            if (isSensitiveKey(match.groupValues[2])) "${match.groupValues[1]}$PLACEHOLDER\"" else match.value
        }
        // XML element text: <key>text</key>. Group 1 = the open tag through '>',
        // group 2 = the open-tag name, group 3 = the close-tag name.
        out = XML_ELEMENT_REGEX.replace(out) { match ->
            if (isSensitiveKey(match.groupValues[2])) {
                "${match.groupValues[1]}$PLACEHOLDER</${match.groupValues[3]}>"
            } else {
                match.value
            }
        }
        // Form / query-ish: <key>=<value>. Group 1 = the key. Runs last so it never
        // re-touches a JSON/XML value already masked above.
        out = FORM_KV_REGEX.replace(out) { match ->
            if (isSensitiveKey(match.groupValues[1])) "${match.groupValues[1]}=$PLACEHOLDER" else match.value
        }
        return out
    }

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
