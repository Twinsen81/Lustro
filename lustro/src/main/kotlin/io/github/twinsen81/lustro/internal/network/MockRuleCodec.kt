package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.escapeForJson
import io.github.twinsen81.lustro.network.MockRule
import java.util.UUID
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import okhttp3.Headers as OkHttpHeaders

/**
 * The one reader, writer, and validator for mock rules, shared by the debug API
 * routes and by rule storage.
 *
 * Validation is not cosmetic: [LustroNetworkInterceptor] turns a matching rule
 * into a real OkHttp response inside the app's own call, so a `Content-Type`
 * that isn't a media type, a header name or value OkHttp rejects, or a status
 * outside 100–599 would throw there — on the app's thread, in the app's
 * process. Rules are checked where they enter instead, and anything already
 * stored is checked again when it's loaded.
 */
internal object MockRuleCodec {
    private const val DEFAULT_STATUS = 200
    private const val MIN_STATUS = 100
    private const val MAX_STATUS = 599

    /**
     * Reads a rule from [obj]. When [generateMissingId] is true a blank `id`
     * gets a fresh UUID (the wire contract's "generated when omitted"), and
     * when it is false a blank `id` is rejected, which is what a stored rule
     * with no id is.
     */
    fun parse(obj: JSONObject, generateMissingId: Boolean): MockRuleParseResult {
        val rawId = obj.optString("id")
        val id = if (rawId.isBlank() && generateMissingId) UUID.randomUUID().toString() else rawId
        val rule =
            MockRuleImpl(
                id = id,
                enabled = obj.optBoolean("enabled", true),
                name = obj.optString("name", ""),
                urlPattern = obj.optString("urlPattern", ""),
                method = if (obj.isNull("method")) null else obj.optString("method").ifBlank { null },
                statusCode = obj.optInt("statusCode", DEFAULT_STATUS),
                responseHeaders = headersFrom(obj.optJSONObject("responseHeaders")),
                responseBody = obj.optString("responseBody", ""),
                // hitCount is a runtime counter: never read from input, never persisted.
            )
        return validate(rule) ?: MockRuleParseResult.Valid(rule)
    }

    /** Returns why [rule] can't be served, or `null` when it can. */
    fun validate(rule: MockRule): MockRuleParseResult.Invalid? {
        if (rule.id.isBlank()) {
            return MockRuleParseResult.Invalid("id is required", "id")
        }
        // An empty pattern would match every request: MockRule.matches() falls back
        // to a substring match, and url.contains("") is always true.
        if (rule.urlPattern.isBlank()) {
            return MockRuleParseResult.Invalid("urlPattern is required", "urlPattern")
        }
        if (rule.statusCode !in MIN_STATUS..MAX_STATUS) {
            return MockRuleParseResult.Invalid(
                "statusCode must be between $MIN_STATUS and $MAX_STATUS, was ${rule.statusCode}",
                "statusCode",
            )
        }
        for ((name, value) in rule.responseHeaders.toList()) {
            headerRejection(name, value)?.let { return it }
        }
        val contentType = rule.responseHeaders.get("Content-Type")
        if (contentType != null && contentType.toMediaTypeOrNull() == null) {
            return MockRuleParseResult.Invalid(
                "responseHeaders Content-Type is not a media type: $contentType",
                "responseHeaders",
            )
        }
        return null
    }

    /**
     * Appends [rule] as a JSON object. [includeHitCount] is on for the API's
     * view of a rule and off for a stored one, where the counter is
     * runtime-only.
     */
    fun appendJson(out: StringBuilder, rule: MockRule, includeHitCount: Boolean) {
        out.append("{")
        out.append("\"id\":\"").append(rule.id.escapeForJson()).append("\",")
        out.append("\"name\":\"").append(rule.name.escapeForJson()).append("\",")
        out.append("\"urlPattern\":\"").append(rule.urlPattern.escapeForJson()).append("\",")
        out.append("\"method\":")
        out.append(rule.method?.let { "\"${it.escapeForJson()}\"" } ?: "null").append(",")
        out.append("\"statusCode\":").append(rule.statusCode).append(",")
        out.append("\"responseHeaders\":")
        appendHeadersJson(out, rule.responseHeaders)
        out.append(",")
        out.append("\"responseBody\":\"").append(rule.responseBody.escapeForJson()).append("\",")
        out.append("\"enabled\":").append(rule.enabled)
        if (includeHitCount) {
            out.append(",\"hitCount\":").append(rule.hitCount)
        }
        out.append("}")
    }

    /** Serializes [rules] as a JSON array, in the shape [parse] reads back. */
    fun toJsonArray(rules: List<MockRule>): String =
        buildString {
            append("[")
            rules.forEachIndexed { index, rule ->
                if (index > 0) append(",")
                appendJson(this, rule, includeHitCount = false)
            }
            append("]")
        }

    /**
     * Asks OkHttp itself whether it would take this header, so the answer can't
     * drift from what the interceptor does with the rule later.
     */
    private fun headerRejection(name: String, value: String): MockRuleParseResult.Invalid? =
        try {
            OkHttpHeaders.Builder().add(name, value)
            null
        } catch (e: IllegalArgumentException) {
            MockRuleParseResult.Invalid(
                e.message ?: "responseHeaders has a header that cannot be sent",
                "responseHeaders",
            )
        }

    private fun headersFrom(obj: JSONObject?): Headers {
        if (obj == null) return Headers.EMPTY
        val builder = Headers.Builder()
        obj.keys().forEach { key -> builder.add(key, obj.optString(key)) }
        return builder.build()
    }

    // Duplicate names are joined with ", " so the object shape survives names
    // that a JSON object can only hold once.
    private fun appendHeadersJson(out: StringBuilder, headers: Headers) {
        val folded = LinkedHashMap<String, String>()
        headers.forEach { name, value ->
            val existing = folded[name]
            folded[name] = if (existing != null) "$existing, $value" else value
        }
        out.append("{")
        folded.entries.forEachIndexed { index, (name, value) ->
            if (index > 0) out.append(",")
            out.append("\"").append(name.escapeForJson()).append("\":")
            out.append("\"").append(value.escapeForJson()).append("\"")
        }
        out.append("}")
    }
}

/** What [MockRuleCodec.parse] made of a rule: the rule itself, or why it was refused. */
internal sealed interface MockRuleParseResult {
    /** A rule the interceptor can serve. */
    data class Valid(val rule: MockRuleImpl) : MockRuleParseResult

    /** [message] says what is wrong; [field] names the offending input field. */
    data class Invalid(val message: String, val field: String) : MockRuleParseResult
}
