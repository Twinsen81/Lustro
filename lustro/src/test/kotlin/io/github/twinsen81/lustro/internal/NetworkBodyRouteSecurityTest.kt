package io.github.twinsen81.lustro.internal

import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.internal.network.NetworkTrafficStore
import io.github.twinsen81.lustro.network.CapturedBody
import io.github.twinsen81.lustro.network.CapturedResponse
import io.github.twinsen81.lustro.network.NetworkDebugTab
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Network tab's body route over real loopback HTTP. A captured body is
 * content from an app's server, served from the console's origin, so what
 * matters is what a browser receives: the headers that decide whether it
 * renders the body, and whether script in it can run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkBodyRouteSecurityTest {
    private val client = OkHttpClient()
    private val networkTab = NetworkDebugTab.create()
    private lateinit var server: LustroServer
    private lateinit var token: String

    private val serverCsp =
        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
            "connect-src 'self'; img-src 'self' data:; form-action 'none'; " +
            "object-src 'none'; base-uri 'none'; frame-ancestors 'none'"

    private val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val registry = DebugTabRegistry().apply { addTab(networkTab); start() }
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

    private fun captureResponse(contentType: String, body: CapturedBody): String {
        val store = networkTab.captureSink as NetworkTrafficStore
        val id = store.beginRequest("https://example.com/page", "GET", Headers.EMPTY, null, null)
        store.completeRequest(
            id,
            CapturedResponse.Builder(200, 5).headers(Headers.of("Content-Type" to contentType)).body(body).build(),
        )
        store.awaitCaptures()
        return id.value
    }

    private fun getBody(id: String): Response =
        client.newCall(
            Request.Builder()
                .url("http://127.0.0.1:${server.listeningPort}/api/v1/network/transactions/$id/body/response")
                .header("Authorization", "Bearer $token")
                .build(),
        ).execute()

    private fun assertCannotRunScript(resp: Response) {
        assertEquals("attachment", resp.header("Content-Disposition"))
        // One header, carrying both policies: a browser enforces each of them.
        assertEquals(listOf("$serverCsp, default-src 'none'; sandbox"), resp.headers("Content-Security-Policy"))
        assertEquals("nosniff", resp.header("X-Content-Type-Options"))
    }

    @Test
    fun `an HTML body is an attachment in a sandbox`() {
        val html = "<html><body><script>fetch('/api/v1/network/clear',{method:'POST'})</script></body></html>"
        val id = captureResponse("text/html; charset=utf-8", CapturedBody(html))

        getBody(id).use { resp ->
            assertEquals(200, resp.code)
            assertEquals("text/html; charset=utf-8", resp.header("Content-Type"))
            assertCannotRunScript(resp)
            assertEquals(html, resp.body!!.string())
        }
    }

    @Test
    fun `an SVG body is an attachment in a sandbox`() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" onload="alert(document.cookie)"/>"""
        val id = captureResponse("image/svg+xml", CapturedBody(svg))

        getBody(id).use { resp ->
            assertEquals(200, resp.code)
            assertEquals("image/svg+xml; charset=utf-8", resp.header("Content-Type"))
            assertCannotRunScript(resp)
            assertEquals(svg, resp.body!!.string())
        }
    }

    @Test
    fun `a PNG body is served inline with its type, byte for byte`() {
        val id = captureResponse("image/png", CapturedBody(text = null, byteSize = 8, bytes = png))

        getBody(id).use { resp ->
            assertEquals(200, resp.code)
            assertEquals("image/png", resp.header("Content-Type"))
            assertNull(resp.header("Content-Disposition"))
            assertEquals(listOf(serverCsp), resp.headers("Content-Security-Policy"))
            assertEquals("nosniff", resp.header("X-Content-Type-Options"))
            assertArrayEquals(png, resp.body!!.bytes())
        }
    }

    @Test
    fun `the body route needs the token like every other API route`() {
        val id = captureResponse("image/png", CapturedBody(text = null, bytes = png))

        val request =
            Request.Builder()
                .url("http://127.0.0.1:${server.listeningPort}/api/v1/network/transactions/$id/body/response")
                .build()
        client.newCall(request).execute().use { resp -> assertEquals(401, resp.code) }
    }
}
