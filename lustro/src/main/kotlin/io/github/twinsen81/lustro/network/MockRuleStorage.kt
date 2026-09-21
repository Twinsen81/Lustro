package io.github.twinsen81.lustro.network

import android.content.SharedPreferences
import android.util.Log
import io.github.twinsen81.lustro.internal.network.MockRuleCodec
import io.github.twinsen81.lustro.internal.network.MockRuleParseResult
import org.json.JSONArray

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
    /**
     * Loads the persisted rules; corrupt data yields an empty list, and a rule
     * the interceptor could not turn into a response is dropped.
     *
     * Dropping is what breaks the loop a rule stored before this check creates:
     * it would otherwise crash the app on every request it matched, restart
     * after restart.
     */
    override fun load(): List<MockRule> {
        val raw = prefs.getString(PREF_RULES_KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    when (val parsed = MockRuleCodec.parse(obj, generateMissingId = false)) {
                        is MockRuleParseResult.Valid -> add(parsed.rule)
                        is MockRuleParseResult.Invalid ->
                            Log.w(TAG, "Dropping stored mock rule at index $i: ${parsed.message}")
                    }
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
        prefs.edit().putString(PREF_RULES_KEY, MockRuleCodec.toJsonArray(rules)).commit()
    }

    private companion object {
        private const val PREF_RULES_KEY = "rules"
        private const val TAG = "Lustro"
    }
}
