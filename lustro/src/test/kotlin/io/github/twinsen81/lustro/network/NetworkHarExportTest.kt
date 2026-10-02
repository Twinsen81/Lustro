package io.github.twinsen81.lustro.network

import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.MediaType
import io.github.twinsen81.lustro.internal.network.NetworkTrafficStore
import java.time.Instant
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for `GET transactions/_/export`, the HAR export of captured transactions. */
class NetworkHarExportTest {
    private val tab = NetworkDebugTab.create(classifier = NetworkClassifier { url -> if ("sync" in url) listOf("sync") else emptyList() })
    private val store = tab.captureSink as NetworkTrafficStore

    private fun export(vararg query: Pair<String, String>): DebugResponse =
        tab.handle(
            DebugRequest(
                path = "transactions/_/export",
                method = "GET",
                queryParams = query.groupBy({ it.first }, { it.second }),
            ),
        )!!

    private fun entries(vararg query: Pair<String, String>): JSONArray {
        val res = export(*query)
        assertEquals(200, res.status)
        return JSONObject(res.body.toString(Charsets.UTF_8)).getJSONObject("log").getJSONArray("entries")
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun JSONArray.headers(): Map<String, String> =
        objects().associate { it.getString("name") to it.getString("value") }

    private fun record(
        url: String = "https://example.com/a",
        method: String = "GET",
        requestHeaders: Headers = Headers.EMPTY,
        requestBody: CapturedBody? = null,
        requestType: MediaType? = null,
        response: CapturedResponse? = CapturedResponse.Builder(200, 5).build(),
    ): String {
        val id = store.beginRequest(url, method, requestHeaders, requestBody, requestType)
        response?.let { store.completeRequest(id, it) }
        assertTrue(store.awaitCaptures())
        return id.value
    }

    @Test
    fun `the export is a HAR 1_2 log of every transaction, oldest first`() {
        val first = record(url = "https://example.com/first")
        val second = record(url = "https://example.com/second")

        val res = export("format" to "har")
        assertEquals("application/json; charset=utf-8", res.contentType.toString())
        val log = JSONObject(res.body.toString(Charsets.UTF_8)).getJSONObject("log")
        assertEquals("1.2", log.getString("version"))
        assertEquals("Lustro", log.getJSONObject("creator").getString("name"))
        assertTrue(log.getJSONObject("creator").getString("version").isNotEmpty())
        val ids = log.getJSONArray("entries").objects().map { it.getJSONObject("_lustro").getString("id") }
        assertEquals(listOf(first, second), ids)
    }

    @Test
    fun `format defaults to har`() {
        record()
        assertEquals(1, entries().length())
    }

    @Test
    fun `an unknown format is a 400 naming the field`() {
        val res = export("format" to "csv")
        assertEquals(400, res.status)
        val error = JSONObject(res.body.toString(Charsets.UTF_8))
        assertEquals("bad_request", error.getString("error"))
        assertEquals("format", error.getString("field"))
    }

    @Test
    fun `ids selects transactions and leaves out the ones the store no longer has`() {
        val a = record(url = "https://example.com/a")
        record(url = "https://example.com/b")
        val c = record(url = "https://example.com/c")

        fun exported(vararg query: Pair<String, String>) =
            entries(*query).objects().map { it.getJSONObject("_lustro").getString("id") }

        assertEquals(listOf(a, c), exported("ids" to "$c, $a,gone,$c"))
        // Repeated parameters work too.
        assertEquals(listOf(a, c), exported("ids" to a, "ids" to c))
        // An ids parameter that names nothing is an empty selection, not everything.
        assertEquals(emptyList<String>(), exported("ids" to ""))
    }

    @Test
    fun `an entry maps the request, the response, and the timings`() {
        val id =
            record(
                url = "https://example.com/orders?q=a%20b&flag",
                method = "POST",
                requestHeaders = Headers.of("Accept" to "application/json"),
                requestBody = CapturedBody("""{"item":1}""", byteSize = 10),
                requestType = MediaType.JSON,
                response =
                    CapturedResponse.Builder(302, 41)
                        .headers(Headers.of("Content-Type" to "text/plain", "location" to "/next"))
                        .body(CapturedBody("moved", byteSize = 5))
                        .protocol("h2")
                        .build(),
            )
        val detail = JSONObject(tab.handle(DebugRequest(path = "transactions/$id", method = "GET"))!!.body.toString(Charsets.UTF_8))

        val entry = entries().getJSONObject(0)
        val startedDateTime = entry.getString("startedDateTime")
        assertTrue(startedDateTime, Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z""").matches(startedDateTime))
        assertEquals(detail.getLong("startedAt"), Instant.parse(startedDateTime).toEpochMilli())
        assertEquals(41, entry.getLong("time"))
        assertEquals(0, entry.getJSONObject("cache").length())
        assertEquals("fetch", entry.getString("_resourceType"))
        val timings = entry.getJSONObject("timings")
        assertEquals(0, timings.getLong("send"))
        assertEquals(41, timings.getLong("wait"))
        assertEquals(0, timings.getLong("receive"))
        for (phase in listOf("blocked", "dns", "connect", "ssl")) assertEquals(phase, -1, timings.getLong(phase))

        val request = entry.getJSONObject("request")
        assertEquals("POST", request.getString("method"))
        assertEquals("https://example.com/orders?q=a%20b&flag", request.getString("url"))
        assertEquals("h2", request.getString("httpVersion"))
        assertEquals(0, request.getJSONArray("cookies").length())
        assertEquals(mapOf("Accept" to "application/json"), request.getJSONArray("headers").headers())
        assertEquals(mapOf("q" to "a b", "flag" to ""), request.getJSONArray("queryString").headers())
        val postData = request.getJSONObject("postData")
        assertEquals("application/json; charset=utf-8", postData.getString("mimeType"))
        assertEquals("""{"item":1}""", postData.getString("text"))
        assertFalse(postData.has("_encoding"))
        assertEquals(-1, request.getLong("headersSize"))
        assertEquals(10, request.getLong("bodySize"))

        val response = entry.getJSONObject("response")
        assertEquals(302, response.getInt("status"))
        assertEquals("", response.getString("statusText"))
        assertEquals("h2", response.getString("httpVersion"))
        assertEquals("/next", response.getString("redirectURL"))
        assertEquals("text/plain", response.getJSONArray("headers").headers()["Content-Type"])
        val content = response.getJSONObject("content")
        assertEquals(5, content.getLong("size"))
        assertEquals("text/plain", content.getString("mimeType"))
        assertEquals("moved", content.getString("text"))
        assertFalse(content.has("encoding"))
        assertEquals(-1, response.getLong("headersSize"))
        assertEquals(5, response.getLong("bodySize"))
        assertFalse(response.has("_error"))

        val lustro = entry.getJSONObject("_lustro")
        assertEquals(id, lustro.getString("id"))
        assertFalse(lustro.getBoolean("isMocked"))
        assertEquals(0, lustro.getJSONArray("categories").length())
        assertFalse(lustro.getBoolean("requestBodyTruncated"))
        assertFalse(lustro.getBoolean("responseBodyTruncated"))
        assertTrue(lustro.getBoolean("responseComplete"))
        assertTrue(lustro.isNull("error"))
    }

    @Test
    fun `the export keeps headers and bodies redacted`() {
        record(
            method = "POST",
            requestHeaders = Headers.of("Authorization" to "Bearer secret"),
            requestBody = CapturedBody("""{"password":"hunter2"}"""),
            requestType = MediaType.JSON,
        )

        val text = export().body.toString(Charsets.UTF_8)
        assertFalse(text, "secret" in text)
        assertFalse(text, "hunter2" in text)
    }

    @Test
    fun `a body kept as bytes goes out in base64`() {
        val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        record(
            method = "PUT",
            requestBody = CapturedBody(text = null, byteSize = 8, bytes = png),
            requestType = MediaType.parse("image/png"),
            response =
                CapturedResponse.Builder(200, 5)
                    .headers(Headers.of("Content-Type" to "image/png"))
                    .body(CapturedBody(text = null, byteSize = 8, bytes = png))
                    .build(),
        )

        val entry = entries().getJSONObject(0)
        assertEquals("image", entry.getString("_resourceType"))
        val base64 = Base64.getEncoder().encodeToString(png)
        val content = entry.getJSONObject("response").getJSONObject("content")
        assertEquals(base64, content.getString("text"))
        assertEquals("base64", content.getString("encoding"))
        assertEquals(8, content.getLong("size"))
        val postData = entry.getJSONObject("request").getJSONObject("postData")
        assertEquals(base64, postData.getString("text"))
        assertEquals("base64", postData.getString("_encoding"))
    }

    @Test
    fun `an inflated body reports its kept size as the content size`() {
        val inflated = "x".repeat(100)
        record(
            response =
                CapturedResponse.Builder(200, 5)
                    .headers(Headers.of("Content-Type" to "text/plain", "Content-Encoding" to "gzip"))
                    .body(CapturedBody(inflated, byteSize = 20))
                    .build(),
        )

        val response = entries().getJSONObject(0).getJSONObject("response")
        assertEquals(20, response.getLong("bodySize"))
        assertEquals(100, response.getJSONObject("content").getLong("size"))
    }

    @Test
    fun `mocks, truncated bodies, and categories are marked in _lustro`() {
        record(
            url = "https://example.com/sync",
            method = "POST",
            requestBody = CapturedBody("abc", truncated = true, byteSize = 300_000),
            requestType = MediaType.TEXT,
            response =
                CapturedResponse.Builder(500, 3)
                    .body(CapturedBody("def", truncated = true, byteSize = 400_000))
                    .mocked(true)
                    .build(),
        )

        val lustro = entries().getJSONObject(0).getJSONObject("_lustro")
        assertTrue(lustro.getBoolean("isMocked"))
        assertTrue(lustro.getBoolean("requestBodyTruncated"))
        assertTrue(lustro.getBoolean("responseBodyTruncated"))
        assertEquals("sync", lustro.getJSONArray("categories").getString(0))
    }

    @Test
    fun `a failed request carries its error`() {
        val id = store.beginRequest("https://example.com/down", "GET", Headers.EMPTY, null, null)
        store.failRequest(id, 12, "java.net.UnknownHostException: example.com")
        assertTrue(store.awaitCaptures())

        val entry = entries().getJSONObject(0)
        val response = entry.getJSONObject("response")
        assertEquals(0, response.getInt("status"))
        assertEquals("java.net.UnknownHostException: example.com", response.getString("_error"))
        assertEquals("java.net.UnknownHostException: example.com", entry.getJSONObject("_lustro").getString("error"))
        assertEquals(12, entry.getLong("time"))
    }

    @Test
    fun `a request still in flight exports with no response yet`() {
        record(response = null)

        val entry = entries().getJSONObject(0)
        assertEquals(0, entry.getLong("time"))
        assertEquals(0, entry.getJSONObject("timings").getLong("wait"))
        assertFalse(entry.getJSONObject("request").has("postData"))
        val response = entry.getJSONObject("response")
        assertEquals(0, response.getInt("status"))
        assertEquals("", response.getString("httpVersion"))
        assertEquals(0, response.getJSONArray("headers").length())
        assertEquals("", response.getString("redirectURL"))
        assertEquals(-1, response.getLong("bodySize"))
        val content = response.getJSONObject("content")
        assertEquals(0, content.getLong("size"))
        assertEquals("x-unknown", content.getString("mimeType"))
        assertFalse(content.has("text"))
        assertFalse(entry.getJSONObject("_lustro").getBoolean("responseComplete"))
    }

    @Test
    fun `the export route takes GET only`() {
        assertNull(tab.handle(DebugRequest(path = "transactions/_/export", method = "POST")))
    }
}
