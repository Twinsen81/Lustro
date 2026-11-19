package io.github.twinsen81.lustro.internal

import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugTab
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The server sends every response uncompressed, also to a client that accepts
 * gzip. NanoHTTPD's gzip path never ends its Deflater, so each compressed
 * response would leak native memory and trigger a StrictMode violation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LustroServerEncodingTest {
    private class TextTab : DebugTab() {
        override val id: String = "sample"
        override val title: String = "Sample"
        override val icon: String = "S"

        override fun handle(request: DebugRequest): DebugResponse? =
            if (request.path == "text") DebugResponse.text("plain text") else null
    }

    // OkHttp decodes gzip only when it adds Accept-Encoding itself. With the header
    // set by hand, the response arrives as the server sent it.
    private val client = OkHttpClient()
    private lateinit var server: LustroServer
    private lateinit var token: String

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val registry = DebugTabRegistry().apply { addTab(TextTab()); start() }
        val tokenStore = LustroTokenStore(context)
        token = tokenStore.token()
        server = LustroServer("127.0.0.1", 0, registry, DebugAssetLoader(context), tokenStore, allowedOrigins = emptyList())
        server.start(2000, false)
    }

    @After
    fun tearDown() {
        server.stop()
        server.shutdownLimiter()
    }

    @Test
    fun `a client that accepts gzip gets every kind of response uncompressed`() {
        val paths = listOf("/tab/sample", "/shared.css", "/api/v1/_meta", "/api/v1/sample/text", "/api/v1/sample/missing")
        for (path in paths) {
            val request =
                Request.Builder()
                    .url("http://127.0.0.1:${server.listeningPort}$path")
                    .header("Accept-Encoding", "gzip")
                    .header("Authorization", "Bearer $token")
                    .build()
            client.newCall(request).execute().use { response ->
                assertNull(path, response.header("Content-Encoding"))
                val bytes = response.body!!.bytes()
                assertTrue(path, bytes.isNotEmpty())
                // A gzip stream starts with 1f 8b.
                assertTrue(path, bytes.size < 2 || bytes[0] != 0x1f.toByte() || bytes[1] != 0x8b.toByte())
            }
        }
    }

    @Test
    fun `a tab's text response arrives as the tab wrote it`() {
        val request =
            Request.Builder()
                .url("http://127.0.0.1:${server.listeningPort}/api/v1/sample/text")
                .header("Accept-Encoding", "gzip, deflate, br")
                .header("Authorization", "Bearer $token")
                .build()
        client.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
            assertNull(response.header("Content-Encoding"))
            assertEquals("plain text", response.body!!.string())
        }
    }
}
