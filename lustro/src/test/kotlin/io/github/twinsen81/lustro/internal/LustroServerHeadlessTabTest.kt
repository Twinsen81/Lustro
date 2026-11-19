package io.github.twinsen81.lustro.internal

import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugTab
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

/**
 * End-to-end (over loopback HTTP) tests that a headless tab has routes but no
 * page, and that a form body reaches a tab's [DebugRequest.formParams].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LustroServerHeadlessTabTest {
    private val client = OkHttpClient.Builder().followRedirects(false).build()
    private lateinit var server: LustroServer
    private lateinit var token: String

    private class VisibleTab : DebugTab() {
        override val id: String = "visible"
        override val title: String = "Visible"
        override val icon: String = "V"
    }

    /** A tab with routes only. It keeps the last request it handled. */
    private class HeadlessTab : DebugTab() {
        override val id: String = "headless"
        override val title: String = "Headless"
        override val icon: String = "H"
        override val showInTabBar: Boolean = false

        val lastRequest = AtomicReference<DebugRequest?>()

        override fun renderScript(): String = "/* headless script */"

        override fun handle(request: DebugRequest): DebugResponse? {
            lastRequest.set(request)
            return DebugResponse.ok("{\"ok\":true}")
        }
    }

    private val headless = HeadlessTab()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val registry = DebugTabRegistry().apply { addTab(VisibleTab()); addTab(headless); start() }
        val tokenStore = LustroTokenStore(context)
        token = tokenStore.token()
        server =
            LustroServer(
                hostname = "127.0.0.1",
                port = 0,
                registry = registry,
                assetLoader = DebugAssetLoader(context),
                tokenStore = tokenStore,
                allowedOrigins = emptyList(),
            )
        server.start(2000, false)
    }

    @After
    fun tearDown() {
        server.stop()
        server.shutdownLimiter()
    }

    private fun call(path: String, configure: Request.Builder.() -> Unit = {}): Response =
        client.newCall(
            Request.Builder()
                .url("http://127.0.0.1:${server.listeningPort}$path")
                .header("Authorization", "Bearer $token")
                .apply(configure)
                .build(),
        ).execute()

    @Test
    fun `the page of a headless tab is a 404, as for an unknown tab`() {
        val unknown = call("/tab/nope").use { it.code to JSONObject(it.body!!.string()).getString("error") }
        call("/tab/headless").use { resp ->
            assertEquals(404, resp.code)
            val envelope = JSONObject(resp.body!!.string())
            assertEquals(unknown, resp.code to envelope.getString("error"))
            assertEquals("Tab not found: headless", envelope.getString("message"))
        }
    }

    @Test
    fun `the page of a visible tab does not link the headless tab`() {
        call("/tab/visible").use { resp ->
            assertEquals(200, resp.code)
            val html = resp.body!!.string()
            assertTrue(html.contains("/tab/visible"))
            assertFalse(html.contains("/tab/headless"))
        }
    }

    @Test
    fun `the routes and the script of a headless tab still work`() {
        call("/api/v1/headless/ping").use { assertEquals(200, it.code) }
        call("/api/v1/headless/_view.js").use { resp ->
            assertEquals(200, resp.code)
            assertTrue(resp.body!!.string().contains("headless script"))
        }
    }

    @Test
    fun `a form body reaches the tab as form parameters and not as query parameters`() {
        val form = FormBody.Builder().add("id", "offline sync").add("tag", "a&b").add("tag", "ü").build()
        call("/api/v1/headless/save?from=query") { post(form) }.use { assertEquals(200, it.code) }

        val request = headless.lastRequest.get()!!
        assertEquals(mapOf("id" to listOf("offline sync"), "tag" to listOf("a&b", "ü")), request.formParams())
        assertEquals(mapOf("from" to listOf("query")), request.queryParams)
    }
}
