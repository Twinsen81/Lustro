package io.github.twinsen81.lustro

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.internal.network.NetworkTrafficStore
import io.github.twinsen81.lustro.network.CapturedBody
import io.github.twinsen81.lustro.network.CapturedResponse
import io.github.twinsen81.lustro.network.NetworkCaptureFilter
import io.github.twinsen81.lustro.network.NetworkDebugTab
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests `Lustro.networkCaptureSink()`, which an app's adapter for another HTTP
 * client reports requests to, through the public facade.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LustroCaptureSinkTest {
    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `a reported request and its response are listed, redacted like OkHttp's`() {
        val tab = NetworkDebugTab.create()
        val sink = Lustro.builder(app).addTab(tab).build().networkCaptureSink()

        val id =
            sink.beginRequest(
                url = "https://cloud.example.com/remote.php/dav/files/me/",
                method = "PROPFIND",
                headers = Headers.Builder().add("Authorization", "Basic c2VjcmV0").add("Depth", "1").build(),
                requestBody = CapturedBody("<propfind/>"),
                contentType = MediaType.parse("application/xml"),
            )
        sink.completeRequest(
            id,
            CapturedResponse.Builder(207, 42)
                .headers(Headers.Builder().add("Content-Type", "application/xml").build())
                .body(CapturedBody("<multistatus/>"))
                .build(),
        )
        awaitCaptures(tab)

        val tx = transactions(tab).getJSONObject(0)
        assertEquals("PROPFIND", tx.getString("method"))
        assertEquals(207, tx.getInt("statusCode"))
        assertEquals("the app's own adapter reported it", "app", tx.getString("source"))
        val detail = tab.handle(DebugRequest(path = "transactions/${tx.getString("id")}", method = "GET")).json()
        assertEquals("[REDACTED]", detail.getJSONObject("requestHeaders").getString("Authorization"))
        assertEquals("1", detail.getJSONObject("requestHeaders").getString("Depth"))
        assertEquals("<propfind/>", detail.getString("requestBody"))
        assertEquals("<multistatus/>", detail.getString("responseBody"))
    }

    @Test
    fun `a reported failure is listed with its error`() {
        val tab = NetworkDebugTab.create()
        val sink = Lustro.builder(app).addTab(tab).build().networkCaptureSink()

        val id = sink.beginRequest("https://cloud.example.com/status.php", "GET", Headers.Builder().build(), null, null)
        sink.failRequest(id, 30, "connection reset")
        awaitCaptures(tab)

        assertEquals("connection reset", transactions(tab).getJSONObject(0).getString("error"))
    }

    @Test
    fun `a request that starts while capture is paused is not listed, and one that started before completes`() {
        val tab = NetworkDebugTab.create()
        val sink = Lustro.builder(app).addTab(tab).build().networkCaptureSink()
        val before = sink.beginRequest("https://cloud.example.com/before", "GET", Headers.Builder().build(), null, null)

        tab.handle(DebugRequest(path = "pause", method = "POST"))
        val during = sink.beginRequest("https://cloud.example.com/during", "GET", Headers.Builder().build(), null, null)
        sink.completeRequest(during, CapturedResponse.Builder(200, 5).build())
        sink.completeRequest(before, CapturedResponse.Builder(200, 5).build())
        awaitCaptures(tab)

        val listed = transactions(tab)
        assertEquals(1, listed.length())
        assertEquals("https://cloud.example.com/before", listed.getJSONObject(0).getString("url"))
        assertEquals(200, listed.getJSONObject(0).getInt("statusCode"))
    }

    @Test
    fun `a request the capture filter leaves out is not listed, and is counted as skipped`() {
        val tab = NetworkDebugTab.create(captureFilter = NetworkCaptureFilter.of("No previews") { !it.url.contains("/preview") })
        val sink = Lustro.builder(app).addTab(tab).build().networkCaptureSink()

        val id = sink.beginRequest("https://cloud.example.com/preview?fileId=1", "GET", Headers.Builder().build(), null, null)
        sink.failRequest(id, 5, "not listed")
        awaitCaptures(tab)

        val poll = tab.handle(DebugRequest(path = "transactions", method = "GET")).json()
        assertEquals(0, poll.getJSONArray("items").length())
        assertEquals(1, poll.getJSONObject("state").getJSONObject("captureFilter").getInt("skipped"))
    }

    @Test
    fun `a mock rule the sink returns counts a hit`() {
        val tab = NetworkDebugTab.create()
        val sink = Lustro.builder(app).addTab(tab).build().networkCaptureSink()
        val rule = """{"id":"down","urlPattern":"/status.php","statusCode":503,"responseBody":"maintenance"}"""
        tab.handle(DebugRequest(path = "rules", method = "POST", body = rule.toByteArray(), contentType = MediaType.JSON))

        val found = sink.findMockRule("https://cloud.example.com/status.php", "GET")

        assertEquals(503, found?.statusCode)
        assertNull(sink.findMockRule("https://cloud.example.com/other", "GET"))
        val rules = tab.handle(DebugRequest(path = "rules", method = "GET")).json().getJSONArray("items")
        assertEquals(1, rules.getJSONObject(0).getInt("hitCount"))
    }

    @Test
    fun `with no network tab the sink records nothing and finds no rule`() {
        val sink = Lustro.builder(app).build().networkCaptureSink()

        val id = sink.beginRequest("https://cloud.example.com/", "GET", Headers.Builder().build(), null, null)
        sink.completeRequest(id, CapturedResponse.Builder(200, 1).build())
        sink.failRequest(id, 1, "ignored")

        assertNull(sink.findMockRule("https://cloud.example.com/", "GET"))
    }

    private fun awaitCaptures(tab: NetworkDebugTab) = assertTrue((tab.captureSink as NetworkTrafficStore).awaitCaptures())

    private fun transactions(tab: NetworkDebugTab): JSONArray = tab.handle(DebugRequest(path = "transactions", method = "GET")).json().getJSONArray("items")

    private fun DebugResponse?.json(): JSONObject = JSONObject(this!!.body.toString(Charsets.UTF_8))
}
