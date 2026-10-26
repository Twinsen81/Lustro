package io.github.twinsen81.lustro.prefs

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.startup.InitializationProvider
import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.DebugConfig
import io.github.twinsen81.lustro.Lustro
import io.github.twinsen81.lustro.LustroStatus
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The Preferences routes through the real server, with its token auth. */
@RunWith(RobolectricTestRunner::class)
class PrefsDebugTabServerTest {
    private val http = OkHttpClient()
    private lateinit var settings: SharedPreferences
    private lateinit var session: SharedPreferences
    private lateinit var lustro: Lustro
    private lateinit var root: String
    private lateinit var base: String

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // The server reads its token from this file, and writes the port it bound.
        val prefs = app.getSharedPreferences("lustro_debug", Context.MODE_PRIVATE)
        prefs.edit().putString("lustro_token", TOKEN).commit()
        settings = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        settings.edit().putString("username", "ann").putInt("launch_count", 3).commit()
        session = app.getSharedPreferences("session", Context.MODE_PRIVATE)
        session.edit().putString("session_id", "s-1").commit()

        lustro =
            Lustro.builder(app)
                .config(DebugConfig.builder().serverPort(0).build())
                .addTab(
                    PrefsDebugTab.create(
                        app,
                        files =
                            listOf(
                                PrefsFile("settings", settings, ValuePolicy.VISIBLE),
                                PrefsFile("session", session, ValuePolicy.KEYS_ONLY, readOnly = true),
                            ),
                    ),
                )
                .build()
        // The server listens while the app is in the foreground: an activity puts it there.
        // Robolectric creates no content provider, so ProcessLifecycleOwner is attached here.
        Robolectric.setupContentProvider(InitializationProvider::class.java)
        Robolectric.buildActivity(Activity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(LustroStatus.ENABLED, lustro.start())

        val deadline = System.currentTimeMillis() + 5_000
        while (prefs.getInt("lustro_port", 0) == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        root = "http://127.0.0.1:${prefs.getInt("lustro_port", 0)}"
        base = "$root/api/v1/prefs"
    }

    @After
    fun tearDown() {
        lustro.stop()
    }

    @Test
    fun `files, an edit that the app reads, and an app change that moves the cursor`() {
        val files = JSONObject(get("files").body!!.string()).getJSONArray("items")
        val names = (0 until files.length()).map { files.getJSONObject(it).getString("name") }
        assertEquals("settings", names[0])
        assertEquals("session", names[1])
        assertTrue("Lustro's own file is discovered", "lustro_debug" in names)

        val reset = JSONObject(get("entries?file=settings").body!!.string())
        assertEquals("reset", reset.getString("status"))

        val edit = post("edit", """{"file":"settings","key":"username","type":"string","value":"bob"}""")
        assertEquals(200, edit.code)
        assertEquals("bob", settings.getString("username", null))

        val afterEdit = JSONObject(get("entries?file=settings&cursor=${reset.getString("cursor")}").body!!.string())
        assertEquals("delta", afterEdit.getString("status"))

        // The app writes off the main thread; the listener runs on it.
        Thread { settings.edit().putInt("launch_count", 4).commit() }.apply { start() }.join()
        shadowOf(Looper.getMainLooper()).idle()
        val cursor = afterEdit.getString("cursor")
        val afterApp = JSONObject(get("entries?file=settings&cursor=$cursor").body!!.string())
        assertEquals("delta", afterApp.getString("status"))
    }

    @Test
    fun `a read-only file answers 403, and the token masks in Lustro's own file`() {
        val edit = post("edit", """{"file":"session","key":"session_id","type":"string","value":"x"}""")
        assertEquals(403, edit.code)
        assertEquals("read_only", JSONObject(edit.body!!.string()).getString("code"))
        assertEquals("s-1", session.getString("session_id", null))

        val own = get("entries?file=lustro_debug").body!!.string()
        assertFalse(own, own.contains(TOKEN))
    }

    @Test
    fun `a request without the token gets the enveloped 401`() {
        val response = http.newCall(Request.Builder().url("$base/files").build()).execute()
        response.use {
            assertEquals(401, it.code)
            assertEquals("unauthorized", JSONObject(it.body!!.string()).getString("error"))
        }
    }

    @Test
    fun `the schema is served, and _meta lists the tab`() {
        val schema = get("_schema")
        assertEquals(200, schema.code)
        assertTrue(JSONObject(schema.body!!.string()).getJSONObject("paths").has("/api/v1/prefs/entries"))

        val meta = http.newCall(Request.Builder().url("$root/api/v1/_meta").header("Authorization", "Bearer $TOKEN").build()).execute()
        assertTrue(meta.body!!.string().contains("\"prefs\""))
    }

    @Test
    fun `the tab is in the tab bar, and serves its view with its script and styles`() {
        val page = http.newCall(Request.Builder().url("$root/tab/prefs").build()).execute()
        assertTrue(page.body!!.string().contains("<a href=\"/tab/prefs\" class=\"dc-nav__item dc-nav__item--active\">"))

        val view = get("_view")
        assertEquals(200, view.code)
        assertTrue(view.body!!.string().contains("<tbody id=\"prefs-rows\"></tbody>"))
        assertTrue(get("_view.js").body!!.string().contains("window.prefsApplyEnvelope"))
        assertTrue(get("_view.css").body!!.string().contains(".prefs-bar"))
    }

    private fun get(path: String): Response =
        http.newCall(Request.Builder().url("$base/$path").header("Authorization", "Bearer $TOKEN").build()).execute()

    private fun post(path: String, body: String): Response =
        http.newCall(
            Request.Builder()
                .url("$base/$path")
                .header("Authorization", "Bearer $TOKEN")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute()

    private companion object {
        const val TOKEN = "test-token-0123456789"
    }
}
