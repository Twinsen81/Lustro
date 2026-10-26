package io.github.twinsen81.lustro.sample

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// DataStore allows one instance for each file in the process, so the delegates keep
// them, and the debug bootstrap registers the same instance that the app writes.
private val Context.appStateStore: DataStore<Preferences> by preferencesDataStore(name = DemoDataStore.APP_STATE)
private val Context.sdkStateStore: DataStore<Preferences> by preferencesDataStore(name = DemoDataStore.SDK_STATE)

/**
 * The sample's Preferences DataStores for the Preferences tab, and the app's own
 * changes to them. The debug bootstrap registers [APP_STATE], whose sensitive
 * values the tab masks; [SDK_STATE] stands for the store of a library, which
 * the tab finds on disk and reads from its file. They are plain DataStore calls,
 * so this file needs no Lustro type.
 */
internal class DemoDataStore(context: Context, private val scope: CoroutineScope) {
    private val appState = context.appStateStore
    private val sdkState = context.sdkStateStore

    /** The entries of [APP_STATE] by key, as the app reads them now: an edit from the console shows here. */
    val appStateValues: StateFlow<List<Pair<String, String>>> =
        appState.data.map { read(it) }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun countOpen(done: (Int) -> Unit) {
        scope.launch {
            var count = 0
            appState.edit {
                count = (it[OPEN_COUNT] ?: 0) + 1
                it[OPEN_COUNT] = count
                it[LAST_OPENED_AT] = System.currentTimeMillis()
            }
            done(count)
        }
    }

    fun refreshSdkState(done: (Int) -> Unit) {
        scope.launch {
            var version = 0
            sdkState.edit {
                version = (it[CONFIG_VERSION] ?: 0) + 1
                it[CONFIG_VERSION] = version
                it[SAMPLE_RATE] = (1..9).random() / 10.0
            }
            done(version)
        }
    }

    fun reset(done: () -> Unit) {
        scope.launch {
            writeDefaults(appState, sdkState)
            done()
        }
    }

    private fun read(data: Preferences): List<Pair<String, String>> =
        data.asMap().entries.sortedBy { it.key.name }.map { (key, value) ->
            key.name to
                when (value) {
                    is String -> "\"$value\""
                    is Set<*> -> value.map { it.toString() }.sorted().joinToString(prefix = "[", postfix = "]")
                    is ByteArray -> value.joinToString(" ") { "%02x".format(it) }
                    else -> value.toString()
                }
        }

    companion object {
        const val APP_STATE: String = "sample_app_state"
        const val SDK_STATE: String = "sample_sdk_state"

        private val OPEN_COUNT = intPreferencesKey("open_count")
        private val LAST_OPENED_AT = longPreferencesKey("last_opened_at")
        private val CONFIG_VERSION = intPreferencesKey("config_version")
        private val SAMPLE_RATE = doublePreferencesKey("sample_rate")
        private val THEME = stringPreferencesKey("theme")

        /** The store that the app writes, for the debug bootstrap to register. */
        fun appState(context: Context): DataStore<Preferences> = context.applicationContext.appStateStore

        /** Writes the demo values on the first start, so that both stores are on disk before the console lists them. */
        fun seed(context: Context) {
            val app = context.applicationContext
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                if (THEME !in app.appStateStore.data.first()) writeDefaults(app.appStateStore, app.sdkStateStore)
            }
        }

        private suspend fun writeDefaults(appState: DataStore<Preferences>, sdkState: DataStore<Preferences>) {
            appState.edit {
                it.clear()
                it[THEME] = "dark"
                it[OPEN_COUNT] = 1
                it[LAST_OPENED_AT] = System.currentTimeMillis()
                it[doublePreferencesKey("zoom")] = 1.25
                it[booleanPreferencesKey("onboarding_done")] = false
                it[stringSetPreferencesKey("recent_searches")] = setOf("lighthouse", "harbor")
                it[byteArrayPreferencesKey("avatar_hash")] = byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00)
                it[stringPreferencesKey("auth_token")] = "at-${java.util.UUID.randomUUID().toString().take(8)}"
            }
            sdkState.edit { it.writeSdkDefaults() }
        }

        private fun MutablePreferences.writeSdkDefaults() {
            clear()
            this[stringPreferencesKey("install_id")] = java.util.UUID.randomUUID().toString().take(8)
            this[stringPreferencesKey("api_key")] = "sk-demo-${java.util.UUID.randomUUID().toString().take(8)}"
            this[CONFIG_VERSION] = 1
            this[SAMPLE_RATE] = 0.5
        }
    }
}
