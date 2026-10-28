package io.github.twinsen81.lustro.work

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Looper
import android.util.Log
import androidx.startup.InitializationProvider
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.twinsen81.lustro.DebugConfig
import io.github.twinsen81.lustro.Lustro
import io.github.twinsen81.lustro.LustroStatus
import io.github.twinsen81.lustro.work.internal.StepWorker
import java.util.concurrent.TimeUnit
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

/** The WorkManager routes through the real server, with its token auth, and the tab as the public factory builds it. */
@RunWith(RobolectricTestRunner::class)
class WorkDebugTabServerTest {
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

        val work = WorkDebugTab.create(app, uniqueNames = listOf("sync"))
        val builder = Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor())
        WorkManagerTestInitHelper.initializeTestWorkManager(app, work.configure(builder).build())

        lustro =
            Lustro.builder(app)
                .config(DebugConfig.builder().serverPort(0).build())
                .addTab(work)
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
        base = "$root/api/v1/work"
    }

    @After
    fun tearDown() {
        lustro.stop()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun `the items, an item, and the events of work, and cancel, through the server`() {
        val workManager = WorkManager.getInstance(ApplicationProvider.getApplicationContext())
        val done = OneTimeWorkRequestBuilder<StepWorker>().build()
        val waiting = OneTimeWorkRequestBuilder<StepWorker>().setInitialDelay(1, TimeUnit.HOURS).addTag("later").build()
        workManager.enqueue(listOf(done, waiting)).result.get()

        val items = JSONObject(get("items").body!!.string())
        assertEquals(2, items.getJSONArray("items").length())
        assertEquals("installed", items.getJSONObject("state").getString("listeners"))
        val item = JSONObject(get("items/${done.id}").body!!.string())
        assertEquals("finished", item.getJSONArray("events").getJSONObject(3).getString("type"))
        val events = JSONObject(get("events?type=finished").body!!.string())
        assertEquals(1, events.getJSONArray("items").length())

        val cancel = post("cancel", JSONObject().put("tag", "later"))
        assertEquals(200, cancel.code)
        assertEquals(1, JSONObject(cancel.body!!.string()).getInt("cancelled"))
        assertEquals(405, get("cancel?tag=later").code)
        val error = JSONObject(get("items?state=done").body!!.string())
        assertEquals("bad_request", error.getString("error"))
        assertEquals("state", error.getString("field"))
    }

    @Test
    fun `the tab serves its schema with its own version, and _meta lists it`() {
        val schema = JSONObject(get("_schema").body!!.string())
        assertEquals("1.0", schema.getJSONObject("info").getString("version"))
        assertTrue(schema.getJSONObject("paths").has("/api/v1/work/items/{id}"))
        val tabs = JSONObject(call(Request.Builder().url("$root/api/v1/_meta")).body!!.string()).getJSONArray("tabs")
        val work = (0 until tabs.length()).map { tabs.getJSONObject(it) }.single { it.getString("id") == "work" }
        assertEquals("/api/v1/work/_schema", work.getString("schemaUrl"))
        assertEquals(401, http.newCall(Request.Builder().url("$base/items").build()).execute().code)
    }

    private fun get(path: String): Response = call(Request.Builder().url("$base/$path"))

    private fun post(path: String, body: JSONObject): Response =
        call(Request.Builder().url("$base/$path").post(body.toString().toRequestBody("application/json".toMediaType())))

    private fun call(builder: Request.Builder): Response = http.newCall(builder.header("Authorization", "Bearer $TOKEN").build()).execute()

    private companion object {
        const val TOKEN = "work-server-test-token"
    }
}
