package io.github.twinsen81.lustro.sample

import android.app.Activity
import android.app.Application
import android.os.Looper
import androidx.startup.InitializationProvider
import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.DebugConfig
import io.github.twinsen81.lustro.Lustro
import io.github.twinsen81.lustro.LustroEndpoint
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The flags tab declares its routes, as `write-a-tab.md` shows: a wrong method gets a `405`
 * with `Allow` from the declarations, and `_meta` lists the tab with its generated document.
 */
@RunWith(RobolectricTestRunner::class)
class SampleFlagsTabServerTest {
    private val http = OkHttpClient()
    private lateinit var lustro: Lustro
    private lateinit var endpoint: LustroEndpoint

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        lustro =
            Lustro.builder(app)
                .config(DebugConfig.builder().serverPort(0).build())
                .addTab(SampleFlagsTab(app))
                .build()
        // The server listens while the app is in the foreground: an activity puts it there.
        // Robolectric creates no content provider, so ProcessLifecycleOwner is attached here.
        Robolectric.setupContentProvider(InitializationProvider::class.java)
        Robolectric.buildActivity(Activity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        lustro.start()
        val deadline = System.currentTimeMillis() + 5_000
        while (lustro.endpoint() == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        endpoint = requireNotNull(lustro.endpoint())
    }

    @After
    fun tearDown() {
        lustro.stop()
    }

    @Test
    fun `a wrong method on a path that the document declares gets a 405 that lists the methods`() {
        val toggle = call("GET", "toggle")
        assertEquals(405, toggle.code)
        assertEquals("POST", toggle.header("Allow"))
        val envelope = JSONObject(toggle.body!!.string())
        assertEquals("method_not_allowed", envelope.getString("error"))
        assertEquals("flags/toggle takes POST, not GET", envelope.getString("message"))

        assertEquals("GET", call("POST", "snapshot").header("Allow"))
    }

    @Test
    fun `a path that the document does not declare is still a 404, without Allow`() {
        val missing = call("GET", "nothing")
        assertEquals(404, missing.code)
        assertNull(missing.header("Allow"))
        assertEquals(200, call("GET", "snapshot").code)
    }

    @Test
    fun `meta lists the tab, and its schema is the generated document`() {
        val meta = JSONObject(call("GET", "../_meta").body!!.string())
        val tabs = meta.getJSONArray("tabs")
        val flags = (0 until tabs.length()).map { tabs.getJSONObject(it) }.single { it.getString("id") == "flags" }
        assertEquals("1.0", flags.getString("version"))
        val schema = JSONObject(call("GET", "_schema").body!!.string())
        assertEquals("toggleFlag", schema.getJSONObject("paths").getJSONObject("/api/v1/flags/toggle").getJSONObject("post").getString("operationId"))
    }

    private fun call(method: String, path: String): Response {
        val body = if (method == "GET") null else "{}".toRequestBody("application/json".toMediaType())
        val request =
            Request.Builder()
                .url("http://${endpoint.host}:${endpoint.port}/api/v1/flags/$path")
                .header("Authorization", "Bearer ${endpoint.token}")
                .method(method, body)
                .build()
        return http.newCall(request).execute()
    }
}
