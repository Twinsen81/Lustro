package io.github.twinsen81.lustro.internal

import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugTab
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

/**
 * End-to-end (over real loopback HTTP) tests for the auth / CSP / origin
 * layer in [LustroServer]. A tab serving sentinel content lets us assert that no
 * tab data or `renderContent()` output leaks before authentication.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LustroServerSecurityTest {
    private val client =
        OkHttpClient.Builder()
            .followRedirects(false)
            .build()

    private lateinit var server: LustroServer
    private lateinit var tokenStore: LustroTokenStore
    private var port: Int = 0

    private val expectedCsp =
        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
            "connect-src 'self'; img-src 'self' data:; form-action 'none'; " +
            "object-src 'none'; base-uri 'none'; frame-ancestors 'none'"

    /** A tab whose renderContent emits a sentinel that must NEVER appear pre-auth. */
    private class SentinelTab : DebugTab() {
        override val id: String = "sample"
        override val title: String = "Sample"
        override val icon: String = "S"

        override fun renderContent(): String = "<div id=\"SECRET_TAB_CONTENT\">data</div>"

        override fun handle(request: DebugRequest): DebugResponse? =
            DebugResponse.ok("{\"secret\":\"TAB_DATA\"}")
    }

    /** Keeps the last request it handled, to check what a tab actually receives. */
    private class RecordingTab : DebugTab() {
        override val id: String = "recorder"
        override val title: String = "Recorder"
        override val icon: String = "R"

        val lastRequest = AtomicReference<DebugRequest?>()

        override fun handle(request: DebugRequest): DebugResponse? {
            lastRequest.set(request)
            return DebugResponse.ok("{}")
        }
    }

    private val recordingTab = RecordingTab()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val registry = DebugTabRegistry().apply { addTab(SentinelTab()); addTab(recordingTab); start() }
        val assetLoader = DebugAssetLoader(context)
        tokenStore = LustroTokenStore(context)
        server =
            LustroServer(
                hostname = "127.0.0.1",
                port = 0,
                registry = registry,
                assetLoader = assetLoader,
                tokenStore = tokenStore,
                allowedOrigins = listOf("https://allowed.example"),
                appName = "Test App",
            )
        server.start(2000, false)
        port = server.listeningPort
    }

    @After
    fun tearDown() {
        server.stop()
        server.shutdownLimiter()
    }

    private fun url(path: String): String = "http://127.0.0.1:$port$path"

    private fun get(
        path: String,
        bearer: String? = null,
        cookie: String? = null,
        origin: String? = null,
        secFetchSite: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Response {
        val builder = Request.Builder().url(url(path)).get()
        bearer?.let { builder.header("Authorization", "Bearer $it") }
        cookie?.let { builder.header("Cookie", it) }
        origin?.let { builder.header("Origin", it) }
        secFetchSite?.let { builder.header("Sec-Fetch-Site", it) }
        extraHeaders.forEach { (name, value) -> builder.header(name, value) }
        return client.newCall(builder.build()).execute()
    }

    private fun post(
        path: String,
        json: String,
        bearer: String? = null,
        cookie: String? = null,
        origin: String? = null,
        secFetchSite: String? = null,
    ): Response {
        val builder =
            Request.Builder()
                .url(url(path))
                .post(json.toRequestBody())
                .header("Content-Type", "application/json")
        bearer?.let { builder.header("Authorization", "Bearer $it") }
        cookie?.let { builder.header("Cookie", it) }
        origin?.let { builder.header("Origin", it) }
        secFetchSite?.let { builder.header("Sec-Fetch-Site", it) }
        return client.newCall(builder.build()).execute()
    }

    private fun call(
        method: String,
        path: String,
        cookie: String,
        origin: String,
        secFetchSite: String,
    ): Response {
        val body = if (method in setOf("POST", "PUT", "PATCH")) "{}".toRequestBody() else null
        val request =
            Request.Builder()
                .url(url(path))
                .method(method, body)
                .header("Cookie", cookie)
                .header("Origin", origin)
                .header("Sec-Fetch-Site", secFetchSite)
                // NanoHTTPD sends a body even with a HEAD response, which would be
                // read as the next response on a reused connection.
                .header("Connection", "close")
                .build()
        return client.newCall(request).execute()
    }


    @Test
    fun `unauthenticated api route returns enveloped 401`() {
        get("/api/v1/sample/transactions").use { resp ->
            assertEquals(401, resp.code)
            val json = JSONObject(resp.body!!.string())
            assertEquals("unauthorized", json.getString("error"))
        }
    }

    @Test
    fun `unauthenticated meta and schema routes return 401`() {
        get("/api/v1/_meta").use { assertEquals(401, it.code) }
        get("/api/v1/_schema").use { assertEquals(401, it.code) }
        get("/api/v1/sample/_schema").use { assertEquals(401, it.code) }
        get("/api/v1/sample/_view").use { assertEquals(401, it.code) }
        get("/api/v1/sample/_view.js").use { assertEquals(401, it.code) }
    }

    @Test
    fun `valid bearer token authenticates api route`() {
        val token = tokenStore.token()
        get("/api/v1/sample/transactions", bearer = token).use { resp ->
            assertEquals(200, resp.code)
            assertTrue(resp.body!!.string().contains("TAB_DATA"))
        }
    }

    @Test
    fun `valid cookie authenticates api route`() {
        val token = tokenStore.token()
        get("/api/v1/sample/transactions", cookie = "lustro_token=$token").use { resp ->
            assertEquals(200, resp.code)
        }
    }

    @Test
    fun `wrong bearer token is rejected`() {
        get("/api/v1/sample/transactions", bearer = "not-the-token").use {
            assertEquals(401, it.code)
        }
    }

    @Test
    fun `rotation invalidates the old token`() {
        val old = tokenStore.token()
        tokenStore.rotate()
        get("/api/v1/sample/transactions", bearer = old).use {
            assertEquals(401, it.code)
        }
        get("/api/v1/sample/transactions", bearer = tokenStore.token()).use {
            assertEquals(200, it.code)
        }
    }

    @Test
    fun `tab does not receive the bearer header`() {
        val token = tokenStore.token()
        get("/api/v1/recorder/headers", bearer = token).use { assertEquals(200, it.code) }
        val headers = recordingTab.lastRequest.get()!!.headers
        assertNull(headers.get("Authorization"))
        assertTrue(headers.toList().none { (_, value) -> value.contains(token) })
    }

    @Test
    fun `tab does not receive the cookie header`() {
        val token = tokenStore.token()
        get("/api/v1/recorder/headers", cookie = "other=1; lustro_token=$token").use {
            assertEquals(200, it.code)
        }
        val headers = recordingTab.lastRequest.get()!!.headers
        assertNull(headers.get("Cookie"))
        assertTrue(headers.toList().none { (_, value) -> value.contains(token) })
    }

    @Test
    fun `tab still receives the non-credential headers`() {
        get(
            "/api/v1/recorder/headers",
            bearer = tokenStore.token(),
            extraHeaders = mapOf("X-Probe" to "1", "Accept" to "application/json"),
        ).use { assertEquals(200, it.code) }
        val headers = recordingTab.lastRequest.get()!!.headers
        assertEquals("1", headers.get("X-Probe"))
        assertEquals("application/json", headers.get("Accept"))
        assertEquals("127.0.0.1:$port", headers.get("Host"))
    }



    @Test
    fun `auth endpoint sets cookie on token match`() {
        val token = tokenStore.token()
        post("/api/v1/_auth", "{\"token\":\"$token\"}").use { resp ->
            assertEquals(200, resp.code)
            val setCookie = resp.header("Set-Cookie")
            assertNotNull(setCookie)
            assertTrue(setCookie!!.contains("lustro_token=$token"))
            assertTrue(setCookie.contains("HttpOnly"))
            assertTrue(setCookie.contains("SameSite=Strict"))
            assertTrue(setCookie.contains("Path=/"))
        }
    }

    @Test
    fun `auth endpoint rejects a bad token with 401 and no cookie`() {
        post("/api/v1/_auth", "{\"token\":\"wrong\"}").use { resp ->
            assertEquals(401, resp.code)
            assertNull(resp.header("Set-Cookie"))
        }
    }



    @Test
    fun `pre-auth chrome carries the tab bar but no tab content or data`() {
        get("/tab/sample").use { resp ->
            assertEquals(200, resp.code)
            val html = resp.body!!.string()
            // Tab bar present.
            assertTrue(html.contains("/tab/sample"))
            // shared.js (unauthenticated) loads and carries the tab id; it injects
            // the tab's auth-gated _view.css/_view.js dynamically AFTER auth.
            assertTrue(html.contains("/shared.js"))
            assertTrue(html.contains("data-lustro-tab=\"sample\""))
            // The tab's own assets must NOT be eagerly referenced in the chrome:
            // a static tag fires during pre-auth HTML parse and 401s before the
            // cookie is set, leaving the tab inert. Regression guard for the
            // dynamic-injection fix (shared.js injectTabAssets).
            assertFalse(html.contains("/api/v1/sample/_view.js"))
            assertFalse(html.contains("/api/v1/sample/_view.css"))
            // NO renderContent() output and NO tab data leaks pre-auth.
            assertFalse(html.contains("SECRET_TAB_CONTENT"))
            assertFalse(html.contains("TAB_DATA"))
        }
    }

    @Test
    fun `pre-auth chrome carries app title and icon links`() {
        get("/tab/sample").use { resp ->
            assertEquals(200, resp.code)
            val html = resp.body!!.string()
            assertTrue(html.contains("<title>Lustro - Test App</title>"))
            assertTrue(html.contains("""<link rel="icon" type="image/png" href="/lustro-icon.png">"""))
            assertTrue(html.contains("""<link rel="apple-touch-icon" href="/lustro-icon.png">"""))
        }
    }

    @Test
    fun `browser icons are unauthenticated png responses`() {
        listOf("/lustro-icon.png", "/favicon.ico").forEach { path ->
            get(path).use { resp ->
                assertEquals(200, resp.code)
                assertEquals("image/png", resp.header("Content-Type"))
                val bytes = resp.body!!.bytes()
                assertTrue("icon should be a PNG", bytes.size > 8)
                assertEquals(0x89.toByte(), bytes[0])
                assertEquals('P'.code.toByte(), bytes[1])
                assertEquals('N'.code.toByte(), bytes[2])
                assertEquals('G'.code.toByte(), bytes[3])
            }
        }
    }

    @Test
    fun `shared assets are unauthenticated`() {
        get("/shared.js").use { assertEquals(200, it.code) }
        get("/shared.css").use { assertEquals(200, it.code) }
    }



    @Test
    fun `chrome page carries the exact CSP header and nosniff`() {
        get("/tab/sample").use { resp ->
            assertEquals(expectedCsp, resp.header("Content-Security-Policy"))
            assertEquals("nosniff", resp.header("X-Content-Type-Options"))
        }
    }

    @Test
    fun `authenticated view response carries the exact CSP header`() {
        val token = tokenStore.token()
        get("/api/v1/sample/_view", bearer = token).use { resp ->
            assertEquals(expectedCsp, resp.header("Content-Security-Policy"))
            assertEquals("nosniff", resp.header("X-Content-Type-Options"))
        }
    }



    @Test
    fun `the server's own origin is allowed on state-changing requests`() {
        val token = tokenStore.token()
        post(
            "/api/v1/sample/clear",
            "{}",
            bearer = token,
            origin = "http://127.0.0.1:$port",
        ).use { assertEquals(200, it.code) }
    }

    @Test
    fun `a different localhost port origin is rejected on state-changing requests`() {
        val token = tokenStore.token()
        // A page on another localhost port is a DIFFERENT origin; trusting it would
        // let it drive the API via the ambient SameSite cookie (CSRF). Must 403.
        post(
            "/api/v1/sample/clear",
            "{}",
            bearer = token,
            origin = "http://127.0.0.1:${port + 1}",
        ).use { assertEquals(403, it.code) }
    }

    @Test
    fun `foreign origin is rejected with enveloped 403`() {
        val token = tokenStore.token()
        post(
            "/api/v1/sample/clear",
            "{}",
            bearer = token,
            origin = "https://evil.example",
            secFetchSite = "cross-site",
        ).use { resp ->
            assertEquals(403, resp.code)
            assertEquals("forbidden", JSONObject(resp.body!!.string()).getString("error"))
        }
    }

    @Test
    fun `allowed origin from config is accepted`() {
        val token = tokenStore.token()
        post(
            "/api/v1/sample/clear",
            "{}",
            bearer = token,
            origin = "https://allowed.example",
            secFetchSite = "cross-site",
        ).use { assertEquals(200, it.code) }
    }

    @Test
    fun `same-origin sec-fetch-site is accepted`() {
        val token = tokenStore.token()
        post(
            "/api/v1/sample/clear",
            "{}",
            bearer = token,
            secFetchSite = "same-origin",
        ).use { assertEquals(200, it.code) }
    }

    @Test
    fun `missing origin headers are accepted`() {
        val token = tokenStore.token()
        post("/api/v1/sample/clear", "{}", bearer = token).use {
            assertEquals(200, it.code)
        }
    }

    @Test
    fun `a GET from a page on another localhost port never reaches the tab`() {
        // What a browser sends for an <img> on a page from another local port: the
        // console's cookie, since ports share it, and Sec-Fetch-Site but no Origin.
        get(
            "/api/v1/recorder/delete-everything",
            cookie = "lustro_token=${tokenStore.token()}",
            secFetchSite = "same-site",
        ).use { resp ->
            assertEquals(403, resp.code)
            assertEquals("forbidden", JSONObject(resp.body!!.string()).getString("error"))
        }
        assertNull(recordingTab.lastRequest.get())
    }

    @Test
    fun `every method is origin checked`() {
        val cookie = "lustro_token=${tokenStore.token()}"
        listOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE").forEach { method ->
            call(method, "/api/v1/recorder/state", cookie, "http://127.0.0.1:${port + 1}", "same-site").use {
                assertEquals(method, 403, it.code)
            }
        }
        assertNull(recordingTab.lastRequest.get())
    }

    @Test
    fun `framework api routes are origin checked`() {
        val cookie = "lustro_token=${tokenStore.token()}"
        listOf("/api/v1/_meta", "/api/v1/_schema", "/api/v1/sample/_view", "/api/v1/sample/_view.js").forEach { path ->
            call("GET", path, cookie, "http://127.0.0.1:${port + 1}", "same-site").use {
                assertEquals(path, 403, it.code)
            }
        }
    }

    @Test
    fun `the console's own GETs are accepted`() {
        val cookie = "lustro_token=${tokenStore.token()}"
        // same-origin: the console's fetches and the tab assets it loads; none: a
        // URL typed into the address bar.
        listOf("same-origin", "none").forEach { site ->
            get("/api/v1/recorder/state", cookie = cookie, secFetchSite = site).use {
                assertEquals(site, 200, it.code)
            }
        }
        assertNotNull(recordingTab.lastRequest.get())
    }

}
