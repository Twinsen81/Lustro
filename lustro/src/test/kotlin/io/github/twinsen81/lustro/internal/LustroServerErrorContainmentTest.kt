package io.github.twinsen81.lustro.internal

import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugTab
import io.github.twinsen81.lustro.UncaughtExceptionRecorder
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end (over loopback HTTP) tests that an [Error] thrown while serving a
 * request becomes an enveloped 500 instead of escaping the server. NanoHTTPD
 * catches only [Exception], and on Android an uncaught Error kills the host app.
 * Each case covers a different boundary: the per-tab `handle()` guard, a tab hook
 * run on the request worker, and a route served on the connection thread itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LustroServerErrorContainmentTest {
    @get:Rule
    val uncaught = UncaughtExceptionRecorder()

    private val client = OkHttpClient.Builder().followRedirects(false).build()
    private val tab = ThrowingTab()
    private lateinit var server: LustroServer
    private lateinit var token: String

    /** A tab whose routes and hooks throw Errors rather than Exceptions. */
    private class ThrowingTab : DebugTab() {
        override val id: String = "probe"
        override val title: String = "Probe"
        override val icon: String = "P"

        @Volatile
        var failTabBar = false

        override val showInTabBar: Boolean
            get() = if (failTabBar) throw NoClassDefFoundError("simulated") else true

        override fun renderContent(): String = throw StackOverflowError("simulated")

        override fun handle(request: DebugRequest): DebugResponse? =
            when (request.path) {
                "todo" -> TODO("simulated")
                "oom" -> throw OutOfMemoryError("simulated")
                "ok" -> DebugResponse.ok("{\"ok\":true}")
                else -> null
            }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val registry = DebugTabRegistry().apply { addTab(tab); start() }
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

    private fun get(path: String): Response =
        client.newCall(
            Request.Builder()
                .url("http://127.0.0.1:${server.listeningPort}$path")
                .header("Authorization", "Bearer $token")
                .build(),
        ).execute()

    private fun assertInternalError(path: String) {
        get(path).use { resp ->
            assertEquals(500, resp.code)
            assertEquals("internal_error", JSONObject(resp.body!!.string()).getString("error"))
        }
    }

    @Test
    fun `a handler that calls TODO() gets an enveloped 500 and the server keeps serving`() {
        assertInternalError("/api/v1/probe/todo")
        get("/api/v1/probe/ok").use { assertEquals(200, it.code) }
        uncaught.assertNothingUncaught()
    }

    @Test
    fun `an OutOfMemoryError from a handler gets an enveloped 500`() {
        assertInternalError("/api/v1/probe/oom")
        get("/api/v1/probe/ok").use { assertEquals(200, it.code) }
        uncaught.assertNothingUncaught()
    }

    @Test
    fun `an Error from a tab hook on the request worker gets an enveloped 500`() {
        // renderContent() runs outside the handle() guard; the limiter rethrows
        // its Error on the connection thread, where serve() must answer.
        assertInternalError("/api/v1/probe/_view")
        get("/api/v1/probe/ok").use { assertEquals(200, it.code) }
        uncaught.assertNothingUncaught()
    }

    @Test
    fun `an Error on a route served outside the limiter gets an enveloped 500`() {
        // `/` runs on the connection thread and reads showInTabBar to pick a tab.
        tab.failTabBar = true
        assertInternalError("/")
        tab.failTabBar = false
        get("/").use { assertTrue("redirects to the default tab", it.isRedirect) }
        uncaught.assertNothingUncaught()
    }
}
