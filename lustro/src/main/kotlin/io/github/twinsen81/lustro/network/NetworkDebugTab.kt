@file:Suppress("TooGenericExceptionCaught")

package io.github.twinsen81.lustro.network

import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugRoutes
import io.github.twinsen81.lustro.DebugRoutes.Companion.ref
import io.github.twinsen81.lustro.DebugTab
import io.github.twinsen81.lustro.ExperimentalDebugRoutes
import io.github.twinsen81.lustro.ExperimentalPlatformCapture
import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.MediaType
import io.github.twinsen81.lustro.escapeForJson
import io.github.twinsen81.lustro.escapeHtml
import io.github.twinsen81.lustro.internal.Versions
import io.github.twinsen81.lustro.internal.network.AppCaptureSink
import io.github.twinsen81.lustro.internal.network.CaptureSource
import io.github.twinsen81.lustro.internal.network.CapturingWebSocketFactory
import io.github.twinsen81.lustro.internal.network.HarExport
import io.github.twinsen81.lustro.internal.network.HarWebSocket
import io.github.twinsen81.lustro.internal.network.HttpUrlConnectionCapture
import io.github.twinsen81.lustro.internal.network.LustroNetworkInterceptor
import io.github.twinsen81.lustro.internal.network.MAX_THROTTLE_MS
import io.github.twinsen81.lustro.internal.network.MockRuleRoutes
import io.github.twinsen81.lustro.internal.network.NETWORK_SCHEMAS
import io.github.twinsen81.lustro.internal.network.NetworkCaptureProvider
import io.github.twinsen81.lustro.internal.network.NetworkDocument
import io.github.twinsen81.lustro.internal.network.NetworkSendRequestImpl
import io.github.twinsen81.lustro.internal.network.NetworkTrafficStore
import io.github.twinsen81.lustro.internal.network.SafeCaptureFilter
import io.github.twinsen81.lustro.internal.network.SEARCH_HELP_DIALOG
import io.github.twinsen81.lustro.internal.network.SearchBudget
import io.github.twinsen81.lustro.internal.network.SendCaptureTag
import io.github.twinsen81.lustro.internal.network.WebSocketCapture
import io.github.twinsen81.lustro.internal.network.WebSocketRoutes
import io.github.twinsen81.lustro.internal.network.WebSocketTrafficStore
import io.github.twinsen81.lustro.internal.network.appendTransactionJson
import io.github.twinsen81.lustro.internal.network.respondWithSearch
import io.github.twinsen81.lustro.internal.network.setThrottle
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Debug console tab for inspecting captured HTTP traffic and WebSocket messages,
 * and managing mock rules.
 *
 * Two-panel layout: the list of transactions or of WebSocket connections on the
 * left; on the right, the detail of the selected one, or the mock rules, or the
 * Send form, each in place of the detail. Created through the
 * [companion factory][Companion.create]; the
 * sink/interceptor wiring is internal. Adapted to the `/api/v1/network` wire
 * contract (cursor envelope, synchronous send) and the SPI
 * [NetworkCaptureFilter]/[NetworkClassifier]/[Redactor]/[MockRuleStorage] seams.
 */
public class NetworkDebugTab private constructor(
    private val store: NetworkTrafficStore,
    private val captureFilter: SafeCaptureFilter,
    private val senders: List<OkHttpSender>,
    // The senderClient form: its one sender takes DebugConfig.appServerBaseUrl.
    private val sendersUseConfigBaseUrl: Boolean,
    private val capturePlatformHttp: Boolean,
    maxBodyCaptureBytes: Long,
    redactor: Redactor,
    classifier: NetworkClassifier,
) : DebugTab() {
    override val id: String = "network"
    override val title: String = "Network"
    override val icon: String = "🌐"
    override val order: Int = 10

    // Set by the server on start so the Send panel can reject self-requests
    // against the actual bind host:port (not all loopback). Null until started.
    @Volatile
    private var serverHost: String? = null

    @Volatile
    private var serverPort: Int = -1

    // Mutable so the runtime can push DebugConfig values into the tab after
    // construction (see [applyConfig]); the proven defaults are kept here so
    // create() works standalone. The interceptor is built lazily by
    // networkInterceptor() and platform capture is installed in onStart(), both
    // after applyConfig has run, so they observe the configured values.
    @Volatile
    private var maxBodyCaptureBytes: Long = maxBodyCaptureBytes

    // Base for resolving the relative URLs of the senderClient sender; null =
    // relative URLs are rejected. Pushed in via applyConfig from
    // DebugConfig.appServerBaseUrl.
    @Volatile
    private var appServerBaseUrl: String? = null

    // The app's package name, pushed in via applyConfig. The console keeps the
    // hosts that the user checked under it: localStorage is per origin, and every
    // app reached through the same adb forward has the same origin.
    @Volatile
    private var appId: String? = null

    // Bounds a Send Request call; pushed in via applyConfig from
    // DebugConfig.requestTimeoutMs.
    @Volatile
    private var requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS

    internal val captureSink: NetworkCaptureSink
        get() = store

    private val webSocketStore = WebSocketTrafficStore()

    @Volatile
    private var platformCapture: HttpUrlConnectionCapture? = null

    // Tests replace it: they cannot undo the real process-wide handler factory.
    @Volatile
    internal var platform: HttpUrlConnectionCapture.Platform = HttpUrlConnectionCapture.Platform.Default

    // Shares the pause toggle, the capture filter, the redactor, and the payload
    // cap with HTTP capture, so one setting covers both kinds of traffic.
    internal val webSocketCapture: WebSocketCapture =
        WebSocketCapture(
            store = webSocketStore,
            redactor = redactor,
            classifier = classifier,
            captureFilter = captureFilter,
            isPaused = { store.isPaused() },
            maxPayloadBytes = { this.maxBodyCaptureBytes },
        )

    // How long a search may take. A test sets it to 0 to see a search stop.
    @Volatile
    internal var searchTimeoutMs: Long = SearchBudget.DEFAULT_TIMEOUT_MS

    private val webSocketRoutes = WebSocketRoutes(webSocketStore, { searchTimeoutMs }) { stateJson() }

    private val mockRuleRoutes = MockRuleRoutes(store)

    // What Lustro reaches the tab through. A separate object, not an interface
    // of the tab: the members of an interface that the tab implemented would be
    // public members of the tab, which the no-op half does not have.
    internal val captureProvider: NetworkCaptureProvider =
        object : NetworkCaptureProvider {
            override fun createInterceptor(captureEnabled: () -> Boolean): Interceptor =
                this@NetworkDebugTab.createInterceptor(captureEnabled)

            override fun createAppCaptureSink(captureEnabled: () -> Boolean): NetworkCaptureSink =
                this@NetworkDebugTab.createAppCaptureSink(captureEnabled)

            override fun wrapWebSocketFactory(delegate: WebSocket.Factory): WebSocket.Factory =
                this@NetworkDebugTab.wrapWebSocketFactory(delegate)

            override fun bindTo(host: String, port: Int) {
                this@NetworkDebugTab.bindTo(host, port)
            }
        }

    // A factory that is already capturing is returned as it is, so wrapping
    // twice doesn't record every message twice.
    internal fun wrapWebSocketFactory(delegate: WebSocket.Factory): WebSocket.Factory =
        delegate as? CapturingWebSocketFactory ?: CapturingWebSocketFactory(delegate, webSocketCapture)

    internal fun createInterceptor(captureEnabled: () -> Boolean): Interceptor =
        LustroNetworkInterceptor(
            sink = store,
            captureEnabled = { captureEnabled() && !store.isPaused() },
            captureFilter = captureFilter,
            throttleDelayMs = { store.getThrottleDelayMs() },
            incrementMockHit = { store.incrementHitCount(it) },
            maxBodySize = maxBodyCaptureBytes,
            recordThrottle = { id, delayMs -> store.recordThrottle(id, delayMs) },
            recordRequestBody = { id, body, contentType -> store.recordRequestBody(id, body, contentType) },
        )

    internal fun createAppCaptureSink(captureEnabled: () -> Boolean): NetworkCaptureSink =
        AppCaptureSink(
            store = store,
            captureEnabled = { captureEnabled() && !store.isPaused() },
            captureFilter = captureFilter,
        )

    /** Records the live bind address so the Send panel can detect self-requests. */
    internal fun bindTo(host: String, port: Int) {
        serverHost = host
        serverPort = port
    }

    /**
     * Internal config-injection seam invoked by `Lustro.Builder.build()` so the
     * tab honours [DebugConfig] instead of its standalone factory defaults:
     * the transaction ring cap, the per-body capture cap, the base URL used
     * to resolve relative Send Request URLs, the per-request timeout that
     * bounds a Send Request call, and the app's package name.
     */
    internal fun applyConfig(
        maxCaptureTransactions: Int,
        maxBodyCaptureBytes: Long,
        appServerBaseUrl: String?,
        captureBudgetBytes: Long,
        requestTimeoutMs: Long,
        maxCaptureWebSockets: Int,
        maxWebSocketEvents: Int,
        webSocketCaptureBudgetBytes: Long,
        appId: String? = null,
    ) {
        this.appId = appId
        store.maxTransactions = maxCaptureTransactions
        store.captureBudgetBytes = captureBudgetBytes
        webSocketStore.maxConnections = maxCaptureWebSockets
        webSocketStore.maxEventsPerConnection = maxWebSocketEvents
        webSocketStore.budgetBytes = webSocketCaptureBudgetBytes
        this.maxBodyCaptureBytes = maxBodyCaptureBytes
        this.appServerBaseUrl = appServerBaseUrl
        this.requestTimeoutMs = requestTimeoutMs
    }

    override fun onStart() {
        // Platform capture installs a process-global handler; it is best-effort and
        // fail-open. Gated by capturePlatformHttp. Built here (after applyConfig)
        // so it observes the configured body cap. A process can set the handler
        // factory only once, so the tab keeps its capture: on a start after
        // Lustro.stop() the factory is still in place and install() returns at once.
        if (capturePlatformHttp) {
            val capture =
                platformCapture ?: HttpUrlConnectionCapture(
                    sink = store.sinkFor(CaptureSource.PLATFORM),
                    isPaused = { store.isPaused() },
                    captureFilter = captureFilter,
                    maxBodySize = maxBodyCaptureBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    platform = platform,
                ).also { platformCapture = it }
            capture.install()
        }
    }

    override fun renderContent(): String =
        """
        <div class="dc-split" id="net-root" data-view="http"${appId?.let { " data-app-id=\"${it.escapeHtml()}\"" }.orEmpty()}>
            <div class="dc-pane net-list-pane">
                <div class="dc-toolbar net-list-head">
                    <h3 class="dc-mono-label net-list-title">Network Traffic</h3>
                    <div class="dc-seg dc-seg--sm net-view-seg" role="group" aria-label="Kind of traffic">
                        <button class="dc-seg__item dc-seg__item--active" id="http-view-btn" data-action="switchTrafficView" data-view="http" title="HTTP requests and responses.">HTTP<span id="tx-count" class="net-view-count"> (0)</span></button>
                        <button class="dc-seg__item" id="ws-view-btn" data-action="switchTrafficView" data-view="ws" title="WebSocket connections and the messages of each one. A socket is listed when the app creates it with the factory from Lustro.webSocketFactory.">WebSockets<span id="ws-count" class="net-view-count"> (0)</span></button>
                    </div>
                    <button class="dc-btn dc-btn--icon net-filter-flag" id="capture-filter-btn" data-action="showCaptureFilter" hidden aria-label="Requests skipped by the app filters">⚠</button>
                    <div class="net-list-actions">
                    <button class="dc-btn" id="pause-btn" data-action="togglePause" title="Pause traffic capture. The interceptor still runs but new requests and WebSocket messages are not recorded. Click again to resume.">⏸ Pause</button>
                    <button class="dc-btn net-http-only" id="overwrite-btn" data-action="toggleOverwriteMode" title="Overwrite mode: when a new request arrives, any earlier completed transaction with the same method + URL path is removed from the list. In-flight requests are never evicted.">Overwrite: off</button>
                    <select class="dc-btn net-http-only" id="throttle-select" name="throttleDelayMs" aria-label="Global throttle" data-action="setThrottle" title="Global throttle: sleep this long before every OkHttp request, mocked or real. A platform HttpURLConnection request, or one that the app's own adapter reports, is not held. Useful for testing loading spinners and timeout handling.">
                        <option value="0">No throttle</option>
                        <option value="500">500ms</option>
                        <option value="1000">1s</option>
                        <option value="3000">3s</option>
                        <option value="5000">5s</option>
                        <option value="10000">10s</option>
                        <option value="30000">30s</option>
                        <option value="60000">1 min</option>
                    </select>
                    <button class="dc-btn dc-btn--ghost-danger" data-action="clearTraffic" title="Clear the captured requests and WebSocket connections. Mock rules and settings are preserved. Keyboard shortcut: C (when not typing in an input).">Clear</button>
                    </div>
                </div>
                <div class="net-search net-http-only">
                    <div class="dc-field"><label class="net-search-label" for="search-input"><span class="dc-field__prefix" aria-hidden="true">&gt;</span><input type="text" id="search-input" name="search" aria-label="Search network traffic" aria-describedby="search-input-error" class="dc-input" placeholder="filter url, method, headers, body…" autocomplete="off" spellcheck="false" data-action="onSearchInput" title="Search the URL, the method, the headers, and the bodies for this text, ignoring case, on the device, 300 ms after you stop typing. With REGEX on, each word is a regular expression: all must match, and -word leaves out. Matches are marked in the detail. Ctrl/Cmd+K focuses the field, and Escape clears it." /></label><button type="button" class="dc-btn dc-btn--icon net-search-clear" data-action="clearSearch" data-target="search-input" hidden aria-label="Clear the search" title="Clear the search. Escape in the field does the same.">×</button><button type="button" class="dc-btn dc-btn--sm net-search-regex" data-action="toggleSearchRegex" data-target="search-input" aria-pressed="false" title="Regular expressions. On: each word is a Java regular expression that ignores case, all words must match, each in any field, and -word leaves out, such as featured|popular -analytics. Off: the text is found as typed. Remembered in your browser.">Regex</button><button type="button" class="dc-btn dc-btn--icon net-search-help" data-action="showSearchHelp" aria-label="Regex examples" title="Common regular expressions, with examples.">?</button></div>
                    <button class="dc-btn" id="new-request-btn" data-action="openNewRequest" hidden title="Compose a request and send it through one of the OkHttp clients that the app gave to Lustro. The form opens in the right pane.">+ New request</button>
                    <button class="dc-btn" id="mocks-btn" data-action="openMockRules" title="Mock rules: a request that a rule matches gets the rule's response and never leaves the device. The rules apply to all OkHttp traffic, not only to the selected request, and the number is how many are enabled. The list and the form open in the right pane.">Mocks<span id="mocks-count" class="net-mocks-count"></span></button>
                    <div class="net-search-error" id="search-input-error" role="alert" hidden></div>
                </div>
                <div class="net-category-bar net-http-only" id="category-filters"></div>
                <div class="net-filter-bar net-http-only" id="status-filters"></div>
                <div class="net-filter-bar net-http-only" id="method-filters"></div>
                <div class="net-select-bar net-http-only" id="select-bar" role="toolbar" aria-label="Actions for the selected requests" hidden>
                    <span id="select-count" class="net-select-count">0 selected</span>
                    <span id="select-note" class="net-select-note" hidden title="A filter or search change keeps only the selected requests that it still shows, so an export has the rows on screen."></span>
                    <button class="dc-btn dc-btn--sm" id="copy-selection-md-btn" data-action="copySelectionMarkdown" disabled title="Copy the selected requests and their responses as one Markdown document, for a bug report, a pull request, or a chat.">Copy Markdown</button>
                    <button class="dc-btn dc-btn--sm dc-btn--primary" id="export-har-btn" data-action="exportSelectionHar" disabled title="Save the selected requests as a HAR file, which browser devtools and HTTP tools import. Headers and bodies are redacted, as shown here.">Export HAR</button>
                    <button class="dc-btn dc-btn--sm" id="clear-selection-btn" data-action="clearTxSelection" title="Deselect every request.">Clear selection</button>
                </div>
                <div style="flex:1;overflow-y:auto">
                    <table class="dc-table net-ws-only" id="ws-table">
                        <thead class="dc-thead">
                            <tr>
                                <th class="dc-th" style="width:104px">State</th>
                                <th class="dc-th">URL</th>
                                <th class="dc-th" style="width:132px" title="Messages the app sent, and messages it received.">Messages</th>
                                <th class="dc-th" style="width:104px">Started</th>
                            </tr>
                        </thead>
                        <tbody id="ws-list"></tbody>
                    </table>
                    <table class="dc-table net-http-only">
                        <thead class="dc-thead">
                            <tr>
                                <th class="dc-th net-select-col" id="select-col" data-action="toggleSelectAll" title="Select every request the filters show, or none. Selected requests can be copied as Markdown or exported as a HAR file."><input type="checkbox" id="select-all" class="net-check" aria-label="Select every request the filters show"></th>
                                <th class="dc-th" style="width:74px">Method</th>
                                <th class="dc-th net-url-col" id="url-col"><div class="net-url-head">URL<div class="dc-menu-anchor net-hosts"><button type="button" class="net-hosts-btn" id="hosts-btn" data-action="toggleHostsMenu" aria-haspopup="true" aria-expanded="false" aria-controls="hosts-menu"></button><div class="dc-menu-scrim" id="hosts-menu-scrim" data-action="closeHostsMenu" hidden></div><div class="dc-menu net-hosts-menu" id="hosts-menu" role="menu" aria-label="The app's hosts" hidden></div></div></div></th>
                                <th class="dc-th" style="width:60px">Status</th>
                                <th class="dc-th" style="width:66px">Time</th>
                                <th class="dc-th" style="width:96px">Cat</th>
                            </tr>
                        </thead>
                        <tbody id="tx-list"></tbody>
                    </table>
                </div>
            </div>
            <div class="dc-divider"></div>
            <div class="dc-pane net-detail-pane">
                <div class="net-detail-bar" id="detail-bar" role="toolbar" aria-label="Actions for the selected request" hidden>
                    <button class="dc-btn dc-btn--sm" id="resend-btn" data-action="editAndResend" hidden title="Open this request in the Send form: method, URL, headers, and body. Change what you need and send it again. The captured request stays as it is.">Edit and resend</button>
                    <button class="dc-btn dc-btn--sm" id="mock-this-btn" data-action="mockThis">Mock this request</button>
                    <button class="dc-btn dc-btn--sm" id="request-rules-btn" data-action="openRequestRules">Matching rules<span id="request-rules-count"></span></button>
                    <button class="dc-btn dc-btn--sm net-detail-bar-copy" id="copy-curl-btn" data-action="copyCurl" title="Copy a cURL command that reproduces the selected request (paste into a terminal to re-run).">cURL</button>
                    <button class="dc-btn dc-btn--sm" id="copy-md-btn" data-action="copyMarkdown" title="Copy the request and response as Markdown, for a bug report, a pull request, or a chat. Headers and bodies go in code blocks.">Markdown</button>
                    <button class="dc-btn dc-btn--sm" id="copy-all-btn" data-action="copyAllDetail" title="Copy the full request and response (status, headers, bodies) as plain text for sharing or pasting into a bug report.">Copy</button>
                </div>
                <div id="detail-content" class="dc-tabpanel net-tab-content active">
                    <div class="net-empty-state">
                        <div class="net-empty-icon">🔍</div>
                        <p>Select a request to inspect</p>
                    </div>
                </div>
                <div id="rules-content" class="dc-tabpanel net-tab-content">
                    <div class="net-pane-head">
                        <h4 title="A request that a rule matches gets the rule's response and never leaves the device. Rules are tried from the top, so the first match answers, and a new rule goes on top. Rules live in the app and survive a restart when it gives Lustro a rule storage.">Mock rules</h4>
                        <span class="net-pane-sub" id="rules-scope"></span>
                        <button class="dc-btn dc-btn--icon" data-action="closeRightPane" aria-label="Close the mock rules" title="Close the rules and show the detail again. Escape does the same.">✕</button>
                    </div>
                    <div id="rules-list"></div>
                    <div id="rule-form-container"></div>
                </div>
                <div id="send-content" class="dc-tabpanel net-tab-content">
                    <div id="send-form-container"></div>
                </div>
            </div>
        </div>
        <div id="capture-filter-modal" class="dc-modal-scrim" hidden>
            <div class="dc-modal" role="dialog" aria-labelledby="capture-filter-title">
                <div class="dc-modal__head">
                    <span class="dc-modal__title" id="capture-filter-title">Skipped by the app filters</span>
                    <button class="dc-modal__close" data-action="closeCaptureFilter" title="Close.">×</button>
                </div>
                <div class="dc-modal__body">
                    <p class="net-filter-note">The app's code sets a capture filter, and it left requests out of this list. Mock rules and the throttle still apply to them.</p>
                    <div class="net-filter-field"><span class="dc-label">Description</span><p id="capture-filter-description" class="net-filter-description"></p></div>
                    <div class="net-filter-counts">
                        <div class="net-filter-field"><span class="dc-label">Skipped</span><span id="capture-filter-skipped" class="net-filter-count"></span></div>
                        <div class="net-filter-field" title="The filter threw an exception on these requests, so they were captured. Logcat has the first failure under the LustroCapture tag."><span class="dc-label">Failed</span><span id="capture-filter-failed" class="net-filter-count"></span></div>
                    </div>
                    <p class="net-filter-note">The counts start again when you clear the list.</p>
                </div>
                <div class="dc-modal__foot">
                    <button class="dc-btn dc-btn--primary" data-action="closeCaptureFilter" title="Close.">Close</button>
                </div>
            </div>
        </div>
        """.trimIndent() + "\n" + SEARCH_HELP_DIALOG

    /**
     * Declares the routes under `/api/v1/network/`, which also make the tab's OpenAPI document.
     * `POST send` is declared only when the app gave a sender, so without one the route and
     * its document are absent, and the request gets `404`.
     */
    @OptIn(ExperimentalDebugRoutes::class)
    override fun routes(routes: DebugRoutes) {
        routes.title = NetworkDocument.TITLE
        // The Network routes are the core of the wire protocol, so they have its version.
        routes.version = Versions.PROTOCOL_VERSION
        routes.description = NetworkDocument.DESCRIPTION
        for ((name, schema) in NETWORK_SCHEMAS) routes.component(name, schema)

        routes.get("transactions") {
            operationId = "listTransactions"
            summary = "Poll captured transactions with an opaque cursor."
            description = NetworkDocument.TRANSACTIONS
            val cursor = query.string("cursor") { description = NetworkDocument.CURSOR }
            val search = query.string("search") { description = NetworkDocument.TRANSACTIONS_SEARCH }
            val regex = query.boolean("regex") { description = NetworkDocument.REGEX }.withDefault(false)
            val since = query.long("since") { min = 0; description = NetworkDocument.TRANSACTIONS_SINCE }
            response("Cursor envelope.", ref("TransactionCursorEnvelope"))
            handle { call -> transactions(call[cursor], call[search], call[regex], call[since]) }
        }
        routes.get("transactions/{id}") {
            operationId = "getTransaction"
            summary = "Full detail for one captured transaction."
            val id = path.string("id")
            response("Transaction detail.", ref("Transaction"))
            errors(404)
            handle { call -> transactionDetail(call[id]) }
        }
        routes.get("transactions/{id}/body/{direction}") {
            operationId = "getTransactionBody"
            summary = "One captured body, as it is stored."
            description = NetworkDocument.TRANSACTION_BODY
            val id = path.string("id")
            val direction = path.string("direction") { values = listOf(REQUEST, RESPONSE) }
            response("The body.") {
                header("Content-Disposition", NetworkDocument.TRANSACTION_BODY_DISPOSITION)
                header("Content-Security-Policy", NetworkDocument.TRANSACTION_BODY_CSP)
                content(ANY_CONTENT)
            }
            errors(404)
            handle { call -> transactionBody(call[id], request = call[direction] == REQUEST) }
        }
        routes.get("transactions/_/export") {
            operationId = "exportTransactions"
            summary = "Captured transactions as a HAR 1.2 document."
            description = NetworkDocument.EXPORT
            // The router refuses another format, so the handler needs no value.
            query.string("format") { values = listOf(EXPORT_FORMAT_HAR); description = "The export format. 400 for any other." }
                .withDefault(EXPORT_FORMAT_HAR)
            val ids = query.strings("ids") { description = NetworkDocument.EXPORT_IDS }
            response("A HAR document.", ref("HarDocument"))
            handle { call -> export(call[ids]) }
        }
        webSocketRoutes.declare(routes)
        routes.post("clear") {
            operationId = "clearTransactions"
            summary = NetworkDocument.CLEAR_SUMMARY
            response("Acknowledged.", NetworkDocument.OK)
            handle { clear() }
        }
        mockRuleRoutes.declare(routes)
        routes.post("pause") {
            operationId = "togglePause"
            summary = NetworkDocument.PAUSE_SUMMARY
            response("New state.", NetworkDocument.PAUSE_RESPONSE_SCHEMA)
            handle { togglePause() }
        }
        routes.post("overwrite-mode") {
            operationId = "toggleOverwriteMode"
            summary = NetworkDocument.OVERWRITE_MODE_SUMMARY
            response("New state.", NetworkDocument.OVERWRITE_MODE_RESPONSE_SCHEMA)
            handle { toggleOverwriteMode() }
        }
        routes.post("throttle") {
            operationId = "setThrottle"
            summary = "Set a global pre-request throttle delay (ms)."
            description = NetworkDocument.THROTTLE
            val delayMs = body.int("delayMs") { min = 0; max = MAX_THROTTLE_MS.toLong() }.required()
            response("New delay.", NetworkDocument.THROTTLE_RESPONSE_SCHEMA)
            handle { call -> setThrottle(store, call[delayMs]) }
        }
        routes.get("senders") {
            operationId = "listSenders"
            summary = NetworkDocument.SENDERS_SUMMARY
            description = NetworkDocument.SENDERS
            response("The senders, in order.", NetworkDocument.SENDERS_RESPONSE_SCHEMA)
            handle { listSenders() }
        }
        if (senders.isNotEmpty()) {
            routes.post("send") {
                operationId = "sendRequest"
                summary = "Send a request through one of the app's senders (synchronous)."
                description = NetworkDocument.SEND
                val url = body.string("url") { description = NetworkDocument.SEND_URL }.required()
                val sender = body.string("sender") { description = NetworkDocument.SEND_SENDER; schema = """{"type": ["string", "null"]}""" }
                val method = body.string("method").withDefault("GET")
                val headers = body.json("headers") { schema = """{"type": "object", "additionalProperties": {"type": "string"}}""" }
                val requestBody = body.string("body")
                response("Synchronous send result.", NetworkDocument.SEND_RESPONSE_SCHEMA)
                errors(504)
                handle { call -> send(call[url], call[sender], call[method], call[headers], call[requestBody]) }
            }
        }
    }

    private fun transactions(cursor: String?, search: String?, regex: Boolean, since: Long?): DebugResponse =
        respondWithSearch(search, regex) { query ->
            DebugResponse.cursorEnvelope(
                // A handshake's webSocketId depends on which connections are listed, and
                // each transaction's activeMockRuleId on the rules, so a change to either
                // is a change to this list. Every count only grows.
                currentSequence = store.getSequence() + webSocketStore.getMembershipSequence() + store.getRulesRevision(),
                clientCursor = cursor,
                state = stateJson(),
                epoch = store.epoch,
            ) {
                val transactions = store.getTransactions(search = query, budget = SearchBudget(searchTimeoutMs), since = since)
                val webSockets = webSocketStore.transactionLinks()
                // One snapshot, so every row is matched against the same rules.
                val rules = store.getMockRules()
                transactions.forEachIndexed { index, tx ->
                    if (index > 0) append(",")
                    appendTransactionJson(tx, brief = true, webSocketId = webSockets[tx.id], rules = rules)
                }
            }
        }

    private fun stateJson(): String =
        buildString {
            append("{")
            append("\"paused\":").append(store.isPaused()).append(",")
            append("\"overwriteMode\":").append(store.isOverwriteMode()).append(",")
            append("\"throttleDelayMs\":").append(store.getThrottleDelayMs()).append(",")
            append("\"mockRules\":{\"enabled\":").append(store.getMockRules().count { it.enabled })
            append(",\"revision\":").append(store.getRulesRevision()).append("},")
            append("\"captureFilter\":")
            if (captureFilter.isSet) {
                append("{\"description\":\"").append(captureFilter.description.escapeForJson()).append("\",")
                append("\"skipped\":").append(captureFilter.skipped).append(",")
                append("\"failed\":").append(captureFilter.failed).append("}")
            } else {
                append("null")
            }
            append("}")
        }

    private fun transactionDetail(txId: String): DebugResponse {
        val tx = store.getTransaction(txId) ?: return DebugResponse.notFound("Transaction not found")
        return DebugResponse.json {
            appendTransactionJson(tx, brief = false, webSocketId = webSocketStore.transactionLinks()[tx.id], rules = store.getMockRules())
        }
    }

    /**
     * Serves a body as it is stored: the bytes of a binary body, or the redacted
     * text of a text body as UTF-8. Only the raster image types the console
     * shows are served inline. Every other type, SVG and HTML included, is an
     * attachment with a sandboxing CSP, so a captured page can never run script
     * under the console's origin.
     */
    private fun transactionBody(txId: String, request: Boolean): DebugResponse {
        val tx = store.getTransaction(txId) ?: return DebugResponse.notFound("Transaction not found")
        val bytes = if (request) tx.requestBinaryBody else tx.responseBinaryBody
        val text = if (request) tx.requestBody else tx.responseBody
        val captured = (if (request) tx.requestContentType else tx.responseContentType)?.let { MediaType.parse(it) }
        // Rebuilt from its type and subtype alone: a text body is re-encoded as
        // UTF-8, and nothing else from a captured header is sent on.
        val essence = captured?.takeIf { MEDIA_TOKEN.matches(it.type) && MEDIA_TOKEN.matches(it.subtype) }
            ?.let { "${it.type}/${it.subtype}" }
        val body: ByteArray
        val contentType: MediaType
        when {
            bytes != null -> {
                body = bytes
                contentType = essence?.let { MediaType.parse(it) } ?: MediaType.OCTET_STREAM
            }
            text != null -> {
                body = text.toByteArray(Charsets.UTF_8)
                contentType = essence?.let { MediaType.parse("$it; charset=utf-8") } ?: MediaType.TEXT
            }
            else -> return DebugResponse.notFound("No ${if (request) "request" else "response"} body was retained")
        }
        // The sandbox is for attachments only: it would also block the inline
        // styles a browser's own image viewer uses when an image is opened on its own.
        val headers =
            if (essence in INLINE_BODY_TYPES) {
                Headers.EMPTY
            } else {
                Headers.of("Content-Disposition" to "attachment", "Content-Security-Policy" to ATTACHMENT_CSP)
            }
        return DebugResponse.bytes(body, contentType, headers = headers)
    }

    /**
     * The transactions named by [ids], or every one when it is `null`, as a HAR
     * document, oldest first. An id the store no longer has is left out, so a
     * selection taken just before an eviction still exports what remains.
     */
    private fun export(ids: List<String>?): DebugResponse {
        // Repeated ids parameters and comma-separated lists both work. An ids
        // parameter that names none is an empty selection, not every transaction.
        val named = ids?.flatMap { it.split(',') }?.map { it.trim() }?.filter { it.isNotEmpty() }
        val transactions =
            if (named == null) store.getTransactions() else named.distinct().mapNotNull { store.getTransaction(it) }
        val oldestFirst = transactions.sortedWith(compareBy({ it.startedAt }, { it.startOrder }))
        // A socket's messages go out with its handshake.
        val webSockets = webSocketStore.transactionLinks()
        val har =
            HarExport.write(oldestFirst, Versions.LIBRARY_VERSION) { tx ->
                webSockets[tx.id]?.let { id ->
                    val connection = webSocketStore.getConnection(id)
                    val log = webSocketStore.getEvents(id)
                    if (connection != null && log != null) HarWebSocket(connection, log.events) else null
                }
            }
        return DebugResponse.ok(har)
    }

    // The filter's counts restart with the list, so they describe the requests
    // missing from what is on screen.
    private fun clear(): DebugResponse {
        store.clear()
        webSocketStore.clear()
        captureFilter.resetCounts()
        return ok()
    }

    private fun togglePause(): DebugResponse {
        val newState = !store.isPaused()
        store.setPaused(newState)
        return DebugResponse.json { append("{\"status\":\"ok\",\"paused\":$newState}") }
    }

    private fun toggleOverwriteMode(): DebugResponse {
        val newState = !store.isOverwriteMode()
        store.setOverwriteMode(newState)
        return DebugResponse.json { append("{\"status\":\"ok\",\"overwriteMode\":$newState}") }
    }

    // Throws what a sender's base URL provider throws.
    private fun baseUrlOf(sender: OkHttpSender): String? =
        sender.baseUrl ?: appServerBaseUrl.takeIf { sendersUseConfigBaseUrl }

    private fun listSenders(): DebugResponse =
        DebugResponse.json {
            append("{\"items\":[")
            senders.forEachIndexed { index, sender ->
                if (index > 0) append(",")
                append("{\"name\":\"").append(sender.name.escapeForJson()).append("\",\"baseUrl\":")
                // A provider that throws lists no base URL; a send of a relative URL reports why.
                val base =
                    try {
                        baseUrlOf(sender)
                    } catch (_: Exception) {
                        null
                    }
                if (base == null) append("null") else append("\"").append(base.escapeForJson()).append("\"")
                append("}")
            }
            append("]}")
        }

    private fun send(url: String, senderName: String?, requestMethod: String, headersJson: String?, bodyText: String?): DebugResponse {
        return try {
            val rawUrl = url.trim()
            val method = requestMethod.uppercase()
            if (rawUrl.isBlank()) {
                return DebugResponse.error("url is required", field = "url")
            }
            val sender =
                if (senderName == null) {
                    senders.first()
                } else {
                    senders.firstOrNull { it.name == senderName }
                        ?: return DebugResponse.error(
                            "No sender is named \"$senderName\"",
                            field = "sender",
                            hint = "GET senders lists the names",
                        )
                }
            val resolvedUrl =
                if (ABSOLUTE_URL.containsMatchIn(rawUrl)) {
                    rawUrl
                } else {
                    val base =
                        try {
                            baseUrlOf(sender)
                        } catch (e: Exception) {
                            return DebugResponse.error(
                                "The base URL provider of the sender \"${sender.name}\" threw: ${e.message ?: e.javaClass.simpleName}",
                                field = "baseUrl",
                            )
                        } ?: return DebugResponse.error(
                            "url must be absolute: the sender \"${sender.name}\" has no base URL",
                            field = "url",
                        )
                    if (base.toHttpUrlOrNull() == null) {
                        return DebugResponse.error(
                            "The base URL of the sender \"${sender.name}\" is not an absolute http or https URL: $base",
                            field = "baseUrl",
                        )
                    }
                    // Appended, not resolved as RFC 3986 does: a base with a path,
                    // such as https://api.example.com/v1, keeps it.
                    base.trimEnd('/') + "/" + rawUrl.trimStart('/')
                }
            if (isSelfRequest(resolvedUrl)) {
                return DebugResponse.error("Refusing to send to the debug server itself")
            }
            val headersBuilder = Headers.Builder()
            var contentType: MediaType? = null
            // Read as org.json reads it, so a value that is not a string is sent as its text. Headers that are not an object are left out.
            val headers = headersJson?.let { JSONTokener(it).nextValue() as? JSONObject }
            if (headers != null) {
                val keys = headers.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = headers.optString(key)
                    // Carry Content-Type via the body's media type instead of a raw
                    // header so it isn't duplicated.
                    if (key.equals("Content-Type", ignoreCase = true)) {
                        contentType = MediaType.parse(value)
                    } else {
                        headersBuilder.add(key, value)
                    }
                }
            }
            val requestBody = bodyText?.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
            val sendRequest =
                NetworkSendRequestImpl(
                    url = resolvedUrl,
                    method = method,
                    headers = headersBuilder.build(),
                    body = requestBody,
                    contentType = contentType ?: MediaType.JSON,
                )
            val captureTag = SendCaptureTag()
            // SYNCHRONOUS: block for the sender result. The runtime calls handle()
            // off the main thread, so blocking here is safe. Only the status and
            // outcome are reported, so the response read is capped at the capture
            // cap. The call timeout runs a moment past the per-request timeout:
            // the server still answers 504 first, then OkHttp cancels the call so a
            // slow or stalled response cannot keep the worker thread and socket busy.
            val result =
                sender.send(
                    sendRequest,
                    maxResponseBodyBytes = maxBodyCaptureBytes,
                    callTimeoutMs = requestTimeoutMs + SEND_CANCEL_GRACE_MS,
                    captureTag = captureTag,
                )
            DebugResponse.json {
                append("{")
                append("\"transactionId\":")
                // Set when the sender's client carries the Lustro interceptor and
                // the request was captured.
                val transactionId = captureTag.transactionId
                if (transactionId == null) append("null") else append("\"").append(transactionId.value.escapeForJson()).append("\"")
                append(",\"sender\":\"").append(sender.name.escapeForJson()).append("\"")
                append(",\"url\":\"").append(resolvedUrl.escapeForJson()).append("\"")
                append(",\"statusCode\":").append(result.statusCode)
                append(",\"ok\":").append(result.isSuccess)
                if (!result.isSuccess) {
                    append(",\"error\":\"").append((result.errorMessage ?: "send failed").escapeForJson()).append("\"")
                }
                append("}")
            }
        } catch (e: Exception) {
            DebugResponse.error("Failed to send: ${e.message}")
        }
    }

    private fun isSelfRequest(rawUrl: String): Boolean {
        // Compare against the server's ACTUAL bind host:port (not all loopback): a
        // real server that happens to expose /api/network/* must not be rejected.
        val host = serverHost ?: return false
        val port = serverPort
        if (port < 0) return false
        return try {
            val parsed = java.net.URI(rawUrl)
            val parsedHost = parsed.host?.lowercase() ?: return false
            val parsedPort =
                if (parsed.port != -1) parsed.port else if (parsed.scheme == "https") 443 else 80
            parsedHost == host.lowercase() && parsedPort == port
        } catch (_: Exception) {
            false
        }
    }

    private fun ok(): DebugResponse = DebugResponse.json { append("{\"status\":\"ok\"}") }

    /** Factory for [NetworkDebugTab]. */
    public companion object {
        /**
         * Creates a [NetworkDebugTab] that captures only OkHttp traffic (via the
         * interceptor from [Lustro.networkInterceptor]), and the messages of the
         * WebSockets that the app creates with the factory from
         * [Lustro.webSocketFactory]. The [redactor], the [classifier], and the
         * [captureFilter] apply to those sockets too.
         *
         * This is the safe, default factory: it does not touch the platform
         * `HttpURLConnection` machinery, so it needs no opt-in. To additionally
         * capture `HttpURLConnection` traffic, use the
         * [overload][create] that takes `capturePlatformHttp`, which is gated by
         * [ExperimentalPlatformCapture].
         *
         * @param senderClient when non-null, the Send form sends through this
         *   client, as one sender named "Default" whose relative URLs resolve
         *   against `DebugConfig.appServerBaseUrl`; when `null`, the Send route
         *   is hidden. To send through several clients, or through one that is
         *   built after Lustro, use the overload that takes `senders`.
         * @param classifier tags captured URLs with category labels (default: none).
         * @param redactor removes sensitive data at capture time (default:
         *   [DefaultRedactor]).
         * @param mockRuleStorage persists mock rules; `null` keeps them in memory.
         * @param captureFilter decides which requests are captured (default: all).
         *   It affects capture only: mock rules and the throttle still apply to a
         *   request it skips. The Network tab shows how many it skipped, with its
         *   description.
         */
        @JvmStatic
        @JvmOverloads
        public fun create(
            senderClient: OkHttpClient? = null,
            classifier: NetworkClassifier = NoOpNetworkClassifier,
            redactor: Redactor = DefaultRedactor,
            mockRuleStorage: MockRuleStorage? = null,
            captureFilter: NetworkCaptureFilter = NoOpNetworkCaptureFilter,
        ): NetworkDebugTab =
            newTab(
                senders = listOfNotNull(senderClient?.let { OkHttpSender(it) }),
                sendersUseConfigBaseUrl = true,
                capturePlatformHttp = false,
                classifier = classifier,
                redactor = redactor,
                mockRuleStorage = mockRuleStorage,
                captureFilter = captureFilter,
            )

        /**
         * Creates a [NetworkDebugTab] whose Send form offers [senders], for
         * example one for each OkHttp client that the app builds with its own
         * interceptors or for its own host. Otherwise the same as the
         * [overload][create] that takes `senderClient`.
         *
         * @param senders the senders the Send form lists, in this order; the
         *   first one is the default, also for `POST send` without a `sender`.
         *   Each resolves relative URLs against its own
         *   [base URL][OkHttpSender.baseUrl]; `DebugConfig.appServerBaseUrl`
         *   does not apply to them. An empty list hides the Send route.
         * @param classifier tags captured URLs with category labels (default: none).
         * @param redactor removes sensitive data at capture time (default:
         *   [DefaultRedactor]).
         * @param mockRuleStorage persists mock rules; `null` keeps them in memory.
         * @param captureFilter decides which requests are captured (default: all).
         *   It affects capture only: mock rules and the throttle still apply to a
         *   request it skips. The Network tab shows how many it skipped, with its
         *   description.
         * @throws IllegalArgumentException when two senders have the same name.
         */
        @JvmStatic
        @JvmOverloads
        public fun create(
            senders: List<OkHttpSender>,
            classifier: NetworkClassifier = NoOpNetworkClassifier,
            redactor: Redactor = DefaultRedactor,
            mockRuleStorage: MockRuleStorage? = null,
            captureFilter: NetworkCaptureFilter = NoOpNetworkCaptureFilter,
        ): NetworkDebugTab =
            newTab(
                senders = senders,
                sendersUseConfigBaseUrl = false,
                capturePlatformHttp = false,
                classifier = classifier,
                redactor = redactor,
                mockRuleStorage = mockRuleStorage,
                captureFilter = captureFilter,
            )

        /**
         * Creates a [NetworkDebugTab], optionally installing the process-global
         * `HttpURLConnection` capture.
         *
         * [capturePlatformHttp] has no default so this overload never collides
         * with the safe [create] above. When `true`, the runtime installs the
         * platform `HttpURLConnection` capture in [onStart] (best-effort,
         * fail-open). This rests on a non-public platform detail, hence the
         * [ExperimentalPlatformCapture] opt-in.
         *
         * @param senderClient when non-null, the Send form sends through this
         *   client, as one sender named "Default" whose relative URLs resolve
         *   against `DebugConfig.appServerBaseUrl`; when `null`, the Send route
         *   is hidden.
         * @param capturePlatformHttp when `true`, installs the process-global
         *   `HttpURLConnection` capture (best-effort, fail-open).
         * @param classifier tags captured URLs with category labels (default: none).
         * @param redactor removes sensitive data at capture time (default:
         *   [DefaultRedactor]).
         * @param mockRuleStorage persists mock rules; `null` keeps them in memory.
         * @param captureFilter decides which requests are captured, OkHttp and
         *   `HttpURLConnection` alike (default: all). It affects capture only:
         *   mock rules and the throttle still apply to an OkHttp request it skips.
         *   The Network tab shows how many it skipped, with its description.
         */
        @ExperimentalPlatformCapture
        @JvmStatic
        @JvmOverloads
        public fun create(
            senderClient: OkHttpClient?,
            capturePlatformHttp: Boolean,
            classifier: NetworkClassifier = NoOpNetworkClassifier,
            redactor: Redactor = DefaultRedactor,
            mockRuleStorage: MockRuleStorage? = null,
            captureFilter: NetworkCaptureFilter = NoOpNetworkCaptureFilter,
        ): NetworkDebugTab =
            newTab(
                senders = listOfNotNull(senderClient?.let { OkHttpSender(it) }),
                sendersUseConfigBaseUrl = true,
                capturePlatformHttp = capturePlatformHttp,
                classifier = classifier,
                redactor = redactor,
                mockRuleStorage = mockRuleStorage,
                captureFilter = captureFilter,
            )

        /**
         * Creates a [NetworkDebugTab] whose Send form offers [senders],
         * optionally installing the process-global `HttpURLConnection` capture.
         * See the [overload][create] that takes `senders` and the one that takes
         * `senderClient` and [capturePlatformHttp].
         *
         * @param senders the senders the Send form lists, in this order; the
         *   first one is the default. An empty list hides the Send route.
         * @param capturePlatformHttp when `true`, installs the process-global
         *   `HttpURLConnection` capture (best-effort, fail-open).
         * @param classifier tags captured URLs with category labels (default: none).
         * @param redactor removes sensitive data at capture time (default:
         *   [DefaultRedactor]).
         * @param mockRuleStorage persists mock rules; `null` keeps them in memory.
         * @param captureFilter decides which requests are captured, OkHttp and
         *   `HttpURLConnection` alike (default: all). It affects capture only:
         *   mock rules and the throttle still apply to an OkHttp request it skips.
         *   The Network tab shows how many it skipped, with its description.
         * @throws IllegalArgumentException when two senders have the same name.
         */
        @ExperimentalPlatformCapture
        @JvmStatic
        @JvmOverloads
        public fun create(
            senders: List<OkHttpSender>,
            capturePlatformHttp: Boolean,
            classifier: NetworkClassifier = NoOpNetworkClassifier,
            redactor: Redactor = DefaultRedactor,
            mockRuleStorage: MockRuleStorage? = null,
            captureFilter: NetworkCaptureFilter = NoOpNetworkCaptureFilter,
        ): NetworkDebugTab =
            newTab(
                senders = senders,
                sendersUseConfigBaseUrl = false,
                capturePlatformHttp = capturePlatformHttp,
                classifier = classifier,
                redactor = redactor,
                mockRuleStorage = mockRuleStorage,
                captureFilter = captureFilter,
            )

        private fun newTab(
            senders: List<OkHttpSender>,
            sendersUseConfigBaseUrl: Boolean,
            capturePlatformHttp: Boolean,
            classifier: NetworkClassifier,
            redactor: Redactor,
            mockRuleStorage: MockRuleStorage?,
            captureFilter: NetworkCaptureFilter,
        ): NetworkDebugTab {
            val duplicate = senders.groupBy { it.name }.entries.firstOrNull { it.value.size > 1 }?.key
            require(duplicate == null) { "Two senders are named \"$duplicate\"; each needs its own name" }
            val store =
                NetworkTrafficStore(
                    maxTransactions = DEFAULT_MAX_TRANSACTIONS,
                    redactor = redactor,
                    classifier = classifier,
                    storage = mockRuleStorage,
                )
            return NetworkDebugTab(
                store = store,
                captureFilter = SafeCaptureFilter(captureFilter),
                senders = senders.toList(),
                sendersUseConfigBaseUrl = sendersUseConfigBaseUrl,
                capturePlatformHttp = capturePlatformHttp,
                maxBodyCaptureBytes = DEFAULT_MAX_BODY_CAPTURE_BYTES,
                redactor = redactor,
                classifier = classifier,
            )
        }

        private const val SEND_CANCEL_GRACE_MS = 1_000L

        private val ABSOLUTE_URL = Regex("^https?://", RegexOption.IGNORE_CASE)

        private const val EXPORT_FORMAT_HAR = "har"

        private const val REQUEST = "request"
        private const val RESPONSE = "response"

        // A body of any type: the captured one, or application/octet-stream.
        private const val ANY_CONTENT = "*/*"

        // Types the console shows in an <img>. Browsers render them without script.
        private val INLINE_BODY_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

        // No subresources and no script, even when a browser renders the body.
        private const val ATTACHMENT_CSP = "default-src 'none'; sandbox"

        // An HTTP token (RFC 9110), as a media type's type and subtype must be.
        private val MEDIA_TOKEN = Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+")

        // The built-in defaults. The configurable DebugConfig values are applied by the
        // runtime when the tab is registered via Lustro.Builder (the proven defaults are
        // kept here so create() works standalone).
        private const val DEFAULT_MAX_TRANSACTIONS = 1000
        private const val DEFAULT_MAX_BODY_CAPTURE_BYTES = 256L * 1024
        private const val DEFAULT_REQUEST_TIMEOUT_MS = 30_000L
    }
}
