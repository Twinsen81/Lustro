package io.github.twinsen81.lustro.logs

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.startup.InitializationProvider
import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.DebugConfig
import io.github.twinsen81.lustro.Lustro
import io.github.twinsen81.lustro.LustroStatus
import io.github.twinsen81.lustro.logs.internal.FakeLauncher
import io.github.twinsen81.lustro.logs.internal.FakeLogcat
import io.github.twinsen81.lustro.logs.internal.LogBuffer
import io.github.twinsen81.lustro.logs.internal.LogcatReader
import io.github.twinsen81.lustro.logs.internal.ThreadtimeParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.TimeZone

/** The Logs routes through the real server, with its token auth. */
@RunWith(RobolectricTestRunner::class)
class LogsDebugTabServerTest {
    private val logcat = FakeLogcat()
    private val launcher = FakeLauncher(logcat)
    private val http = OkHttpClient()
    private lateinit var lustro: Lustro
    private lateinit var base: String

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // The server reads its token from this file, and writes the port it bound.
        val prefs = app.getSharedPreferences("lustro_debug", Context.MODE_PRIVATE)
        prefs.edit().putString("lustro_token", TOKEN).commit()

        val buffer = LogBuffer(1 shl 20)
        val reader =
            LogcatReader(
                buffer,
                pid = 4321,
                processStartMs = { 0L },
                launch = launcher,
                parser = ThreadtimeParser(TimeZone.getTimeZone("UTC")) { NOW },
            )
        lustro =
            Lustro.builder(app)
                .config(DebugConfig.builder().serverPort(0).build())
                .addTab(LogsDebugTab.create(buffer, reader))
                .build()
        // The server listens while the app is in the foreground: an activity puts it there.
        // Robolectric creates no content provider, so ProcessLifecycleOwner is attached here.
        Robolectric.setupContentProvider(InitializationProvider::class.java)
        Robolectric.buildActivity(Activity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(LustroStatus.ENABLED, lustro.start())

        val deadline = System.currentTimeMillis() + 5_000
        while (prefs.getInt("lustro_port", 0) == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        base = "http://127.0.0.1:${prefs.getInt("lustro_port", 0)}/api/v1/logs"
    }

    @After
    fun tearDown() {
        lustro.stop()
    }

    @Test
    fun `start runs logcat, the tail shows its lines, and stop ends it`() {
        assertTrue(launcher.nextLaunch().contains("--pid=4321"))
        logcat.emit(
            "10-19 14:22:20.000  4321  4330 I Sync    : started",
            "10-19 14:22:20.001  4321  4330 E Sync    : failed",
        )

        val envelope = awaitItems(2)
        assertEquals("reset", envelope.getString("status"))
        val state = envelope.getJSONObject("state")
        assertEquals("running", state.getString("reader"))
        assertEquals(false, state.getBoolean("paused"))
        assertEquals(true, state.getBoolean("compact"))

        val errors = JSONObject(get("entries?level=E").body!!.string())
        assertEquals(1, errors.getJSONArray("items").length())
        assertEquals("failed", errors.getJSONArray("items").getJSONObject(0).getString("message"))

        val text = get("text?tag=%5ESync").body!!.string()
        assertTrue(text, text.endsWith(" 4321  4330 E Sync    : failed\n"))

        lustro.stop()
        assertTrue("stop destroys logcat", logcat.destroyed)
    }

    @Test
    fun `a request without the token gets the enveloped 401`() {
        val response = http.newCall(Request.Builder().url("$base/entries").build()).execute()
        response.use {
            assertEquals(401, it.code)
            assertEquals("unauthorized", JSONObject(it.body!!.string()).getString("error"))
        }
    }

    @Test
    fun `clear, pause, and errors go through the server`() {
        logcat.emit("10-19 14:22:20.000  4321  4330 I Sync    : before")
        awaitItems(1)

        val clear = post("clear", "")
        assertEquals(200, clear.code)
        val cursor = JSONObject(clear.body!!.string()).getString("cursor")
        logcat.emit("10-19 14:22:21.000  4321  4330 I Sync    : after")
        var delta: JSONObject
        val deadline = System.currentTimeMillis() + 5_000
        do {
            delta = JSONObject(get("entries?cursor=$cursor").body!!.string())
        } while (delta.getString("status") == "unchanged" && System.currentTimeMillis() < deadline)
        assertEquals("delta", delta.getString("status"))
        assertEquals(0, delta.getInt("dropped"))
        assertEquals("after", delta.getJSONArray("items").getJSONObject(0).getString("message"))

        val pause = post("pause", """{"paused":true}""")
        assertEquals(true, JSONObject(pause.body!!.string()).getBoolean("paused"))

        val bad = get("entries?level=Q")
        assertEquals(400, bad.code)
        assertEquals("level", JSONObject(bad.body!!.string()).getString("field"))
        assertEquals(404, get("nothing").code)

        val schema = get("_schema")
        assertEquals(200, schema.code)
        assertTrue(JSONObject(schema.body!!.string()).getJSONObject("paths").has("/api/v1/logs/entries"))
    }

    @Test
    fun `the tab is in the tab bar, and serves its view with its script and styles`() {
        val page = http.newCall(Request.Builder().url(base.removeSuffix("/api/v1/logs") + "/tab/logs").build()).execute()
        assertTrue(page.body!!.string().contains("<a href=\"/tab/logs\" class=\"dc-nav__item dc-nav__item--active\">"))

        val view = get("_view")
        assertEquals(200, view.code)
        assertTrue(view.body!!.string().contains("<tbody id=\"logs-rows\"></tbody>"))
        assertTrue(get("_view.js").body!!.string().contains("window.logsApplyEnvelope"))
        assertTrue(get("_view.css").body!!.string().contains(".logs-bar"))
    }

    private fun awaitItems(count: Int): JSONObject {
        val deadline = System.currentTimeMillis() + 5_000
        var envelope: JSONObject
        do {
            envelope = JSONObject(get("entries").body!!.string())
            if ((envelope.optJSONArray("items")?.length() ?: 0) >= count) return envelope
            Thread.sleep(10)
        } while (System.currentTimeMillis() < deadline)
        throw AssertionError("expected $count items, got $envelope")
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

        // 2026-10-19 14:22:20.010 UTC
        const val NOW = 1_792_419_740_010L
    }
}
