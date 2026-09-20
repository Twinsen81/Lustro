package io.github.twinsen81.lustro.network

import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.MediaType
import io.github.twinsen81.lustro.internal.network.NetworkTrafficStore
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [NetworkDebugTab.handle] — the cursor-polling envelope
 * (reset/unchanged/delta + opaque cursor) and the mock/state routes. Plain
 * JUnit: the routes under test are Android-free (the opaque cursor comes from
 * the pure-JVM `CursorCodec`).
 */
class NetworkDebugTabTest {
    private fun tab(): NetworkDebugTab {
        val classifier = NetworkClassifier { url -> if ("sync" in url) listOf("sync") else emptyList() }
        // No senderClient => Send route hidden; mock/state/transaction routes active.
        return NetworkDebugTab.create(classifier = classifier, redactor = DefaultRedactor)
    }

    private fun get(path: String, vararg query: Pair<String, String>): DebugRequest =
        DebugRequest(
            path = path,
            method = "GET",
            queryParams = query.associate { (k, v) -> k to listOf(v) },
        )

    private fun post(path: String, body: String): DebugRequest =
        DebugRequest(
            path = path,
            method = "POST",
            body = body.toByteArray(Charsets.UTF_8),
            contentType = MediaType.JSON,
        )

    private fun DebugResponse.json(): JSONObject = JSONObject(body.toString(Charsets.UTF_8))

    private fun NetworkDebugTab.capture(url: String) {
        captureSink.beginRequest(url, "GET", Headers.EMPTY, null, null)
        assertTrue((captureSink as NetworkTrafficStore).awaitCaptures())
    }

    @Test
    fun `first poll returns reset with items and state`() {
        val tab = tab()
        val res = tab.handle(get("transactions"))!!
        val json = res.json()
        assertEquals("reset", json.getString("status"))
        assertTrue(json.has("items"))
        assertTrue(json.has("cursor"))
        val state = json.getJSONObject("state")
        assertFalse(state.getBoolean("paused"))
        assertFalse(state.getBoolean("overwriteMode"))
        assertEquals(0, state.getInt("throttleDelayMs"))
    }

    @Test
    fun `polling with the current cursor returns unchanged and omits items`() {
        val tab = tab()
        val first = tab.handle(get("transactions"))!!.json()
        val cursor = first.getString("cursor")

        val second = tab.handle(get("transactions", "cursor" to cursor))!!.json()
        assertEquals("unchanged", second.getString("status"))
        assertFalse(second.has("items"))
        // Cursor is stable while nothing changed.
        assertEquals(cursor, second.getString("cursor"))
    }

    @Test
    fun `a capture advances the cursor and yields a delta`() {
        val tab = tab()
        val cursor1 = tab.handle(get("transactions"))!!.json().getString("cursor")

        tab.capture("https://example.com/a")

        val second = tab.handle(get("transactions", "cursor" to cursor1))!!.json()
        assertEquals("delta", second.getString("status"))
        assertEquals(1, second.getJSONArray("items").length())
        assertTrue(second.getString("cursor") != cursor1)
    }

    @Test
    fun `control changes leave the cursor alone and show up in state`() {
        val tab = tab()
        val cursor = tab.handle(get("transactions"))!!.json().getString("cursor")

        tab.handle(post("pause", "{}"))
        tab.handle(post("overwrite-mode", "{}"))
        tab.handle(post("throttle", """{"delayMs":250}"""))

        val second = tab.handle(get("transactions", "cursor" to cursor))!!.json()
        assertEquals("unchanged", second.getString("status"))
        assertFalse(second.has("items"))
        val state = second.getJSONObject("state")
        assertTrue(state.getBoolean("paused"))
        assertTrue(state.getBoolean("overwriteMode"))
        assertEquals(250, state.getInt("throttleDelayMs"))
    }

    @Test
    fun `a cursor from another tab instance gets a reset`() {
        // Models an app restart: the new tab's sequence reaches the value of the
        // client's old cursor with a different list.
        val before = tab()
        before.capture("https://example.com/old")
        val staleCursor = before.handle(get("transactions"))!!.json().getString("cursor")

        val after = tab()
        after.capture("https://example.com/new")

        val poll = after.handle(get("transactions", "cursor" to staleCursor))!!.json()
        assertEquals("reset", poll.getString("status"))
        assertEquals("https://example.com/new", poll.getJSONArray("items").getJSONObject(0).getString("url"))
    }

    @Test
    fun `transaction detail returns 404 envelope when missing`() {
        val tab = tab()
        val res = tab.handle(get("transactions/does-not-exist"))!!
        assertEquals(404, res.status)
        assertEquals("not_found", res.json().getString("error"))
    }

    @Test
    fun `add rule then list returns it`() {
        val tab = tab()
        val add =
            tab.handle(post("rules", """{"name":"mock","urlPattern":"/api/x","statusCode":201}"""))!!
        assertEquals("ok", add.json().getString("status"))
        val id = add.json().getString("id")

        val list = tab.handle(get("rules"))!!.json()
        val items = list.getJSONArray("items")
        assertEquals(1, items.length())
        assertEquals(id, items.getJSONObject(0).getString("id"))
        assertEquals("/api/x", items.getJSONObject(0).getString("urlPattern"))
        assertEquals(201, items.getJSONObject(0).getInt("statusCode"))
    }

    @Test
    fun `add rule rejects blank urlPattern with a field error`() {
        val tab = tab()
        val res = tab.handle(post("rules", """{"name":"x"}"""))!!
        assertEquals(400, res.status)
        assertEquals("urlPattern", res.json().getString("field"))
    }

    @Test
    fun `sync replaces the whole rule set`() {
        val tab = tab()
        tab.handle(post("rules", """{"urlPattern":"/old"}"""))
        val sync =
            tab.handle(post("rules/_/sync", """[{"urlPattern":"/a"},{"urlPattern":"/b"}]"""))!!
        assertEquals(2, sync.json().getInt("count"))
        assertEquals(2, tab.handle(get("rules"))!!.json().getJSONArray("items").length())
    }

    @Test
    fun `sync rejects an invalid entry with 400 and leaves the rule set untouched`() {
        val tab = tab()
        tab.handle(post("rules", """{"urlPattern":"/keep"}"""))
        // One valid, one blank-urlPattern entry: declarative sync is all-or-nothing,
        // so the whole batch is rejected with a 400 and the existing set is untouched
        // (never a silent partial apply — the OpenAPI promises set == posted array).
        val res =
            tab.handle(post("rules/_/sync", """[{"urlPattern":"/a"},{"name":"bad"}]"""))!!
        assertEquals(400, res.status)
        assertEquals("urlPattern", res.json().getString("field"))
        val items = tab.handle(get("rules"))!!.json().getJSONArray("items")
        assertEquals(1, items.length())
        assertEquals("/keep", items.getJSONObject(0).getString("urlPattern"))
    }

    @Test
    fun `sync rejects a non-object entry with 400`() {
        val tab = tab()
        val res = tab.handle(post("rules/_/sync", """[{"urlPattern":"/a"}, 42]"""))!!
        assertEquals(400, res.status)
        assertEquals("bad_request", res.json().getString("error"))
    }

    @Test
    fun `delete and toggle rule routes`() {
        val tab = tab()
        val add = tab.handle(post("rules", """{"urlPattern":"/x"}"""))!!.json()
        val id = add.getString("id")

        val toggle = tab.handle(post("rules/toggle", """{"id":"$id"}"""))!!
        assertEquals("ok", toggle.json().getString("status"))
        val afterToggle =
            tab.handle(get("rules"))!!.json().getJSONArray("items").getJSONObject(0)
        assertFalse(afterToggle.getBoolean("enabled"))

        val del = tab.handle(post("rules/delete", """{"id":"$id"}"""))!!
        assertEquals("ok", del.json().getString("status"))
        assertEquals(0, tab.handle(get("rules"))!!.json().getJSONArray("items").length())
    }

    @Test
    fun `overwrite-mode and throttle routes update state`() {
        val tab = tab()
        val ow = tab.handle(post("overwrite-mode", "{}"))!!.json()
        assertTrue(ow.getBoolean("overwriteMode"))

        val th = tab.handle(post("throttle", """{"delayMs":1500}"""))!!.json()
        assertEquals(1500, th.getInt("delayMs"))

        val state = tab.handle(get("transactions"))!!.json().getJSONObject("state")
        assertTrue(state.getBoolean("overwriteMode"))
        assertEquals(1500, state.getInt("throttleDelayMs"))
    }

    @Test
    fun `clear route succeeds`() {
        val tab = tab()
        val res = tab.handle(post("clear", "{}"))!!
        assertEquals("ok", res.json().getString("status"))
    }

    @Test
    fun `send route is hidden when no sender configured`() {
        val tab = tab()
        assertNull(tab.handle(post("send", """{"url":"https://example.com"}""")))
    }

    private fun <T> withServer(response: MockResponse, block: (url: String) -> T): T {
        val server = MockWebServer()
        server.enqueue(response)
        server.start()
        try {
            return block(server.url("/send-target").toString())
        } finally {
            server.shutdown()
        }
    }

    // No read timeout, so only the call timeout can end a stalled send.
    private fun senderTab(maxBodyCaptureBytes: Long, requestTimeoutMs: Long): NetworkDebugTab =
        NetworkDebugTab.create(senderClient = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build())
            .apply {
                applyConfig(
                    maxCaptureTransactions = 1000,
                    maxBodyCaptureBytes = maxBodyCaptureBytes,
                    appServerBaseUrl = null,
                    captureBudgetBytes = 50L * 1024 * 1024,
                    requestTimeoutMs = requestTimeoutMs,
                )
            }

    @Test(timeout = 20_000)
    fun `send reports the status of a large response without reading all of it`() {
        // 1 MiB at 16 KiB/s would take ~64 s to read in full.
        val big = MockResponse().setBody(Buffer().write(ByteArray(1024 * 1024))).throttleBody(16L * 1024, 1, TimeUnit.SECONDS)
        val tab = senderTab(maxBodyCaptureBytes = 4096, requestTimeoutMs = 30_000)

        val json = withServer(big) { url -> tab.handle(post("send", """{"url":"$url"}"""))!!.json() }

        assertTrue(json.toString(), json.getBoolean("ok"))
        assertEquals(200, json.getInt("statusCode"))
    }

    @Test(timeout = 20_000)
    fun `send cancels a stalled call shortly after the configured request timeout`() {
        // One byte, then silence for 4 s.
        val stalled = MockResponse().setBody("xy").throttleBody(1, 4, TimeUnit.SECONDS)
        val tab = senderTab(maxBodyCaptureBytes = 4096, requestTimeoutMs = 200)

        var elapsedMs = 0L
        val json =
            withServer(stalled) { url ->
                val started = System.nanoTime()
                tab.handle(post("send", """{"url":"$url"}"""))!!.json()
                    .also { elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) }
            }

        assertFalse(json.getBoolean("ok"))
        assertEquals("timeout", json.getString("error"))
        assertTrue("send took ${elapsedMs}ms", elapsedMs < 3_000)
    }

    @Test
    fun `unknown route returns null so the server enveloped-404s`() {
        val tab = tab()
        assertNull(tab.handle(get("nope")))
        assertNull(tab.handle(post("transactions", "{}"))) // wrong method
    }

    @Test
    fun `tab identity matches the network wire contract`() {
        val tab = tab()
        assertEquals("network", tab.id)
        assertEquals("Network", tab.title)
        assertEquals(10, tab.order)
    }

    @Test
    fun `rule changes leave the transactions cursor alone`() {
        val tab = tab()
        val firstCursor = tab.handle(get("transactions"))!!.json().getString("cursor")
        val id = tab.handle(post("rules", """{"urlPattern":"/api"}"""))!!.json().getString("id")
        tab.handle(post("rules/toggle", """{"id":"$id"}"""))
        tab.handle(post("rules/delete", """{"id":"$id"}"""))
        val poll = tab.handle(get("transactions", "cursor" to firstCursor))!!.json()
        assertEquals("unchanged", poll.getString("status"))
    }
}
