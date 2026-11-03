package io.github.twinsen81.lustro.internal

import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugTab
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LustroServerMetaTest {
    private class SchemaTab(override val id: String, private val schema: String?) : DebugTab() {
        override val title: String = id
        override val icon: String = "S"

        override fun schema(): String? = schema

        override fun handle(request: DebugRequest): DebugResponse? = null
    }

    private lateinit var server: LustroServer
    private lateinit var tokenStore: LustroTokenStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val registry =
            DebugTabRegistry().apply {
                addTab(SchemaTab("versioned", """{"openapi":"3.1.0","info":{"title":"t","version":"2.3"},"paths":{}}"""))
                addTab(SchemaTab("unversioned", """{"openapi":"3.1.0","paths":{}}"""))
                addTab(SchemaTab("broken", "not json"))
                addTab(SchemaTab("none", null))
                start()
            }
        tokenStore = LustroTokenStore(context)
        server = LustroServer("127.0.0.1", 0, registry, DebugAssetLoader(context), tokenStore, allowedOrigins = emptyList())
        server.start(2000, false)
    }

    @After
    fun tearDown() {
        server.stop()
        server.shutdownLimiter()
    }

    @Test
    fun `meta lists each tab with the version of its own schema, and the protocol version for a schema without one`() {
        val request =
            Request.Builder()
                .url("http://127.0.0.1:${server.listeningPort}/api/v1/_meta")
                .header("Authorization", "Bearer ${tokenStore.token()}")
                .build()
        val meta = OkHttpClient().newCall(request).execute().use { JSONObject(it.body!!.string()) }
        val tabs = meta.getJSONArray("tabs")
        val versions = (0 until tabs.length()).associate { tabs.getJSONObject(it).getString("id") to tabs.getJSONObject(it).getString("version") }
        val protocol = meta.getString("protocolVersion")
        assertEquals(mapOf("versioned" to "2.3", "unversioned" to protocol, "broken" to protocol), versions)
    }
}
