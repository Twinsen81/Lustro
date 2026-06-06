package io.github.twinsen81.lustro.sample

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.DebugConfig
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.Lustro
import io.github.twinsen81.lustro.network.NetworkDebugTab
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * CI network test for the sample's Lustro integration.
 *
 * Runs entirely offline against an [okhttp3.mockwebserver.MockWebServer]: it
 * builds the real `:lustro` runtime (debug unit-test classpath), installs the
 * Network tab's capturing OkHttp interceptor on a client, fires a GET and a POST
 * at the mock server, and asserts:
 *
 *  1. the interceptor passes traffic through UNCHANGED — the client receives the
 *     mock status codes and bodies, and both requests reach the mock server; and
 *  2. a capture is recorded — the Network tab's `transactions` route returns the
 *     cursor envelope with both transactions (correct method/url/status).
 *
 * This drives the real public integration path: `lustro.networkInterceptor()`
 * resolves the registered Network tab's capture sink and returns the runtime's
 * `LustroNetworkInterceptor`, which records into the `NetworkTrafficStore` that
 * the tab's `transactions` route reads from.
 */
@RunWith(RobolectricTestRunner::class)
class LustroNetworkCaptureTest {
    private lateinit var server: MockWebServer
    private lateinit var lustro: Lustro
    private lateinit var networkTab: NetworkDebugTab
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        val app = ApplicationProvider.getApplicationContext<Application>()
        networkTab = NetworkDebugTab.create()
        lustro =
            Lustro.builder(app)
                // Ephemeral-port fallback so the loopback bind never fails on a
                // busy CI port; we only need the runtime wired, not a fixed port.
                .config(DebugConfig.builder().bindFallback(true).build())
                .addTab(networkTab)
                .build()
        // start() freezes the tab registry and binds the loopback socket (closed
        // in tearDown). Robolectric reports the process as foregrounded so the
        // bind happens eagerly.
        lustro.start()
        client = OkHttpClient.Builder().addInterceptor(lustro.networkInterceptor()).build()
    }

    @After
    fun tearDown() {
        lustro.stop()
        server.shutdown()
    }

    @Test
    fun `interceptor passes GET and POST through and records both captures`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"created":true}"""))

        // GET — interceptor must pass the response through unchanged.
        val getResponse =
            client.newCall(Request.Builder().url(server.url("/get")).get().build()).execute()
        getResponse.use {
            assertEquals(200, it.code)
            assertEquals("""{"ok":true}""", it.body!!.string())
        }

        // POST with a JSON body — body and status preserved end to end.
        val postBody = """{"hello":"world"}""".toRequestBody("application/json".toMediaType())
        val postResponse =
            client.newCall(Request.Builder().url(server.url("/post")).post(postBody).build()).execute()
        postResponse.use {
            assertEquals(201, it.code)
            assertEquals("""{"created":true}""", it.body!!.string())
        }

        // Both requests must have actually hit the mock server (pass-through, no
        // mock short-circuit), with the request line/body intact.
        assertEquals(2, server.requestCount)
        val first = server.takeRequest()
        assertEquals("GET", first.method)
        assertEquals("/get", first.path)
        val second = server.takeRequest()
        assertEquals("POST", second.method)
        assertEquals("/post", second.path)
        assertEquals("""{"hello":"world"}""", second.body.readUtf8())

        // And both must be captured: query the Network tab's transactions route.
        val envelope = JSONObject(transactionsJson())
        assertEquals("reset", envelope.getString("status"))
        assertTrue("expected a cursor token", envelope.getString("cursor").isNotEmpty())
        assertNotNull("first poll returns the current item list", envelope.optJSONArray("items"))
        val items = envelope.getJSONArray("items")
        assertEquals(2, items.length())

        val methods = (0 until items.length()).map { items.getJSONObject(it).getString("method") }
        assertTrue("expected a GET capture, got $methods", methods.contains("GET"))
        assertTrue("expected a POST capture, got $methods", methods.contains("POST"))

        // The POST capture carries the captured status code from the mock (201).
        val postTx =
            (0 until items.length())
                .map { items.getJSONObject(it) }
                .single { it.getString("method") == "POST" }
        assertEquals(201, postTx.getInt("statusCode"))
        assertTrue(postTx.getString("url").endsWith("/post"))

        // The capture/control state travels in the cursor envelope's `state`.
        val state = envelope.getJSONObject("state")
        assertTrue(state.has("paused"))
        assertTrue(state.has("throttleDelayMs"))
        assertTrue(state.has("overwriteMode"))
    }

    /** Drives the public `GET transactions` route on the Network tab. */
    private fun transactionsJson(): String {
        val response =
            networkTab.handle(DebugRequest(path = "transactions", method = "GET"))
                ?: error("transactions route returned null")
        return String(response.body, Charsets.UTF_8)
    }
}
