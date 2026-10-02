package io.github.twinsen81.lustro.internal

import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.network.NetworkDebugTab
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Network tab's WebSocket payload route over real loopback HTTP. A payload
 * is content from an app's server, served from the console's origin, so what
 * matters is what a browser receives: no payload may render, whatever it holds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebSocketPayloadRouteSecurityTest {
    /** A socket that goes nowhere: the test only needs its sends recorded. */
    private class SilentSocket(private val request: Request) : WebSocket {
        override fun request(): Request = request

        override fun queueSize(): Long = 0L

        override fun send(text: String): Boolean = true

        override fun send(bytes: ByteString): Boolean = true

        override fun close(code: Int, reason: String?): Boolean = true

        override fun cancel() = Unit
    }

    private val client = OkHttpClient()
    private val networkTab = NetworkDebugTab.create()
    private lateinit var server: LustroServer
    private lateinit var token: String

    private val serverCsp =
        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
            "connect-src 'self'; img-src 'self' data:; form-action 'none'; " +
            "object-src 'none'; base-uri 'none'; frame-ancestors 'none'"

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

    private fun api(path: String, authorized: Boolean = true): Response =
        client.newCall(
            Request.Builder()
                .url("http://127.0.0.1:${server.listeningPort}/api/v1/network/$path")
                .apply { if (authorized) header("Authorization", "Bearer $token") }
                .build(),
        ).execute()

    /** Records one sent message and returns the path of its payload. */
    private fun payloadPathAfter(send: (WebSocket) -> Unit): String {
        val sockets = networkTab.wrapWebSocketFactory { request, _ -> SilentSocket(request) }
        send(sockets.newWebSocket(Request.Builder().url("https://example.com/socket").build(), object : WebSocketListener() {}))
        assertTrue(networkTab.webSocketCapture.awaitCaptures())
        val id = api("websockets").use { JSONObject(it.body!!.string()).getJSONArray("items").getJSONObject(0).getString("id") }
        val seq = api("websockets/$id/events").use { JSONObject(it.body!!.string()).getJSONArray("items").getJSONObject(0).getInt("seq") }
        return "websockets/$id/events/$seq/payload"
    }

    private fun assertCannotRunScript(resp: Response) {
        assertEquals("attachment", resp.header("Content-Disposition"))
        // One header, carrying both policies: a browser enforces each of them.
        assertEquals(listOf("$serverCsp, default-src 'none'; sandbox"), resp.headers("Content-Security-Policy"))
        assertEquals("nosniff", resp.header("X-Content-Type-Options"))
    }

    @Test
    fun `a text payload that is a page is plain text, an attachment, and in a sandbox`() {
        val html = "<html><body><script>fetch('/api/v1/network/clear',{method:'POST'})</script></body></html>"
        val path = payloadPathAfter { it.send(html) }

        api(path).use { resp ->
            assertEquals(200, resp.code)
            assertEquals("text/plain; charset=utf-8", resp.header("Content-Type"))
            assertCannotRunScript(resp)
            assertEquals(html, resp.body!!.string())
        }
    }

    @Test
    fun `a binary payload is an attachment of bytes, byte for byte`() {
        val bytes = byteArrayOf(0x3c, 0x73, 0x76, 0x67, 0x3e, -1, 0)
        val path = payloadPathAfter { it.send(bytes.toByteString()) }

        api(path).use { resp ->
            assertEquals(200, resp.code)
            assertEquals("application/octet-stream", resp.header("Content-Type"))
            assertCannotRunScript(resp)
            assertArrayEquals(bytes, resp.body!!.bytes())
        }
    }

    @Test
    fun `the WebSocket routes need the token like every other API route`() {
        val path = payloadPathAfter { it.send("hello") }

        api(path, authorized = false).use { assertEquals(401, it.code) }
        api("websockets", authorized = false).use { assertEquals(401, it.code) }
    }
}
