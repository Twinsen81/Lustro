package io.github.twinsen81.lustro

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.internal.network.LustroNetworkInterceptor
import io.github.twinsen81.lustro.internal.network.MockRuleImpl
import io.github.twinsen81.lustro.internal.network.NetworkTrafficStore
import io.github.twinsen81.lustro.internal.network.NoOpCaptureSink
import io.github.twinsen81.lustro.network.MockRule
import io.github.twinsen81.lustro.network.MockRuleStorage
import io.github.twinsen81.lustro.network.NetworkDebugTab
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The network functions of a build that [Lustro.start] refuses to arm: not
 * debuggable, without [DebugConfig.allowNonDebuggableBuilds]. They return the
 * forms they return without a network tab, also when the app never calls
 * [Lustro.start], so such a build does not capture, mock, or throttle. A mock
 * rule comes from a [MockRuleStorage], as one that a debuggable build of the app
 * stored earlier.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LustroNonDebuggableNetworkTest {
    private lateinit var app: Application
    private val server = MockWebServer()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a non-debuggable build gets the pass-through forms, and records and mocks nothing`() {
        setDebuggable(false)
        val tab = tabWithStoredRule()
        val lustro = Lustro.builder(app).addTab(tab).build()
        setThrottle(tab, delayMs = 5_000)

        val interceptor = lustro.networkInterceptor()
        val client = OkHttpClient()
        assertFalse("the interceptor is a pass-through", interceptor is LustroNetworkInterceptor)
        assertSame(client, lustro.webSocketFactory(client))
        val sink = lustro.networkCaptureSink()
        assertSame(NoOpCaptureSink, sink)

        server.enqueue(MockResponse().setBody("from the server"))
        val startedAt = System.nanoTime()
        val answer = get(OkHttpClient.Builder().addInterceptor(interceptor).build(), "/mocked")
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertNull("the sink finds no rule", sink.findMockRule(server.url("/mocked").toString(), "GET"))
        sink.beginRequest(server.url("/reported").toString(), "GET", Headers.Builder().build(), null, null)
        awaitCaptures(tab)

        assertEquals("the stored rule does not answer", 200 to "from the server", answer)
        assertTrue("the throttle does not delay the request, it took $elapsedMs ms", elapsedMs < 2_500)
        assertEquals("no transaction is recorded", 0, transactions(tab).length())
        assertEquals("no hit is counted", 0, rules(tab).getJSONObject(0).getInt("hitCount"))
        assertEquals("start() refuses too", LustroStatus.DISABLED, lustro.start())
    }

    @Test
    fun `a non-debuggable build with allowNonDebuggableBuilds captures and mocks`() {
        setDebuggable(false)
        val tab = tabWithStoredRule()
        val config = DebugConfig.builder().allowNonDebuggableBuilds(true).build()
        val lustro = Lustro.builder(app).config(config).addTab(tab).build()

        assertCapturesAndMocks(lustro, tab)
    }

    @Test
    fun `a debuggable build captures and mocks as before`() {
        setDebuggable(true)
        val tab = tabWithStoredRule()
        val lustro = Lustro.builder(app).addTab(tab).build()

        assertCapturesAndMocks(lustro, tab)
    }

    private fun assertCapturesAndMocks(lustro: Lustro, tab: NetworkDebugTab) {
        val client = OkHttpClient()
        assertTrue(lustro.networkInterceptor() is LustroNetworkInterceptor)
        assertNotSame(client, lustro.webSocketFactory(client))
        assertNotSame(NoOpCaptureSink, lustro.networkCaptureSink())

        server.enqueue(MockResponse().setBody("from the server"))
        val mocked = get(OkHttpClient.Builder().addInterceptor(lustro.networkInterceptor()).build(), "/mocked")
        val passed = get(OkHttpClient.Builder().addInterceptor(lustro.networkInterceptor()).build(), "/kept")
        awaitCaptures(tab)

        assertEquals(418 to "from the rule", mocked)
        assertEquals(200 to "from the server", passed)
        assertEquals(2, transactions(tab).length())
        assertEquals(1, rules(tab).getJSONObject(0).getInt("hitCount"))
    }

    private fun setDebuggable(debuggable: Boolean) {
        val flags = app.applicationInfo.flags
        app.applicationInfo.flags =
            if (debuggable) {
                flags or ApplicationInfo.FLAG_DEBUGGABLE
            } else {
                flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
            }
    }

    private fun tabWithStoredRule(): NetworkDebugTab {
        val stored: List<MockRule> =
            listOf(MockRuleImpl(id = "stored", name = "stored", urlPattern = "/mocked", statusCode = 418, responseBody = "from the rule"))
        val storage =
            object : MockRuleStorage {
                override fun load(): List<MockRule> = stored

                override fun save(rules: List<MockRule>) = Unit
            }
        return NetworkDebugTab.create(mockRuleStorage = storage)
    }

    private fun setThrottle(tab: NetworkDebugTab, delayMs: Int) {
        val body = """{"delayMs":$delayMs}""".toByteArray()
        val response = tab.handle(DebugRequest(path = "throttle", method = "POST", body = body, contentType = MediaType.JSON))
        assertEquals(200, response!!.status)
    }

    private fun get(client: OkHttpClient, path: String): Pair<Int, String> =
        client.newCall(Request.Builder().url(server.url(path)).build()).execute().use { it.code to it.body!!.string() }

    private fun awaitCaptures(tab: NetworkDebugTab) = assertTrue((tab.captureSink as NetworkTrafficStore).awaitCaptures())

    private fun transactions(tab: NetworkDebugTab) = tab.handle(DebugRequest(path = "transactions", method = "GET")).json().getJSONArray("items")

    private fun rules(tab: NetworkDebugTab) = tab.handle(DebugRequest(path = "rules", method = "GET")).json().getJSONArray("items")

    private fun DebugResponse?.json(): JSONObject = JSONObject(this!!.body.toString(Charsets.UTF_8))
}
