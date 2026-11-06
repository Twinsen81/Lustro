package io.github.twinsen81.lustro.screen

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.startup.InitializationProvider
import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.DebugConfig
import io.github.twinsen81.lustro.Lustro
import io.github.twinsen81.lustro.LustroStatus
import io.github.twinsen81.lustro.screen.internal.ScreenFixtures
import java.time.Duration
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
import org.robolectric.annotation.Config

/** The Screen routes through the real server, with its token auth, and the tab as the public factory builds it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class ScreenDebugTabServerTest {
    private val http = OkHttpClient()
    private lateinit var lustro: Lustro
    private lateinit var root: String
    private lateinit var base: String

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // The server reads its token from this file, and writes the port it bound.
        val prefs = app.getSharedPreferences("lustro_debug", Context.MODE_PRIVATE)
        prefs.edit().putString("lustro_token", TOKEN).commit()
        lustro =
            Lustro.builder(app)
                .config(DebugConfig.builder().serverPort(0).build())
                .addTab(ScreenDebugTab.create(app) { """{"route":"profile"}""" })
                .build()
        // The server listens while the app is in the foreground: an activity puts it there.
        // Robolectric creates no content provider, so ProcessLifecycleOwner is attached here.
        Robolectric.setupContentProvider(InitializationProvider::class.java)
        ScreenFixtures.composeActivity(dialog = true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(LustroStatus.ENABLED, lustro.start())

        val deadline = System.currentTimeMillis() + 5_000
        while (prefs.getInt("lustro_port", 0) == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        root = "http://127.0.0.1:${prefs.getInt("lustro_port", 0)}"
        base = "$root/api/v1/screen"
    }

    @After
    fun tearDown() {
        lustro.stop()
    }

    @Test
    fun `the tree of both windows, with the app's context, through the server`() {
        val tree = JSONObject(onMain { get("tree") }.body!!.string())
        val windows = tree.getJSONArray("windows")
        assertEquals(2, windows.length())
        assertEquals("dialog", windows.getJSONObject(1).getString("kind"))
        assertEquals("profile", tree.getJSONObject("context").getString("route"))
        assertTrue(tree.toString().contains("\"testTag\":\"confirm\""))

        assertEquals(405, onMain { call(Request.Builder().url("$base/tree").post(ByteArray(0).toRequestBody())) }.code)
        val error = JSONObject(onMain { get("tree?settle=-1") }.body!!.string())
        assertEquals("settle", error.getString("field"))
    }

    @Test
    fun `the tab serves its schema with its own version, and _meta lists it, out of the tab bar`() {
        val schema = JSONObject(get("_schema").body!!.string())
        assertEquals("1.0", schema.getJSONObject("info").getString("version"))
        assertTrue(schema.getJSONObject("paths").has("/api/v1/screen/tree"))
        val tabs = JSONObject(call(Request.Builder().url("$root/api/v1/_meta")).body!!.string()).getJSONArray("tabs")
        val screen = (0 until tabs.length()).map { tabs.getJSONObject(it) }.single { it.getString("id") == "screen" }
        assertEquals("/api/v1/screen/_schema", screen.getString("schemaUrl"))
        assertEquals("1.0", screen.getString("version"))
        // Out of the tab bar: the console has no page for it.
        assertFalse(http.newCall(Request.Builder().url("$root/").build()).execute().body!!.string().contains("href=\"/tab/screen\""))
        assertEquals(401, http.newCall(Request.Builder().url("$base/tree").build()).execute().code)
    }

    // The server calls the tab on its own thread, and the read waits for the main
    // looper, which this test thread runs while the call is in flight.
    private fun onMain(call: () -> Response): Response {
        var response: Result<Response>? = null
        val thread = Thread { response = runCatching(call) }
        thread.start()
        while (thread.isAlive) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            thread.join(1)
        }
        return response!!.getOrThrow()
    }

    private fun get(path: String): Response = call(Request.Builder().url("$base/$path"))

    private fun call(builder: Request.Builder): Response = http.newCall(builder.header("Authorization", "Bearer $TOKEN").build()).execute()

    private companion object {
        const val TOKEN = "screen-server-test-token"
    }
}
