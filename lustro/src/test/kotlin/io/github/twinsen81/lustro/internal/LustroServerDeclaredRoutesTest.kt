@file:OptIn(ExperimentalDebugRoutes::class)

package io.github.twinsen81.lustro.internal

import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugResponseException
import io.github.twinsen81.lustro.DebugRoutes
import io.github.twinsen81.lustro.DebugTab
import io.github.twinsen81.lustro.ExperimentalDebugRoutes
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
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
 * A tab that declares its routes, through the real server: `_meta` lists it with the version
 * of its routes, `_schema` serves the generated document, the server warns about an unknown
 * query parameter from that document, and the router answers `405` and `400` itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LustroServerDeclaredRoutesTest {
    private class DeclaredTab : DebugTab() {
        override val id: String = "declared"
        override val title: String = "Declared"
        override val icon: String = "D"

        override fun routes(routes: DebugRoutes) {
            routes.version = "3.2"
            routes.get("items") {
                val limit = query.int("limit") { min = 1; max = 10 }.withDefault(5)
                handle { call -> DebugResponse.ok("""{"limit":${call[limit]}}""") }
            }
            routes.post("items") {
                val name = body.string("name") { minLength = 1 }.required()
                handle { call -> DebugResponse.ok("""{"name":"${call[name]}"}""") }
            }
        }
    }

    private class BrokenTab : DebugTab() {
        override val id: String = "broken"
        override val title: String = "Broken"
        override val icon: String = "B"

        override fun routes(routes: DebugRoutes) {
            routes.get("items") { handle { DebugResponse.ok("{}") } }
        }
    }

    /** Routes by hand, and ends a request from a helper with a DebugResponseException. */
    private class ManualTab : DebugTab() {
        override val id: String = "manual"
        override val title: String = "Manual"
        override val icon: String = "M"

        override fun handle(request: DebugRequest): DebugResponse? =
            if (request.path == "conflict") throw DebugResponseException(DebugResponse.error("Busy", status = 409, field = "id")) else null
    }

    private val client = OkHttpClient()
    private lateinit var server: LustroServer
    private lateinit var token: String

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val registry =
            DebugTabRegistry().apply {
                addTab(DeclaredTab())
                addTab(BrokenTab())
                addTab(ManualTab())
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
    fun `meta lists the declared tab with its version, and leaves out a tab whose declarations are wrong`() {
        val tabs = JSONObject(call("GET", "_meta").use { it.body!!.string() }).getJSONArray("tabs")
        val listed = (0 until tabs.length()).map { tabs.getJSONObject(it) }
        assertEquals(listOf("declared"), listed.map { it.getString("id") })
        assertEquals("3.2", listed.single().getString("version"))
    }

    @Test
    fun `the schema route serves the generated document`() {
        val document = JSONObject(call("GET", "declared/_schema").use { it.body!!.string() })
        val items = document.getJSONObject("paths").getJSONObject("/api/v1/declared/items")
        assertEquals(setOf("get", "post"), items.keys().asSequence().toSet())
        call("GET", "broken/_schema").use {
            assertEquals(500, it.code)
            assertTrue(JSONObject(it.body!!.string()).getString("message").contains("routes.version"))
        }
    }

    @Test
    fun `the router answers, the server warns about an undeclared query parameter`() {
        call("GET", "declared/items?limit=3").use {
            assertEquals(200, it.code)
            assertEquals(3, JSONObject(it.body!!.string()).getInt("limit"))
            assertNull(it.header("Lustro-Warning"))
        }
        call("GET", "declared/items?limt=3").use {
            assertEquals(5, JSONObject(it.body!!.string()).getInt("limit"))
            assertEquals("unknown query parameter \"limt\"", it.header("Lustro-Warning"))
        }
        call("GET", "declared/items?limit=99").use {
            assertEquals(400, it.code)
            assertEquals("limit", JSONObject(it.body!!.string()).getString("field"))
        }
        call("POST", "declared/items", """{"name":""}""").use {
            assertEquals(400, it.code)
            assertEquals("name", JSONObject(it.body!!.string()).getString("field"))
        }
        call("DELETE", "declared/items").use {
            assertEquals(405, it.code)
            assertEquals("GET, POST", it.header("Allow"))
            assertEquals("declared/items takes GET or POST, not DELETE", JSONObject(it.body!!.string()).getString("message"))
        }
        assertEquals(404, call("GET", "declared/nothing").use { it.code })
        assertEquals(500, call("GET", "broken/items").use { it.code })
    }

    @Test
    fun `a DebugResponseException from any tab is its response`() {
        call("GET", "manual/conflict").use {
            assertEquals(409, it.code)
            val error = JSONObject(it.body!!.string())
            assertEquals("conflict", error.getString("error"))
            assertEquals("id", error.getString("field"))
        }
    }

    private fun call(method: String, path: String, body: String? = null): Response {
        val requestBody = if (method == "GET") null else (body ?: "{}").toRequestBody("application/json".toMediaType())
        val request =
            Request.Builder()
                .url("http://127.0.0.1:${server.listeningPort}/api/v1/$path")
                .header("Authorization", "Bearer $token")
                .method(method, requestBody)
                .build()
        return client.newCall(request).execute()
    }
}
