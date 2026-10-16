package io.github.twinsen81.lustro.network

import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.MediaType
import io.github.twinsen81.lustro.internal.network.NetworkTrafficStore
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The named senders of the Send form: `GET senders`, and how `POST send` picks one. */
class NetworkSendersTest {
    private val api = MockWebServer()
    private val cdn = MockWebServer()

    @Before
    fun setUp() {
        api.start()
        cdn.start()
    }

    @After
    fun tearDown() {
        api.shutdown()
        cdn.shutdown()
    }

    private fun get(path: String): DebugRequest = DebugRequest(path = path, method = "GET")

    private fun post(path: String, body: String): DebugRequest =
        DebugRequest(path = path, method = "POST", body = body.toByteArray(Charsets.UTF_8), contentType = MediaType.JSON)

    private fun DebugResponse.json(): JSONObject = JSONObject(body.toString(Charsets.UTF_8))

    private fun configured(tab: NetworkDebugTab, appServerBaseUrl: String? = null): NetworkDebugTab =
        tab.apply {
            applyConfig(
                maxCaptureTransactions = 1000,
                maxBodyCaptureBytes = 4096,
                appServerBaseUrl = appServerBaseUrl,
                captureBudgetBytes = 50L * 1024 * 1024,
                requestTimeoutMs = 10_000,
                maxCaptureWebSockets = 100,
                maxWebSocketEvents = 1000,
                webSocketCaptureBudgetBytes = 16L * 1024 * 1024,
            )
        }

    private fun twoSenders(client: OkHttpClient = OkHttpClient()): NetworkDebugTab =
        configured(
            NetworkDebugTab.create(
                senders =
                    listOf(
                        OkHttpSender.create("API", baseUrl = api.url("/v1").toString().trimEnd('/')) { client },
                        OkHttpSender.create("CDN", baseUrl = cdn.url("/").toString()) { client },
                        OkHttpSender.create("Any host") { client },
                    ),
            ),
        )

    @Test
    fun `senders route lists the senders in order with their base urls`() {
        val items = twoSenders().handle(get("senders"))!!.json().getJSONArray("items")

        assertEquals(3, items.length())
        assertEquals("API", items.getJSONObject(0).getString("name"))
        assertEquals(api.url("/v1").toString().trimEnd('/'), items.getJSONObject(0).getString("baseUrl"))
        assertEquals("CDN", items.getJSONObject(1).getString("name"))
        assertTrue(items.getJSONObject(2).isNull("baseUrl"))
    }

    @Test
    fun `the senderClient form is one Default sender with the configured base url`() {
        val tab = configured(NetworkDebugTab.create(senderClient = OkHttpClient()), appServerBaseUrl = "https://example.com")

        val items = tab.handle(get("senders"))!!.json().getJSONArray("items")

        assertEquals(1, items.length())
        assertEquals("Default", items.getJSONObject(0).getString("name"))
        assertEquals("https://example.com", items.getJSONObject(0).getString("baseUrl"))
    }

    @Test
    fun `without senders the list is empty and send is hidden`() {
        val tab = configured(NetworkDebugTab.create(senders = emptyList()))

        assertEquals(0, tab.handle(get("senders"))!!.json().getJSONArray("items").length())
        assertEquals(null, tab.handle(post("send", """{"url":"https://example.com"}""")))
    }

    @Test
    fun `send resolves a relative url against the base url of the chosen sender`() {
        cdn.enqueue(MockResponse().setResponseCode(204))

        val json = twoSenders().handle(post("send", """{"url":"/popular.json","sender":"CDN"}"""))!!.json()

        assertEquals(204, json.getInt("statusCode"))
        assertEquals("CDN", json.getString("sender"))
        assertEquals(cdn.url("/popular.json").toString(), json.getString("url"))
        assertEquals("/popular.json", cdn.takeRequest().path)
        assertEquals(0, api.requestCount)
    }

    @Test
    fun `a base url with a path keeps it`() {
        api.enqueue(MockResponse())

        twoSenders().handle(post("send", """{"url":"items?page=2"}"""))

        assertEquals("/v1/items?page=2", api.takeRequest().path)
    }

    @Test
    fun `send without a sender uses the first one`() {
        api.enqueue(MockResponse().setResponseCode(401))

        val json = twoSenders().handle(post("send", """{"url":"/me"}"""))!!.json()

        assertEquals("API", json.getString("sender"))
        assertEquals(401, json.getInt("statusCode"))
        // A 4xx is a completed send: ok reports the transport, not the status.
        assertTrue(json.getBoolean("ok"))
    }

    @Test
    fun `an absolute url ignores the base url`() {
        cdn.enqueue(MockResponse())

        twoSenders().handle(post("send", """{"url":"${cdn.url("/abs")}","sender":"API"}"""))

        assertEquals("/abs", cdn.takeRequest().path)
        assertEquals(0, api.requestCount)
    }

    @Test
    fun `an unknown sender is a bad request naming the field`() {
        val res = twoSenders().handle(post("send", """{"url":"/x","sender":"Nope"}"""))!!

        assertEquals(400, res.status)
        assertEquals("sender", res.json().getString("field"))
    }

    @Test
    fun `a relative url for a sender without a base url is a bad request`() {
        val res = twoSenders().handle(post("send", """{"url":"/x","sender":"Any host"}"""))!!

        assertEquals(400, res.status)
        assertEquals("url", res.json().getString("field"))
    }

    @Test
    fun `the client provider is called at the first send and its client is kept`() {
        val calls = AtomicInteger()
        val client = OkHttpClient()
        val tab =
            configured(
                NetworkDebugTab.create(
                    senders =
                        listOf(
                            OkHttpSender.create("API", baseUrl = api.url("/").toString()) {
                                calls.incrementAndGet()
                                client
                            },
                        ),
                ),
            )
        tab.handle(get("senders"))
        assertEquals(0, calls.get())

        api.enqueue(MockResponse())
        api.enqueue(MockResponse())
        tab.handle(post("send", """{"url":"/a"}"""))
        tab.handle(post("send", """{"url":"/b"}"""))

        assertEquals(1, calls.get())
    }

    @Test
    fun `a provider that throws fails the send and is asked again next time`() {
        val calls = AtomicInteger()
        val tab =
            configured(
                NetworkDebugTab.create(
                    senders =
                        listOf(
                            OkHttpSender.create("API", baseUrl = api.url("/").toString()) {
                                if (calls.incrementAndGet() == 1) error("not ready") else OkHttpClient()
                            },
                        ),
                ),
            )

        val first = tab.handle(post("send", """{"url":"/a"}"""))!!.json()
        api.enqueue(MockResponse())
        val second = tab.handle(post("send", """{"url":"/a"}"""))!!.json()

        assertFalse(first.getBoolean("ok"))
        assertEquals("not ready", first.getString("error"))
        assertTrue(second.getBoolean("ok"))
    }

    @Test
    fun `send returns the transaction that the sender's interceptor captured`() {
        lateinit var tab: NetworkDebugTab
        // Built after the tab, as an app's client that carries the interceptor is.
        val capturing by lazy { OkHttpClient.Builder().addInterceptor(tab.createInterceptor { true }).build() }
        tab =
            configured(
                NetworkDebugTab.create(
                    senders = listOf(OkHttpSender.create("API", baseUrl = api.url("/").toString()) { capturing }),
                ),
            )
        api.enqueue(MockResponse().setBody("{}"))

        val json = tab.handle(post("send", """{"url":"/me"}"""))!!.json()

        val id = json.getString("transactionId")
        assertTrue((tab.captureSink as NetworkTrafficStore).awaitCaptures())
        val detail = tab.handle(get("transactions/$id"))!!.json()
        assertEquals(api.url("/me").toString(), detail.getString("url"))
    }

    @Test
    fun `send without a capturing client has no transaction id`() {
        api.enqueue(MockResponse())

        val json = twoSenders().handle(post("send", """{"url":"/me"}"""))!!.json()

        assertTrue(json.isNull("transactionId"))
    }

    @Test
    fun `two senders with one name are rejected`() {
        val client = OkHttpClient()
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                NetworkDebugTab.create(senders = listOf(OkHttpSender.create("API") { client }, OkHttpSender.create("API") { client }))
            }
        assertNotNull(error.message)
    }

    @Test
    fun `a blank name or a base url that is not http is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { OkHttpSender.create(" ") { OkHttpClient() } }
        assertThrows(IllegalArgumentException::class.java) { OkHttpSender.create("API", baseUrl = "api.example.com") { OkHttpClient() } }
        assertThrows(IllegalArgumentException::class.java) { OkHttpSender.create("API", baseUrl = "ftp://example.com") { OkHttpClient() } }
    }
}
