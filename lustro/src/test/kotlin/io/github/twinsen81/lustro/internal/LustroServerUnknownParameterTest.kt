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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The `Lustro-Warning` header over real loopback HTTP: a route ignores a query parameter
 * that it does not know, so the server names each one that the tab's schema does not
 * declare for the route.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LustroServerUnknownParameterTest {
    private class PlainTab(override val id: String, @Volatile var schema: String?) : DebugTab() {
        override val title: String = id
        override val icon: String = "P"

        override fun schema(): String? = schema

        override fun handle(request: DebugRequest): DebugResponse = DebugResponse.ok("""{"ok":true}""")
    }

    private val client = OkHttpClient()
    private val changing = PlainTab("changing", schemaDeclaring("before"))
    private lateinit var server: LustroServer
    private lateinit var token: String

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val registry =
            DebugTabRegistry().apply {
                addTab(NetworkDebugTab.create())
                addTab(PlainTab("plain", null))
                addTab(
                    PlainTab(
                        "dynamic",
                        """
                        {"openapi":"3.1.0","paths":{
                          "/api/v1/dynamic":{"get":{"parameters":[{"name":"limit","in":"query"}]}},
                          "/api/v1/dynamic/items/{id}":{
                            "parameters":[{"name":"shared","in":"query"}],
                            "get":{"parameters":[{"${'$'}ref":"#/components/parameters/Fields"},{"name":"id","in":"path"}]}
                          },
                          "/api/v1/dynamic/items/special":{
                            "get":{"parameters":[{"name":"x","in":"query"}]}
                          }},
                         "components":{"parameters":{"Fields":{"name":"fields","in":"query"}}}}
                        """.trimIndent(),
                    ),
                )
                addTab(changing)
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

    private fun warning(pathAndQuery: String, post: Boolean = false): String? {
        val builder =
            Request.Builder()
                .url("http://127.0.0.1:${server.listeningPort}/api/v1/$pathAndQuery")
                .header("Authorization", "Bearer $token")
        if (post) builder.post("{}".toRequestBody("application/json".toMediaType()))
        return client.newCall(builder.build()).execute().use { it.header("Lustro-Warning") }
    }

    @Test
    fun `a request with only declared parameters gets no warning`() {
        assertNull(warning("network/transactions?search=orders&regex=true&cursor=abc&since=0"))
        assertNull(warning("network/transactions"))
        assertNull(warning("network/transactions/_/export?format=har&ids=a,b"))
    }

    @Test
    fun `an undeclared parameter gets a warning that names it`() {
        assertEquals("unknown query parameter \"uniqueName\"", warning("network/transactions?uniqueName=X&search=a"))
    }

    @Test
    fun `each undeclared parameter is named, in one header`() {
        assertEquals(
            "unknown query parameter \"a\", unknown query parameter \"b\"",
            warning("network/transactions?b=1&a=2&search=x"),
        )
    }

    @Test
    fun `a templated path is matched, also when the route answers an error`() {
        assertEquals(
            "unknown query parameter \"level\"",
            warning("network/websockets/no-such-id/events?limit=5&direction=sent&level=E"),
        )
    }

    @Test
    fun `a POST route that declares no query parameter warns about one`() {
        assertEquals("unknown query parameter \"force\"", warning("network/clear?force=true", post = true))
    }

    @Test
    fun `a route that the schema does not describe and a tab without a schema get no check`() {
        assertNull(warning("network/no-such-route?x=1"))
        assertNull(warning("plain/anything?x=1"))
    }

    @Test
    fun `a dynamic schema with path-level and referenced parameters is read`() {
        assertNull(warning("dynamic/items/7?shared=1&fields=a"))
        assertEquals("unknown query parameter \"other\"", warning("dynamic/items/7?shared=1&other=2"))
    }

    @Test
    fun `a name that could end the header or split the list is escaped`() {
        // %0D%0A is CR LF, %22 a quote, and %C3%A9 an e with an acute accent.
        assertEquals(
            "unknown query parameter \"a\\\"b\\u000d\\u000ac\\u00e9\"",
            warning("network/transactions?a%22b%0D%0Ac%C3%A9=1"),
        )
    }

    @Test
    fun `a literal segment wins over a template of the same length`() {
        // The templated route comes first in the document, so the first match would be wrong.
        assertNull(warning("dynamic/items/special?x=1"))
        assertEquals("unknown query parameter \"shared\"", warning("dynamic/items/special?shared=1"))
        assertEquals("unknown query parameter \"x\"", warning("dynamic/items/7?x=1"))
    }

    @Test
    fun `the root path of the tab is read, and one slash at the end of a path does not count`() {
        assertNull(warning("dynamic?limit=5"))
        assertEquals("unknown query parameter \"limt\"", warning("dynamic?limt=5"))
        assertEquals("unknown query parameter \"limt\"", warning("dynamic/?limt=5"))
        assertNull(warning("dynamic/items/special/?x=1"))
        assertEquals("unknown query parameter \"y\"", warning("dynamic/items/special/?y=1"))
    }

    @Test
    fun `a dynamic schema that changes is read again`() {
        assertEquals("unknown query parameter \"after\"", warning("changing/list?before=1&after=1"))
        changing.schema = schemaDeclaring("after")
        assertEquals("unknown query parameter \"before\"", warning("changing/list?before=1&after=1"))
    }

    private fun schemaDeclaring(name: String): String =
        """{"openapi":"3.1.0","paths":{"/api/v1/changing/list":{"get":{"parameters":[{"name":"$name","in":"query"}]}}}}"""
}
