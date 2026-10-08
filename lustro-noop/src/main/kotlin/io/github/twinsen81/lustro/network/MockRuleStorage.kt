package io.github.twinsen81.lustro.network

import android.content.SharedPreferences

/**
 * Persistence seam for network [MockRule]s.
 *
 * The runtime [load]s the rules once, when they are first needed, off the main
 * thread: on the thread of the first request a rule could answer, or of the
 * first debug API call that reads or changes them. It [save]s the full set on
 * every mutation, on the thread of the debug API call that made it. Both lists
 * are in the order the rules are tried: the first rule that matches a request
 * answers it. Provide an implementation to survive app restarts (see
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
 * The default [MockRuleStorage] backed by [SharedPreferences] (no-op build).
 *
 * Mirrors the `:lustro` signature so consumer code compiles unchanged. Because
 * the no-op build never captures or mocks traffic, [load] always returns an empty
 * list and [save] does nothing. The [prefs] are accepted and held for API parity
 * but never read or written.
 */
public class SharedPreferencesMockRuleStorage(
    private val prefs: SharedPreferences,
) : MockRuleStorage {
    /** Returns an empty list; the no-op build persists nothing. */
    override fun load(): List<MockRule> = emptyList()

    /** Does nothing; the no-op build persists nothing. */
    override fun save(rules: List<MockRule>) {
        // No-op: the no-op build never captures or mocks traffic.
    }
}
