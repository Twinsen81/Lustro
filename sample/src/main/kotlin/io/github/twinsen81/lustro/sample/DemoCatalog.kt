package io.github.twinsen81.lustro.sample

import kotlinx.coroutines.flow.StateFlow

/**
 * One thing the sample can do. [label] names it for the `request` launch extra
 * and is the test tag of its control; [chip] is the shorter text on screen.
 * While [live] holds text, the control shows that text as a running state, for
 * example the request count of the periodic sync.
 */
internal class DemoAction(
    val label: String,
    val chip: String = label,
    val detail: String? = null,
    val badge: String? = null,
    val live: StateFlow<String?>? = null,
    val run: (Feedback) -> Unit,
)

internal enum class Tone { ACCENT, ERROR, VIOLET, ORANGE, SLATE }

internal class ActionGroup(
    val title: String,
    val tone: Tone,
    val note: String,
    val actions: List<DemoAction>,
)

internal object DemoCatalog {
    private const val WS_ECHO = "wss://echo.websocket.org/"
    private const val WS_REFUSED = "wss://httpbingo.org/status/403"
    private const val LOG_BURST_LINES = 500
    private const val LOG_REPEATS = 20

    fun network(
        requests: DemoRequests,
        webSocket: WebSocketDemo,
        sync: DemoAction,
        extras: List<DemoRequestSpec>,
    ): List<ActionGroup> =
        listOfNotNull(
            ActionGroup(
                "Basic requests",
                Tone.ACCENT,
                "One request for each HTTP method. POST, PUT and PATCH send a small JSON body.",
                listOf(
                    DemoAction("GET /get", detail = "A plain GET. The server answers with the request it got, as JSON.") {
                        requests.get("get", it)
                    },
                    DemoAction("POST /post", detail = "Sends a small JSON body. Open the row to see the request body and the response.") {
                        requests.post(it)
                    },
                    DemoAction("PUT /put", detail = "Sends a small JSON body with PUT.") { requests.put(it) },
                    DemoAction("PATCH /patch", detail = "Sends a small JSON body with PATCH.") { requests.patch(it) },
                    DemoAction("DELETE /delete", detail = "A DELETE without a body.") { requests.delete(it) },
                ),
            ),
            ActionGroup(
                "Errors & timeouts",
                Tone.ERROR,
                "Use Mocks in the Network tab to replace any of these responses.",
                listOf(
                    DemoAction(
                        "Mocked 500 (/status/500)",
                        chip = "500 /status/500",
                        detail = "The server answers 500. To replace this response, add a rule in Mocks.",
                    ) { requests.get("status/500", it) },
                    DemoAction(
                        "Slow call (/delay/3)",
                        chip = "Slow, 3 s",
                        detail = "The server waits 3 s before it answers. The row shows the request in flight, then its time.",
                    ) { requests.get("delay/3", it) },
                    DemoAction(
                        "Timeout (/delay/10, 2s budget)",
                        chip = "Timeout, 2 s budget",
                        detail = "The server waits 10 s, but the call allows 2 s. OkHttp stops the call, and the row shows the timeout error.",
                    ) { requests.timeout(it) },
                ),
            ),
            // The sockets come from the factory Lustro wraps, so the console shows
            // their messages; the interceptor alone would show only the handshake.
            ActionGroup(
                "WebSocket",
                Tone.VIOLET,
                "The echo server sends a greeting, then each message back. The console shows every message.",
                listOf(
                    DemoAction(
                        "WS connect (echo.websocket.org)",
                        chip = "Connect echo",
                        detail = "Opens a WebSocket to echo.websocket.org, which sends each message back. Open the WebSockets view of the Network tab.",
                    ) { webSocket.connect(WS_ECHO, it) },
                    DemoAction(
                        "WS send text",
                        chip = "Send text",
                        detail = "Sends a short text message. The echo comes back as a received message.",
                    ) { webSocket.sendText(it) },
                    DemoAction(
                        "WS send JSON with a token",
                        chip = "JSON with a token",
                        detail = "Sends JSON with a token field. The console masks the token before it stores the message.",
                    ) { webSocket.sendJsonWithToken(it) },
                    DemoAction(
                        "WS send binary",
                        chip = "Send binary",
                        detail = "Sends 48 bytes as a binary message. The console shows them as a hex dump.",
                    ) { webSocket.sendBinary(it) },
                    DemoAction(
                        "WS send a 300 KB text",
                        chip = "Send 300 KB",
                        detail = "Sends a 300 KB text. That is more than the 256 KiB capture limit, so the console keeps only the first part.",
                    ) { webSocket.sendLargeText(it) },
                    DemoAction(
                        "WS send 20 messages",
                        chip = "Send 20 messages",
                        detail = "Sends 20 small JSON messages, one every 150 ms, so the message log grows while you watch.",
                    ) { webSocket.sendMany(it) },
                    DemoAction(
                        "WS close (1000)",
                        chip = "Close",
                        detail = "Closes the socket with code 1000. The connection shows as closed.",
                    ) { webSocket.close(it) },
                    DemoAction(
                        "WS cancel",
                        chip = "Cancel",
                        detail = "Cancels the socket without a close handshake. The connection shows as canceled.",
                    ) { webSocket.cancel(it) },
                    DemoAction(
                        "WS connect, upgrade refused (/status/403)",
                        chip = "Refused upgrade (403)",
                        detail = "Tries to open a WebSocket on a URL that answers 403. The connection shows as failed.",
                    ) { webSocket.connect(WS_REFUSED, it) },
                ),
            ),
            ActionGroup(
                "Streaming (SSE)",
                Tone.ACCENT,
                "Five server-sent events in 5 s. The Network tab shows the row grow event by event.",
                listOf(
                    DemoAction(
                        "Stream SSE (/sse)",
                        chip = "Stream 5 events",
                        detail = "Reads a server-sent event stream: 5 events in 5 s. The row grows with each event, then completes.",
                    ) { requests.streamSse(it) },
                ),
            ),
            ActionGroup(
                "Body viewers",
                Tone.ACCENT,
                "The Network tab picks a viewer for a body by its content type.",
                listOf(
                    DemoAction("GET /json", detail = "A JSON response. The body opens in the Tree view.") { requests.get("json", it) },
                    DemoAction("GET /xml", detail = "An XML response, in the Pretty view.") { requests.get("xml", it) },
                    DemoAction("GET /html", detail = "An HTML response, in the Pretty view.") { requests.get("html", it) },
                    DemoAction("GET /image/jpeg", detail = "A JPEG response. The Preview view shows the picture.") { requests.get("image/jpeg", it) },
                    DemoAction("GET /image/svg", detail = "An SVG response. The Preview view shows the picture.") { requests.get("image/svg", it) },
                    DemoAction("GET /robots.txt", detail = "A plain text response.") { requests.get("robots.txt", it) },
                    DemoAction(
                        "POST a form (/post)",
                        chip = "POST form",
                        detail = "Sends a form with a password field. The Table view shows the fields, and the password is masked.",
                    ) { requests.formPost(it) },
                    DemoAction("POST a PNG (/anything)", chip = "POST PNG", detail = "Uploads a small PNG. The request body shows as a picture.") {
                        requests.pngPost(it)
                    },
                    DemoAction(
                        "POST a BMP (/anything)",
                        chip = "POST BMP",
                        detail = "Uploads a small BMP. The console has no preview for this type, so it shows a hex dump.",
                    ) { requests.bmpPost(it) },
                    DemoAction(
                        "POST a 250 KB JSON body (/anything)",
                        chip = "POST 250 KB JSON",
                        detail = "Uploads 250 KB of JSON. That is just under the 256 KiB capture limit, so the console keeps all of it.",
                    ) { requests.largeJsonPost(it) },
                ),
            ),
            ActionGroup(
                "Compressed bodies",
                Tone.ACCENT,
                "When the app sets Accept-Encoding itself, OkHttp does not decode the response, and interceptors see the compressed bytes.",
                listOf(
                    DemoAction(
                        "GET /get, app asks for gzip",
                        chip = "Asks for gzip",
                        detail = "The app sets Accept-Encoding: gzip itself, so OkHttp does not decode the response. Lustro decodes it for the console.",
                    ) { requests.compressedGet("gzip", it) },
                    DemoAction(
                        "GET /get, app asks for deflate",
                        chip = "Asks for deflate",
                        detail = "The same with deflate. Lustro decodes it for the console.",
                    ) { requests.compressedGet("deflate", it) },
                    DemoAction(
                        "GET /get, app asks for br (not decoded)",
                        chip = "Asks for br",
                        detail = "The same with br. Lustro does not decode br, so the row says that it kept no body.",
                    ) { requests.compressedGet("br", it) },
                    DemoAction(
                        "POST a gzip JSON body (/status/200)",
                        chip = "POST gzip body",
                        detail = "Sends a JSON body compressed with gzip, with Content-Encoding: gzip. Lustro decodes the request body.",
                    ) { requests.gzipPost(it) },
                ),
            ),
            ActionGroup(
                "Platform HTTP",
                Tone.SLATE,
                "Not OkHttp: these use java.net.HttpURLConnection. The debug build captures them with platform capture.",
                listOf(
                    DemoAction(
                        "Raw HttpURLConnection GET",
                        chip = "HttpURLConnection GET",
                        detail = "A GET through java.net.HttpURLConnection, not OkHttp. Platform capture records it, with the source platform.",
                    ) { requests.rawPlatformGet(it) },
                    DemoAction(
                        "Raw HttpURLConnection GET with $NO_CAPTURE_HEADER",
                        chip = "Not captured ($NO_CAPTURE_HEADER)",
                        detail =
                            "The same GET with the header $NO_CAPTURE_HEADER: 1. The sample's capture filter skips such requests, " +
                                "so no row appears, and the ⚠ button in the Network tab counts them.",
                    ) { requests.rawPlatformGet(it, headers = mapOf(NO_CAPTURE_HEADER to "1")) },
                    DemoAction(
                        "Raw HttpURLConnection GET, app asks for gzip",
                        chip = "HttpURLConnection, gzip",
                        detail = "The app asks for gzip itself, through java.net. Lustro decodes the response for the console.",
                    ) { requests.rawPlatformGet(it, path = "gzip", headers = mapOf("Accept-Encoding" to "gzip")) },
                    DemoAction("Volley GET", detail = "A GET through Volley, which uses HttpURLConnection. Platform capture records it too.") {
                        requests.volleyGet(it)
                    },
                ),
            ),
            ActionGroup(
                "Periodic sync",
                Tone.ACCENT,
                "GET /anything/sync every 2 s. Turn on Overwrite in the Network tab to fold the repeats into one row.",
                listOf(sync),
            ),
            extras.takeIf { it.isNotEmpty() }?.let { specs ->
                ActionGroup(
                    "More requests",
                    Tone.SLATE,
                    "Status codes, auth, and two requests that the capture filter leaves out.",
                    specs.map { spec -> DemoAction(spec.label, chip = spec.chip, detail = spec.detail) { requests.send(spec.buildRequest(), it) } },
                )
            },
        )

    // Lines for the Logs tab (GET /api/v1/logs/entries).
    fun logs(logs: DemoLogs): List<DemoAction> =
        listOf(
            DemoAction("Log a burst of 500 lines", chip = "Burst of 500 lines", detail = "Shows how a poll pages a backlog", badge = "500") {
                logs.burst(LOG_BURST_LINES)
                it.ok("Logged $LOG_BURST_LINES lines")
            },
            DemoAction("Log the same line 20 times", chip = "Repeat the same line", detail = "The Logs tab folds the repeats", badge = "×20") {
                logs.repeatLine(LOG_REPEATS)
                it.ok("Logged the same line $LOG_REPEATS times")
            },
            DemoAction("Log an exception", chip = "Log an exception", detail = "Each stack frame is a line of its own", badge = "!") {
                logs.exception()
                it.ok("Logged an exception")
            },
            DemoAction("Log a line with JSON", chip = "Log JSON", detail = "The row toolbar shows it indented", badge = "{ }") {
                logs.json()
                it.ok("Logged a line with JSON")
            },
            DemoAction("Log a fatal line", chip = "Log a fatal line", detail = "Level F, under the Fatal chip", badge = "F") {
                logs.fatal()
                it.ok("Logged a fatal line")
            },
        )

    fun prefs(prefs: DemoPrefs): List<DemoAction> =
        listOf(
            DemoAction(
                "Count a launch",
                detail = "Adds 1 to launch_count in ${DemoPrefs.SETTINGS}. The next poll of the file gets a delta",
                badge = "+1",
            ) {
                it.ok("launch_count is ${prefs.countLaunch()}")
            },
            DemoAction(
                "Rotate the session",
                detail = "New session_id in ${DemoPrefs.SESSION}, which the tab shows keys only and read-only",
                badge = "↻",
            ) {
                it.ok("New session ${prefs.rotateSession()}")
            },
            DemoAction(
                "Refresh the SDK config",
                detail = "Changes ${DemoPrefs.SDK_CACHE}, which the app does not register: the tab finds it on disk and masks api_key",
                badge = "SDK",
            ) {
                it.ok("config_version is ${prefs.refreshSdkConfig()}")
            },
            DemoAction(
                "Clear the settings from the app",
                chip = "Clear the settings",
                detail = "Before API 30, a clear calls no listener: the tab shows it with the next change",
                badge = "∅",
            ) {
                it.ok("Removed ${prefs.clearSettings()} keys from ${DemoPrefs.SETTINGS}")
            },
            DemoAction("Reset the demo preferences", chip = "Reset the values", detail = "Writes the demo values of the three files again", badge = "⟲") {
                prefs.reset()
                it.ok("Wrote the demo values again")
            },
        )
}
