package io.github.twinsen81.lustro.internal

import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugTab
import io.github.twinsen81.lustro.network.NetworkDebugTab
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A route that the tab does not handle with the request's method gets a `405` with an
 * `Allow` header when the tab's OpenAPI document declares the path with other methods, so
 * that a client does not conclude that the route does not exist. Else a `404`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LustroServerMethodNotAllowedTest {
    /** Handles nothing, as a tab that returns `null` for a method that it does not take. */
    private class NullTab(override val id: String, private val schema: String?) : DebugTab() {
        override val title: String = id
        override val icon: String = "N"

        override fun schema(): String? = schema

        override fun handle(request: DebugRequest): DebugResponse? = null
    }

    private val client = OkHttpClient()
    private lateinit var server: LustroServer
    private lateinit var token: String

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val registry =
            DebugTabRegistry().apply {
                addTab(NetworkDebugTab.create())
                addTab(NullTab("plain", null))
                addTab(
                    NullTab(
                        "dynamic",
                        """
                        {"openapi":"3.1.0","paths":{
                          "/api/v1/dynamic":{"get":{},"post":{}},
                          "/api/v1/dynamic/items/{id}":{"get":{},"delete":{}},
                          "/api/v1/dynamic/items/special":{"post":{}},
                          "/api/v1/dynamics":{"put":{}},
                          "/api/v1/elsewhere/items":{"post":{}}
                        }}
                        """.trimIndent(),
                    ),
                )
                start()
            }
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
    fun `a wrong method on a Network route gets a 405 with Allow and the envelope`() {
        call("GET", "network/pause").use {
            assertEquals(405, it.code)
            assertEquals("POST", it.header("Allow"))
            val envelope = JSONObject(it.body!!.string())
            assertEquals("method_not_allowed", envelope.getString("error"))
            assertEquals("network/pause takes POST, not GET", envelope.getString("message"))
        }
        call("POST", "network/transactions").use {
            assertEquals(405, it.code)
            assertEquals("GET", it.header("Allow"))
        }
    }

    @Test
    fun `Allow lists every method of the path, and a templated path matches`() {
        call("DELETE", "network/rules").use {
            assertEquals(405, it.code)
            assertEquals("GET, POST", it.header("Allow"))
            assertEquals("network/rules takes GET or POST, not DELETE", JSONObject(it.body!!.string()).getString("message"))
        }
        assertEquals("GET", call("POST", "network/transactions/no-such-id").use { it.header("Allow") })
        assertEquals("GET", call("POST", "network/transactions/_/export").use { it.header("Allow") })
        assertEquals("GET", call("POST", "network/websockets/no-such-id/events").use { it.header("Allow") })
    }

    @Test
    fun `a path that the document does not declare, or declares with the method, is still a 404`() {
        call("GET", "network/no-such-route").use {
            assertEquals(404, it.code)
            assertNull(it.header("Allow"))
        }
        // The app registered no sender: the route answers null for the method that it takes.
        call("POST", "network/send").use {
            assertEquals(404, it.code)
            assertNull(it.header("Allow"))
        }
        assertEquals(404, call("GET", "network/transactions/a/b/c/d").use { it.code })
    }

    @Test
    fun `a tab without a schema gets a 404 for every method`() {
        for (method in listOf("GET", "POST", "DELETE")) {
            call(method, "plain/anything").use {
                assertEquals(method, 404, it.code)
                assertNull(it.header("Allow"))
            }
        }
    }

    @Test
    fun `a literal segment wins over a template, and another tab's paths do not count`() {
        call("GET", "dynamic/items/special").use {
            assertEquals(405, it.code)
            assertEquals("POST", it.header("Allow"))
        }
        assertEquals("GET, DELETE", call("POST", "dynamic/items/7").use { it.header("Allow") })
        // The tab returns null for a declared method too: then the route is a 404.
        assertEquals(404, call("GET", "dynamic/items/7").use { it.code })
        assertEquals(404, call("GET", "dynamic/elsewhere/items").use { it.code })
    }

    @Test
    fun `the root path of a document gets a 405 that names the tab alone`() {
        for (path in listOf("dynamic", "dynamic/")) {
            call("PUT", path).use {
                assertEquals(path, 405, it.code)
                assertEquals("GET, POST", it.header("Allow"))
                assertEquals("dynamic takes GET or POST, not PUT", JSONObject(it.body!!.string()).getString("message"))
            }
        }
        assertEquals(404, call("GET", "dynamic").use { it.code })
    }

    @Test
    fun `one slash at the end of the path does not count`() {
        call("POST", "dynamic/items/7/").use {
            assertEquals(405, it.code)
            assertEquals("GET, DELETE", it.header("Allow"))
            assertEquals("dynamic/items/7 takes GET or DELETE, not POST", JSONObject(it.body!!.string()).getString("message"))
        }
        assertEquals("POST", call("GET", "dynamic/items/special/").use { it.header("Allow") })
        assertEquals(404, call("GET", "dynamic/items/special//").use { it.code })
    }

    private fun call(method: String, path: String): Response {
        val body = if (method == "GET") null else "{}".toRequestBody("application/json".toMediaType())
        val request =
            Request.Builder()
                .url("http://127.0.0.1:${server.listeningPort}/api/v1/$path")
                .header("Authorization", "Bearer $token")
                .method(method, body)
                .build()
        return client.newCall(request).execute()
    }
}
