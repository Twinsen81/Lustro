package io.github.twinsen81.lustro.sample

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The sample's SharedPreferences files for the Preferences tab, and the app's own
 * changes to them. The debug bootstrap registers [SETTINGS] with every value
 * visible and [SESSION] with keys only and read-only; [SDK_CACHE] stands for the
 * file of a library, which the tab finds on disk. They are plain SharedPreferences
 * calls, so this file needs no Lustro type.
 */
internal class DemoPrefs(context: Context) {
    private val settings = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
    private val session = context.getSharedPreferences(SESSION, Context.MODE_PRIVATE)
    private val sdkCache = context.getSharedPreferences(SDK_CACHE, Context.MODE_PRIVATE)

    private val values = MutableStateFlow(read())

    /** The entries of [SETTINGS] by key, each as its type and value, as the app reads them now. */
    val settingsValues: StateFlow<List<Pair<String, String>>> = values.asStateFlow()

    // SharedPreferences holds its listeners weakly, so this object keeps this one.
    // An edit from the console calls it too: that is how the screen follows.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> values.value = read() }

    init {
        settings.registerOnSharedPreferenceChangeListener(listener)
    }

    fun close() {
        settings.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun countLaunch(): Int {
        val count = settings.getInt(LAUNCH_COUNT, 0) + 1
        settings.edit().putInt(LAUNCH_COUNT, count).putLong(LAST_OPENED_AT, System.currentTimeMillis()).apply()
        return count
    }

    fun rotateSession(): String {
        val id = randomId()
        session.edit().putString("session_id", id).putLong("expires_at", System.currentTimeMillis() + HOUR_MS).apply()
        return id
    }

    fun refreshSdkConfig(): Int {
        val version = sdkCache.getInt("config_version", 0) + 1
        sdkCache.edit().putInt("config_version", version).putLong("last_fetch_at", System.currentTimeMillis()).apply()
        return version
    }

    /** Clears [SETTINGS] as an app would. Returns how many keys it had. */
    fun clearSettings(): Int {
        val count = settings.all.size
        settings.edit().clear().apply()
        // Before API 30, clear() calls no listener, this one included.
        values.value = read()
        return count
    }

    fun reset() {
        writeDefaults(settings, session, sdkCache)
        values.value = read()
    }

    private fun read(): List<Pair<String, String>> =
        settings.all.toSortedMap().map { (key, value) ->
            key to
                when (value) {
                    is String -> "\"$value\""
                    is Set<*> -> value.map { it.toString() }.sorted().joinToString(prefix = "[", postfix = "]")
                    else -> value.toString()
                }
        }

    companion object {
        const val SETTINGS: String = "sample_settings"
        const val SESSION: String = "sample_session"
        const val SDK_CACHE: String = "sample_sdk_cache"
        const val LAUNCH_COUNT: String = "launch_count"
        private const val LAST_OPENED_AT = "last_opened_at"
        private const val HOUR_MS = 3_600_000L

        /**
         * Writes the demo values on the first start, so that the three files are
         * on disk before the console lists them.
         */
        fun seed(context: Context) {
            val settings = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
            if (settings.contains("username")) return
            writeDefaults(
                settings,
                context.getSharedPreferences(SESSION, Context.MODE_PRIVATE),
                context.getSharedPreferences(SDK_CACHE, Context.MODE_PRIVATE),
            )
        }

        private fun writeDefaults(settings: SharedPreferences, session: SharedPreferences, sdkCache: SharedPreferences) {
            val now = System.currentTimeMillis()
            settings.edit()
                .clear()
                .putString("username", "ann")
                .putInt(LAUNCH_COUNT, 1)
                .putLong(LAST_OPENED_AT, now)
                .putFloat("text_scale", 1.0f)
                .putBoolean("notifications", true)
                .putStringSet("pinned_tags", setOf("work", "travel"))
                .apply()
            session.edit()
                .clear()
                .putString("session_id", randomId())
                .putString("refresh_token", "rt-${randomId()}")
                .putLong("user_id", 4_211L)
                .putLong("expires_at", now + HOUR_MS)
                .apply()
            sdkCache.edit()
                .clear()
                .putString("install_id", randomId())
                .putString("api_key", "sk-demo-${randomId()}")
                .putInt("config_version", 1)
                .putLong("last_fetch_at", now)
                .apply()
        }

        private fun randomId(): String = java.util.UUID.randomUUID().toString().take(8)
    }
}
