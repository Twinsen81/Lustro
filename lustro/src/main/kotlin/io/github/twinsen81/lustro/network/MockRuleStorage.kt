package io.github.twinsen81.lustro.network

import android.content.SharedPreferences
import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.internal.network.MockRuleImpl
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistence seam for network [MockRule]s.
 *
 * The runtime [load]s rules at startup and [save]s the full set on every
 * mutation. Provide an implementation to survive app restarts (see
 * [SharedPreferencesMockRuleStorage]); pass `null` to [NetworkDebugTab.create]
 * for in-memory-only rules.
 */
public interface MockRuleStorage {
    /** Returns the persisted mock rules, or an empty list when none are stored. */
    public fun load(): List<MockRule>

    /** Persists the full set of [rules], replacing any previously stored. */
    public fun save(rules: List<MockRule>)
}

/**
 * The default [MockRuleStorage] backed by [SharedPreferences].
 *
 * Rules are stored as a JSON array under a single key so they survive app
 * restarts and can be set up before the browser console is opened.
 */
public class SharedPreferencesMockRuleStorage(
    private val prefs: SharedPreferences,
) : MockRuleStorage {
    /** Loads the persisted rules; corrupt data yields an empty list. */
    override fun load(): List<MockRule> {
        val raw = prefs.getString(PREF_RULES_KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let(::ruleFromJsonOrNull)?.let(::add)
                }
            }
        } catch (_: Exception) {
            // Corrupt prefs — ignore and start with no rules.
            emptyList()
        }
    }

    // commit() (synchronous) instead of apply(). Debug-only writes happen at
    // human speed (a few per session), so the cost is negligible, and apply()
    // can lose writes if the process is killed before the async flush finishes —
    // which is exactly what happened during testing of this feature, prompting
    // the switch. Lint's ApplySharedPref warning is intentionally suppressed.
    /** Persists [rules] synchronously (commit, not apply — see code comment). */
    @Suppress("ApplySharedPref")
    override fun save(rules: List<MockRule>) {
        val arr = JSONArray()
        for (rule in rules) {
            val obj = JSONObject()
            obj.put("id", rule.id)
            obj.put("enabled", rule.enabled)
            obj.put("name", rule.name)
            obj.put("urlPattern", rule.urlPattern)
            obj.put("method", rule.method ?: JSONObject.NULL)
            obj.put("statusCode", rule.statusCode)
            if (!rule.responseHeaders.isEmpty()) {
                val headersObj = JSONObject()
                rule.responseHeaders.forEach { name, value -> headersObj.put(name, value) }
                obj.put("responseHeaders", headersObj)
            }
            obj.put("responseBody", rule.responseBody)
            arr.put(obj)
        }
        prefs.edit().putString(PREF_RULES_KEY, arr.toString()).commit()
    }

    private fun ruleFromJsonOrNull(obj: JSONObject): MockRule? {
        val id = obj.optString("id")
        if (id.isBlank()) return null
        // Reject an empty pattern — MockRule.matches() falls back to substring match,
        // so `url.contains("")` would silently mock every request if the prefs file
        // were corrupted or partially written.
        val urlPattern = obj.optString("urlPattern", "")
        if (urlPattern.isBlank()) return null
        return MockRuleImpl(
            id = id,
            enabled = obj.optBoolean("enabled", true),
            name = obj.optString("name", ""),
            urlPattern = urlPattern,
            method = if (obj.isNull("method")) null else obj.optString("method").ifBlank { null },
            statusCode = obj.optInt("statusCode", DEFAULT_MOCK_STATUS),
            responseHeaders = obj.optJSONObject("responseHeaders").toHeaders(),
            responseBody = obj.optString("responseBody", ""),
            // hitCount intentionally not persisted; runtime-only counter.
        )
    }

    private fun JSONObject?.toHeaders(): Headers {
        if (this == null) return Headers.EMPTY
        val builder = Headers.Builder()
        keys().forEach { key -> builder.add(key, optString(key)) }
        return builder.build()
    }

    private companion object {
        private const val PREF_RULES_KEY = "rules"
        private const val DEFAULT_MOCK_STATUS = 200
    }
}
