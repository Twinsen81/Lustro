package io.github.twinsen81.lustro

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.network.NetworkDebugTab
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for the facade wiring bug where `Lustro.networkInterceptor()`
 * returned a pass-through even though a [NetworkDebugTab] was registered, because
 * the registry's `sortedTabs` was empty until `start()` (which runs after the
 * interceptor + config are resolved in `build()`). This exercises the REAL public
 * path — `builder().addTab(NetworkDebugTab.create()).build().networkInterceptor()`
 * — rather than the internal capture seam the other tests use.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LustroNetworkWiringTest {
    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `networkInterceptor captures traffic into the registered network tab`() {
        val mockServer = MockWebServer()
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        mockServer.start()
        try {
            val tab = NetworkDebugTab.create()
            val lustro = Lustro.builder(app).addTab(tab).build()

            val client = OkHttpClient.Builder().addInterceptor(lustro.networkInterceptor()).build()
            val url = mockServer.url("/wiring-check").toString()
            client.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string() }

            // The tab's store must now hold the captured transaction (cursor envelope).
            val response = tab.handle(DebugRequest(path = "transactions", method = "GET"))
            val body = response?.body?.toString(Charsets.UTF_8).orEmpty()
            val json = JSONObject(body)
            val items = json.optJSONArray("items")
            assertTrue("expected at least one captured transaction, got: $body", (items?.length() ?: 0) >= 1)
            assertTrue("captured url should match the request", body.contains("/wiring-check"))
        } finally {
            mockServer.shutdown()
        }
    }

    @Test
    fun `networkInterceptor is a pass-through when no network tab is registered`() {
        val mockServer = MockWebServer()
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("hi"))
        mockServer.start()
        try {
            val lustro = Lustro.builder(app).build()
            val client = OkHttpClient.Builder().addInterceptor(lustro.networkInterceptor()).build()
            val url = mockServer.url("/no-tab").toString()
            val code = client.newCall(Request.Builder().url(url).build()).execute().use { it.code }
            // Pass-through: the request still succeeds, nothing to capture.
            assertEquals(200, code)
        } finally {
            mockServer.shutdown()
        }
    }
}
