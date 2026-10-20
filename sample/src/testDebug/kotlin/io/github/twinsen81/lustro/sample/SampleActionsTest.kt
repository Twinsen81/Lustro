package io.github.twinsen81.lustro.sample

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SampleActionsTest {
    private val viewModel = SampleViewModel(ApplicationProvider.getApplicationContext<Application>())

    // Scripts start the sample with `--es request "<label>"`; these labels must keep working.
    @Test
    fun `every label of the request launch extra names an action`() {
        val labels =
            listOf(
                "GET /get", "POST /post", "PUT /put", "PATCH /patch", "DELETE /delete",
                "Mocked 500 (/status/500)", "Slow call (/delay/3)", "Timeout (/delay/10, 2s budget)",
                "Stream SSE (/sse)",
                "WS connect (echo.websocket.org)", "WS send text", "WS send JSON with a token", "WS send binary",
                "WS send a 300 KB text", "WS send 20 messages", "WS close (1000)", "WS cancel",
                "WS connect, upgrade refused (/status/403)",
                "GET /get, app asks for gzip", "GET /get, app asks for deflate", "GET /get, app asks for br (not decoded)",
                "POST a gzip JSON body (/status/200)",
                "GET /json", "GET /xml", "GET /html", "GET /image/jpeg", "GET /image/svg", "GET /robots.txt",
                "POST a form (/post)", "POST a PNG (/anything)", "POST a BMP (/anything)", "POST a 250 KB JSON body (/anything)",
                "Start periodic sync", "Stop periodic sync",
                "Log a burst of 500 lines", "Log the same line 20 times", "Log an exception", "Log a line with JSON", "Log a fatal line",
                "Stop the Lustro server", "Start the Lustro server",
                "Raw HttpURLConnection GET", "Raw HttpURLConnection GET with X-No-Capture",
                "Raw HttpURLConnection GET, app asks for gzip", "Volley GET",
            ) + LustroBootstrap.extraDemoRequests(DemoRequests.BASE).map { it.label }

        assertEquals(emptyList<String>(), labels.filterNot { it in viewModel.byLabel })
    }

    @Test
    fun `the network module has fifty actions, each on one chip`() {
        val actions = viewModel.network.flatMap { it.actions }

        assertEquals(50, actions.size)
        assertEquals(actions.size, actions.map { it.label }.toSet().size)
        assertTrue(viewModel.network.last().debugOnly)
    }

    // The group sheet and the note under a group show this text.
    @Test
    fun `every network action says what it does`() {
        val undescribed = viewModel.network.flatMap { it.actions }.filter { it.detail.isNullOrBlank() }.map { it.label }

        assertEquals(emptyList<String>(), undescribed)
    }

    @Test
    fun `an unknown label reports a failure instead of running anything`() {
        viewModel.runByLabel("GET /nothing")

        val event = viewModel.events.recent.value.first()
        assertEquals(Outcome.FAILED, event.outcome)
        assertEquals("No action is labelled \"GET /nothing\"", event.text)
    }

    @Test
    fun `a log action reports its result to its own chip`() {
        viewModel.runByLabel("Log a line with JSON")

        assertEquals(Outcome.OK, viewModel.events.latestByLabel.value.getValue("Log a line with JSON").outcome)
    }
}
