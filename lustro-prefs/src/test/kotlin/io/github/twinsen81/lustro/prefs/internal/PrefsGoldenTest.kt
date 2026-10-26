package io.github.twinsen81.lustro.prefs.internal

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.prefs.PrefsFile
import io.github.twinsen81.lustro.prefs.ValuePolicy
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the golden fixtures of the Preferences routes to what the routes write, so
 * the fixtures that clients validate against can't drift from the server. The
 * cursors are random per file, so they are left out of the comparison.
 */
@RunWith(RobolectricTestRunner::class)
class PrefsGoldenTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val settings = prefs("settings")
    private val session = prefs("session")
    private val registry =
        PrefsRegistry(
            listOf(PrefsFile("settings", settings), PrefsFile("session", session, ValuePolicy.KEYS_ONLY, readOnly = true)),
            File(app.dataDir, "shared_prefs"),
            discoveredReadOnly = true,
        ) { prefs(it) }
    private val routes = PrefsRoutes(registry)

    @After
    fun tearDown() {
        registry.stop()
    }

    @Test
    fun `the golden files, reset, delta, and unchanged fixtures are what the routes write`() {
        settings.edit()
            .putString("username", "ann")
            .putString("auth_token", "eyJhbGciOiJIUzI1NiJ9.e30.c2ln")
            .putInt("launch_count", 3)
            .putLong("last_sync_at", 1_792_419_740_010L)
            .putFloat("text_scale", 1.25f)
            .putBoolean("notifications", true)
            .putStringSet("pinned_tags", setOf("work", "travel"))
            .commit()
        session.edit().putString("session_id", "8f2c9a").putLong("expires_at", 1_792_423_340_010L).commit()
        prefs("sdk_cache").edit().putString("install_id", "c1d2e3").putString("api_key", "k-4f9a").putInt("config_version", 12).commit()
        prefs("secure_store").edit()
            .putString(PrefsSource.KEYSET_KEYS[0], "12a8f0")
            .putString(PrefsSource.KEYSET_KEYS[1], "12a8f1")
            .putString("ATAo8Y3vYQ", "AVvB9ZWQ")
            .commit()
        registry.start()

        assertGolden(FILES, get("files"))

        val reset = get("entries", "file" to "settings")
        assertGolden(RESET, reset)

        // The app writes, and the listener moves the cursor.
        settings.edit().putInt("launch_count", 4).commit()
        val delta = get("entries", "file" to "settings", "cursor" to JSONObject(reset).getString("cursor"))
        assertGolden(DELTA, delta)

        assertGolden(UNCHANGED, get("entries", "file" to "settings", "cursor" to JSONObject(delta).getString("cursor")))
    }

    private fun prefs(name: String): SharedPreferences = app.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun get(path: String, vararg query: Pair<String, String>): String =
        routes.handle(DebugRequest(path, "GET", query.associate { it.first to listOf(it.second) }))!!.text()

    private fun assertGolden(path: String, written: String) {
        assertEquals(
            "$path differs from:\n$written\n",
            canonical(JSONObject(File(path).readText())),
            canonical(JSONObject(written)),
        )
    }

    // Key order is not part of JSON, so objects compare as sorted maps, without their cursors.
    private fun canonical(value: Any?): Any? =
        when (value) {
            is JSONObject -> value.keys().asSequence().filter { it != "cursor" }.associateWith { canonical(value.get(it)) }.toSortedMap()
            is JSONArray -> (0 until value.length()).map { canonical(value.get(it)) }
            else -> value
        }

    private companion object {
        // Unit tests run in the module directory.
        const val FILES = "../wire-protocol/v1/golden/prefs-files.json"
        const val RESET = "../wire-protocol/v1/golden/prefs-reset.json"
        const val DELTA = "../wire-protocol/v1/golden/prefs-delta.json"
        const val UNCHANGED = "../wire-protocol/v1/golden/prefs-unchanged.json"
    }
}
