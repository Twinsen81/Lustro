@file:Suppress("TooGenericExceptionCaught")

package io.github.twinsen81.lustro.network

import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.ExperimentalPlatformCapture
import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.MediaType
import io.github.twinsen81.lustro.escapeForJson
import io.github.twinsen81.lustro.internal.Versions
import io.github.twinsen81.lustro.internal.network.AppCaptureSink
import io.github.twinsen81.lustro.internal.network.CaptureSource
import io.github.twinsen81.lustro.internal.network.CapturingWebSocketFactory
import io.github.twinsen81.lustro.internal.network.HarExport
import io.github.twinsen81.lustro.internal.network.HarWebSocket
import io.github.twinsen81.lustro.internal.network.HttpUrlConnectionCapture
import io.github.twinsen81.lustro.internal.network.LustroNetworkInterceptor
import io.github.twinsen81.lustro.internal.network.MockRuleCodec
import io.github.twinsen81.lustro.internal.network.MockRuleImpl
import io.github.twinsen81.lustro.internal.network.MockRuleParseResult
import io.github.twinsen81.lustro.internal.network.NetworkCaptureProvider
import io.github.twinsen81.lustro.internal.network.NetworkSendRequestImpl
import io.github.twinsen81.lustro.internal.network.NetworkTrafficStore
import io.github.twinsen81.lustro.internal.network.NetworkTransaction
import io.github.twinsen81.lustro.internal.network.SafeCaptureFilter
import io.github.twinsen81.lustro.internal.network.WebSocketCapture
import io.github.twinsen81.lustro.internal.network.WebSocketRoutes
import io.github.twinsen81.lustro.internal.network.WebSocketTrafficStore
import io.github.twinsen81.lustro.internal.toDebugTimestamp
import io.github.twinsen81.lustro.DebugTab
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug console tab for inspecting captured HTTP traffic and WebSocket messages,
 * and managing mock rules.
 *
 * Two-panel layout: the list of transactions or of WebSocket connections on the
 * left, detail/mock rules/send on the right. Created through the
 * [companion factory][Companion.create]; the
 * sink/interceptor wiring is internal. Adapted to the `/api/v1/network` wire
 * contract (cursor envelope, synchronous send) and the SPI
 * [NetworkCaptureFilter]/[NetworkClassifier]/[Redactor]/[MockRuleStorage] seams.
 */
public class NetworkDebugTab private constructor(
    private val store: NetworkTrafficStore,
    private val captureFilter: SafeCaptureFilter,
    private val senderClient: OkHttpClient?,
    private val capturePlatformHttp: Boolean,
    maxBodyCaptureBytes: Long,
    redactor: Redactor,
    classifier: NetworkClassifier,
) : DebugTab(), NetworkCaptureProvider {
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

    // Base for resolving relative "Send Request" URLs; null = relative URLs are
    // rejected. Pushed in via applyConfig from DebugConfig.appServerBaseUrl.
    @Volatile
    private var appServerBaseUrl: String? = null

    // Bounds a Send Request call; pushed in via applyConfig from
    // DebugConfig.requestTimeoutMs.
    @Volatile
    private var requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS

    override val captureSink: io.github.twinsen81.lustro.network.NetworkCaptureSink
        get() = store

    private val webSocketStore = WebSocketTrafficStore()

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

    private val webSocketRoutes = WebSocketRoutes(webSocketStore) { stateJson() }

    // A factory that is already capturing is returned as it is, so wrapping
    // twice doesn't record every message twice.
    override fun wrapWebSocketFactory(delegate: WebSocket.Factory): WebSocket.Factory =
        delegate as? CapturingWebSocketFactory ?: CapturingWebSocketFactory(delegate, webSocketCapture)

    override fun createInterceptor(captureEnabled: () -> Boolean): Interceptor =
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

    override fun createAppCaptureSink(captureEnabled: () -> Boolean): NetworkCaptureSink =
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
     * to resolve relative Send Request URLs, and the per-request timeout that
     * bounds a Send Request call.
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
    ) {
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
        // so it observes the configured body cap.
        if (capturePlatformHttp) {
            HttpUrlConnectionCapture(
                sink = store.sinkFor(CaptureSource.PLATFORM),
                isPaused = { store.isPaused() },
                captureFilter = captureFilter,
                maxBodySize = maxBodyCaptureBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            ).install()
        }
    }

    override fun renderContent(): String =
        """
        <div class="dc-split" id="net-root" data-view="http">
            <div class="dc-pane net-list-pane">
                <div class="dc-toolbar net-list-head">
                    <h3 class="dc-mono-label net-list-title">Network Traffic</h3>
                    <div class="dc-seg dc-seg--sm net-view-seg" role="group" aria-label="Kind of traffic">
                        <button class="dc-seg__item dc-seg__item--active" data-action="switchTrafficView" data-view="http" title="HTTP requests and responses.">HTTP</button>
                        <button class="dc-seg__item" data-action="switchTrafficView" data-view="ws" title="WebSocket connections and the messages of each one. A socket is listed when the app creates it with the factory from Lustro.webSocketFactory.">WebSockets<span id="ws-count" class="net-ws-count"></span></button>
                    </div>
                    <span id="tx-count" class="net-tx-count">0 requests</span>
                    <button class="dc-btn dc-btn--icon net-filter-flag" id="capture-filter-btn" data-action="showCaptureFilter" hidden aria-label="Requests skipped by the app filters">⚠</button>
                    <div class="net-list-actions">
                    <button class="dc-btn net-http-only" id="select-btn" data-action="toggleSelectMode" title="Select requests to export as a HAR file or copy as Markdown. A filter change keeps only the selected requests it still shows.">Select</button>
                    <button class="dc-btn" id="pause-btn" data-action="togglePause" title="Pause traffic capture. The interceptor still runs but new requests and WebSocket messages are not recorded. Click again to resume.">⏸ Pause</button>
                    <button class="dc-btn net-http-only" id="overwrite-btn" data-action="toggleOverwriteMode" title="Overwrite mode: when a new request arrives, any earlier completed transaction with the same method + URL path is removed from the list. In-flight requests are never evicted.">Overwrite: off</button>
                    <select class="dc-btn net-http-only" id="throttle-select" name="throttleDelayMs" aria-label="Global throttle" data-action="setThrottle" title="Global throttle: sleep this long before every OkHttp request, mocked or real. A platform HttpURLConnection request, or one that the app's own adapter reports, is not held. Useful for testing loading spinners and timeout handling.">
                        <option value="0">No throttle</option>
                        <option value="500">500ms</option>
                        <option value="1000">1s</option>
                        <option value="3000">3s</option>
                        <option value="5000">5s</option>
                    </select>
                    <button class="dc-btn dc-btn--ghost-danger" data-action="clearTraffic" title="Clear the captured requests and WebSocket connections. Mock rules and settings are preserved. Keyboard shortcut: C (when not typing in an input).">Clear</button>
                    </div>
                </div>
                <div class="net-search net-http-only">
                    <label class="dc-field" for="search-input"><span class="dc-field__prefix" aria-hidden="true">&gt;</span><input type="text" id="search-input" name="search" aria-label="Search network traffic" class="dc-input" placeholder="filter url, method, body…" data-action="onSearchInput" title="Search across URL, method, request body, and response body (server-side, 300ms debounce). Matches are highlighted in body views. Shortcut: Ctrl/Cmd+K to focus." /></label>
                </div>
                <div class="net-category-bar net-http-only" id="category-filters"></div>
                <div class="net-filter-bar net-http-only" id="status-filters"></div>
                <div class="net-filter-bar net-http-only" id="method-filters"></div>
                <div class="net-select-bar net-http-only" id="select-bar" hidden>
                    <span id="select-count" class="net-select-count">0 selected</span>
                    <button class="dc-btn dc-btn--sm" id="copy-selection-md-btn" data-action="copySelectionMarkdown" disabled title="Copy the selected requests and their responses as one Markdown document, for a bug report, a pull request, or a chat.">Copy Markdown</button>
                    <button class="dc-btn dc-btn--sm dc-btn--primary" id="export-har-btn" data-action="exportSelectionHar" disabled title="Save the selected requests as a HAR file, which browser devtools and HTTP tools import. Headers and bodies are redacted, as shown here.">Export HAR</button>
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
                                <th class="dc-th net-select-col" id="select-col" style="display:none" data-action="toggleSelectAll" title="Select every request the filters show, or none."><input type="checkbox" id="select-all" class="net-check" aria-label="Select every request the filters show"></th>
                                <th class="dc-th" style="width:74px">Method</th>
                                <th class="dc-th">URL</th>
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
                <div class="dc-tabs">
                    <button class="dc-tab dc-tab--active" id="tab-btn-detail" data-action="switchRightTab" data-tab="detail" title="Inspect the selected transaction: headers, body, status, timing, byte sizes.">Detail</button>
                    <button class="dc-tab" id="tab-btn-rules" data-action="switchRightTab" data-tab="rules" title="Manage mock rules — short-circuit matching requests with a synthetic response. Rules live in the app and survive a restart when it gives Lustro a rule storage.">Mock Rules</button>
                    <button class="dc-tab" id="tab-btn-send" data-action="switchRightTab" data-tab="send" title="Dispatch an arbitrary request through the app's OkHttpClient. The result appears in the traffic list only when that client carries the Lustro interceptor. Self-requests to the debug server are rejected.">Send Request</button>
                    <div class="net-tab-actions">
                        <button class="dc-btn dc-btn--sm" id="copy-curl-btn" data-action="copyCurl" style="display:none" title="Copy a cURL command that reproduces the selected request (paste into a terminal to re-run).">cURL</button>
                        <button class="dc-btn dc-btn--sm" id="copy-md-btn" data-action="copyMarkdown" style="display:none" title="Copy the request and response as Markdown, for a bug report, a pull request, or a chat. Headers and bodies go in code blocks.">Markdown</button>
                        <button class="dc-btn dc-btn--sm" id="copy-all-btn" data-action="copyAllDetail" style="display:none" title="Copy the full request and response (status, headers, bodies) as plain text for sharing or pasting into a bug report.">Copy</button>
                    </div>
                </div>
                <div id="detail-content" class="dc-tabpanel net-tab-content active">
                    <div class="net-empty-state">
                        <div class="net-empty-icon">🔍</div>
                        <p>Select a request to inspect</p>
                    </div>
                </div>
                <div id="rules-content" class="dc-tabpanel net-tab-content">
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
        """.trimIndent()

    // The static OpenAPI lives at assets/lustro/network.openapi.json; the tab
    // still works without a dynamic schema.
    override fun schema(): String? = null

    override fun handle(request: DebugRequest): DebugResponse? {
        val path = request.path
        val method = request.method.uppercase()
        val body = request.bodyAsString()
        return when {
            path == "transactions" && method == "GET" -> handleTransactions(request)
            path == "transactions/_/export" && method == "GET" -> handleExport(request)
            path.startsWith("transactions/") && method == "GET" ->
                handleTransactionPath(path.removePrefix("transactions/").split('/'))
            path == "websockets" || path.startsWith("websockets/") -> webSocketRoutes.handle(request)
            path == "clear" && method == "POST" -> handleClear()
            path == "rules" && method == "GET" -> handleGetRules()
            path == "rules" && method == "POST" -> handleAddRule(body)
            path == "rules/_/sync" && method == "POST" -> handleSyncRules(body)
            path == "rules/delete" && method == "POST" -> handleDeleteRule(body)
            path == "rules/toggle" && method == "POST" -> handleToggleRule(body)
            path == "pause" && method == "POST" -> handleTogglePause()
            path == "overwrite-mode" && method == "POST" -> handleToggleOverwriteMode()
            path == "throttle" && method == "POST" -> handleSetThrottle(body)
            path == "send" && method == "POST" && senderClient != null -> handleSendRequest(body)
            else -> null
        }
    }

    private fun handleTransactions(request: DebugRequest): DebugResponse {
        val search = request.queryParam("search")?.takeIf { it.isNotBlank() }
        return DebugResponse.cursorEnvelope(
            // A handshake's webSocketId depends on which connections are listed, so
            // a change to that set is a change to this list. Both counts only grow.
            currentSequence = store.getSequence() + webSocketStore.getMembershipSequence(),
            clientCursor = request.queryParam("cursor"),
            state = stateJson(),
            epoch = store.epoch,
        ) {
            val transactions = store.getTransactions(search = search)
            val webSockets = webSocketStore.transactionLinks()
            transactions.forEachIndexed { index, tx ->
                if (index > 0) append(",")
                appendTransaction(tx, brief = true, webSocketId = webSockets[tx.id])
            }
        }
    }

    private fun stateJson(): String =
        buildString {
            append("{")
            append("\"paused\":").append(store.isPaused()).append(",")
            append("\"overwriteMode\":").append(store.isOverwriteMode()).append(",")
            append("\"throttleDelayMs\":").append(store.getThrottleDelayMs()).append(",")
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

    // transactions/{id} and transactions/{id}/body/{request|response}.
    private fun handleTransactionPath(segments: List<String>): DebugResponse? =
        when {
            segments.size == 1 -> handleTransactionDetail(segments[0])
            segments.size == 3 && segments[1] == "body" && segments[2] == "request" ->
                handleTransactionBody(segments[0], request = true)
            segments.size == 3 && segments[1] == "body" && segments[2] == "response" ->
                handleTransactionBody(segments[0], request = false)
            else -> null
        }

    private fun handleTransactionDetail(txId: String): DebugResponse {
        val tx = store.getTransaction(txId) ?: return DebugResponse.notFound("Transaction not found")
        return DebugResponse.json { appendTransaction(tx, brief = false, webSocketId = webSocketStore.transactionLinks()[tx.id]) }
    }

    /**
     * Serves a body as it is stored: the bytes of a binary body, or the redacted
     * text of a text body as UTF-8. Only the raster image types the console
     * shows are served inline. Every other type, SVG and HTML included, is an
     * attachment with a sandboxing CSP, so a captured page can never run script
     * under the console's origin.
     */
    private fun handleTransactionBody(txId: String, request: Boolean): DebugResponse {
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
     * The transactions named by `ids`, or every one when it is left out, as a HAR
     * document, oldest first. An id the store no longer has is left out, so a
     * selection taken just before an eviction still exports what remains.
     */
    private fun handleExport(request: DebugRequest): DebugResponse {
        val format = request.queryParam("format") ?: EXPORT_FORMAT_HAR
        if (!format.equals(EXPORT_FORMAT_HAR, ignoreCase = true)) {
            return DebugResponse.error("Unknown export format '$format'; the only one is har", field = "format")
        }
        // Repeated ids parameters and comma-separated lists both work. An ids
        // parameter that names none is an empty selection, not every transaction.
        val ids = request.queryParams["ids"]?.flatMap { it.split(',') }?.map { it.trim() }?.filter { it.isNotEmpty() }
        val transactions =
            if (ids == null) store.getTransactions() else ids.distinct().mapNotNull { store.getTransaction(it) }
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
    private fun handleClear(): DebugResponse {
        store.clear()
        webSocketStore.clear()
        captureFilter.resetCounts()
        return ok()
    }

    private fun handleGetRules(): DebugResponse =
        DebugResponse.json {
            append("{\"items\":[")
            val rules = store.getMockRules()
            rules.forEachIndexed { index, rule ->
                if (index > 0) append(",")
                appendMockRule(rule)
            }
            append("]}")
        }

    private fun handleAddRule(body: String?): DebugResponse =
        try {
            when (val parsed = MockRuleCodec.parse(JSONObject(body ?: "{}"), generateMissingId = true)) {
                is MockRuleParseResult.Invalid ->
                    DebugResponse.error(parsed.message, field = parsed.field)

                is MockRuleParseResult.Valid -> {
                    store.addMockRule(parsed.rule)
                    DebugResponse.json {
                        append("{\"status\":\"ok\",\"id\":\"${parsed.rule.id.escapeForJson()}\"}")
                    }
                }
            }
        } catch (e: Exception) {
            DebugResponse.error("Invalid JSON: ${e.message}")
        }

    private fun handleSyncRules(body: String?): DebugResponse =
        try {
            val arr = JSONArray(body ?: "[]")
            val rules = ArrayList<MockRuleImpl>(arr.length())
            for (i in 0 until arr.length()) {
                // Declarative sync is all-or-nothing: the OpenAPI promises the
                // resulting set equals the posted array, so a malformed entry must
                // reject the whole batch with a 400 BEFORE the store is touched —
                // never silently drop it — mirroring the single-rule add validation.
                val obj = arr.optJSONObject(i)
                    ?: return DebugResponse.error("rule at index $i is not a JSON object")
                when (val parsed = MockRuleCodec.parse(obj, generateMissingId = true)) {
                    is MockRuleParseResult.Invalid ->
                        return DebugResponse.error(
                            "rule at index $i: ${parsed.message}",
                            field = parsed.field,
                        )

                    is MockRuleParseResult.Valid -> rules.add(parsed.rule)
                }
            }
            store.replaceMockRules(rules)
            DebugResponse.json { append("{\"status\":\"ok\",\"count\":${rules.size}}") }
        } catch (e: Exception) {
            DebugResponse.error("Invalid JSON: ${e.message}")
        }

    private fun handleDeleteRule(body: String?): DebugResponse =
        try {
            val json = JSONObject(body ?: "{}")
            val ruleId = json.optString("id")
            if (ruleId.isBlank()) {
                DebugResponse.error("id is required", field = "id")
            } else {
                store.removeMockRule(ruleId)
                ok()
            }
        } catch (e: Exception) {
            DebugResponse.error("Invalid JSON: ${e.message}")
        }

    private fun handleToggleRule(body: String?): DebugResponse =
        try {
            val json = JSONObject(body ?: "{}")
            val ruleId = json.optString("id")
            if (ruleId.isBlank()) {
                DebugResponse.error("id is required", field = "id")
            } else {
                store.toggleMockRule(ruleId)
                ok()
            }
        } catch (e: Exception) {
            DebugResponse.error("Invalid JSON: ${e.message}")
        }

    private fun handleTogglePause(): DebugResponse {
        val newState = !store.isPaused()
        store.setPaused(newState)
        return DebugResponse.json { append("{\"status\":\"ok\",\"paused\":$newState}") }
    }

    private fun handleToggleOverwriteMode(): DebugResponse {
        val newState = !store.isOverwriteMode()
        store.setOverwriteMode(newState)
        return DebugResponse.json { append("{\"status\":\"ok\",\"overwriteMode\":$newState}") }
    }

    private fun handleSetThrottle(body: String?): DebugResponse =
        try {
            val json = JSONObject(body ?: "{}")
            val delayMs = json.optInt("delayMs", 0).coerceAtLeast(0)
            store.setThrottleDelayMs(delayMs)
            DebugResponse.json { append("{\"status\":\"ok\",\"delayMs\":$delayMs}") }
        } catch (e: Exception) {
            DebugResponse.error("Invalid JSON: ${e.message}")
        }

    private fun handleSendRequest(body: String?): DebugResponse {
        val client = senderClient ?: return DebugResponse.notFound("Send is not configured")
        return try {
            val json = JSONObject(body ?: "{}")
            val rawUrl = json.optString("url").trim()
            val method = json.optString("method", "GET").uppercase()
            if (rawUrl.isBlank()) {
                return DebugResponse.error("url is required", field = "url")
            }
            // Resolve relative URLs against the configured app server base URL
            // by trimming any trailing slash and appending the relative path.
            val resolvedUrl =
                if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
                    rawUrl
                } else {
                    val base = appServerBaseUrl
                        ?: return DebugResponse.error(
                            "url must be absolute (no app server base configured for relative URLs)",
                            field = "url",
                        )
                    base.trimEnd('/') + "/" + rawUrl.trimStart('/')
                }
            if (isSelfRequest(resolvedUrl)) {
                return DebugResponse.error("Refusing to send to the debug server itself")
            }
            val headersBuilder = Headers.Builder()
            var contentType: MediaType? = null
            val headersJson = json.optJSONObject("headers")
            if (headersJson != null) {
                val keys = headersJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = headersJson.optString(key)
                    // Carry Content-Type via the body's media type instead of a raw
                    // header so it isn't duplicated.
                    if (key.equals("Content-Type", ignoreCase = true)) {
                        contentType = MediaType.parse(value)
                    } else {
                        headersBuilder.add(key, value)
                    }
                }
            }
            val bodyText = json.optString("body")
            val requestBody = bodyText.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
            val sendRequest =
                NetworkSendRequestImpl(
                    url = resolvedUrl,
                    method = method,
                    headers = headersBuilder.build(),
                    body = requestBody,
                    contentType = contentType ?: MediaType.JSON,
                )
            // SYNCHRONOUS: block for the sender result. The runtime calls handle()
            // off the main thread, so blocking here is safe.
            val result = newSender(client).send(sendRequest)
            DebugResponse.json {
                append("{")
                // transactionId is null: the synchronous send path does not correlate
                // the dispatched call to a captured transaction. The replay is recorded
                // as its own row only when the configured sender client carries the
                // Lustro interceptor; either way the outcome is surfaced inline below.
                append("\"transactionId\":null,")
                append("\"statusCode\":").append(result.statusCode).append(",")
                append("\"ok\":").append(result.isSuccess)
                if (!result.isSuccess) {
                    append(",\"error\":\"").append((result.errorMessage ?: "send failed").escapeForJson()).append("\"")
                }
                append("}")
            }
        } catch (e: Exception) {
            DebugResponse.error("Failed to send: ${e.message}")
        }
    }

    // Built per send so it observes the current config. Only the status and
    // outcome are reported, so the response read is capped at the capture cap.
    // The call timeout runs a moment past the per-request timeout: the server
    // still answers 504 first, then OkHttp cancels the call so a slow or stalled
    // response cannot keep the worker thread and socket busy.
    private fun newSender(client: OkHttpClient): OkHttpSender =
        OkHttpSender(
            client = client,
            maxResponseBodyBytes = maxBodyCaptureBytes,
            callTimeoutMs = requestTimeoutMs + SEND_CANCEL_GRACE_MS,
        )

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

    private fun StringBuilder.appendTransaction(tx: NetworkTransaction, brief: Boolean, webSocketId: String?) {
        append("{")
        append("\"id\":\"${tx.id.escapeForJson()}\",")
        append("\"timestamp\":\"${tx.startedAt.toDebugTimestamp()}\",")
        append("\"startedAt\":${tx.startedAt},")
        append("\"completedAt\":${tx.completedAt ?: "null"},")
        append("\"method\":\"${tx.method.escapeForJson()}\",")
        append("\"url\":\"${tx.url.escapeForJson()}\",")
        append("\"finalUrl\":${tx.finalUrl.toJsonString()},")
        append("\"protocol\":${tx.protocol.toJsonString()},")
        append("\"statusCode\":${tx.statusCode ?: "null"},")
        append("\"durationMs\":${tx.durationMs ?: "null"},")
        append("\"throttledMs\":${tx.throttledMs ?: "null"},")
        append("\"categories\":[")
        tx.categories.forEachIndexed { i, cat ->
            if (i > 0) append(",")
            append("\"${cat.escapeForJson()}\"")
        }
        append("],")
        append("\"isMocked\":${tx.isMocked},")
        append("\"source\":\"${tx.source.wireName}\",")
        append("\"requestContentType\":${tx.requestContentType.toJsonString()},")
        append("\"responseContentType\":${tx.responseContentType.toJsonString()},")
        append("\"requestBodyBytes\":${tx.requestBodyBytes ?: "null"},")
        append("\"responseBodyBytes\":${tx.responseBodyBytes ?: "null"},")
        append("\"responseComplete\":${tx.responseComplete},")
        // Set on the handshake of a socket from Lustro.webSocketFactory: its messages are under websockets/{id}.
        append("\"webSocketId\":${webSocketId.toJsonString()},")
        append("\"error\":${tx.error.toJsonString()}")
        if (!brief) {
            append(",")
            append("\"requestHeaders\":${headersToJson(tx.requestHeaders)},")
            append("\"requestBody\":${tx.requestBody.toJsonString()},")
            append("\"requestBodyTruncated\":${tx.requestBodyTruncated},")
            append("\"requestBodyBinary\":${tx.requestBinaryBody != null},")
            append("\"responseHeaders\":${tx.responseHeaders?.let { headersToJson(it) } ?: "null"},")
            append("\"responseBody\":${tx.responseBody.toJsonString()},")
            append("\"responseBodyTruncated\":${tx.responseBodyTruncated},")
            append("\"responseBodyBinary\":${tx.responseBinaryBody != null},")
            append("\"priorResponses\":[")
            tx.priorResponses.forEachIndexed { i, prior ->
                if (i > 0) append(",")
                append("{\"url\":\"${prior.url.escapeForJson()}\",\"statusCode\":${prior.statusCode}}")
            }
            append("]")
        }
        append("}")
    }

    private fun String?.toJsonString(): String = this?.let { "\"${it.escapeForJson()}\"" } ?: "null"

    // responseHeaders go out with the rule (the browser form doesn't show them,
    // but agents and the CLI round-trip the whole rule through this route).
    private fun StringBuilder.appendMockRule(rule: MockRuleImpl) {
        MockRuleCodec.appendJson(this, rule, includeHitCount = true)
    }

    private fun headersToJson(headers: Map<String, String>): String =
        buildString {
            append("{")
            headers.entries.forEachIndexed { index, (key, value) ->
                if (index > 0) append(",")
                append("\"${key.escapeForJson()}\":\"${value.escapeForJson()}\"")
            }
            append("}")
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
         * @param senderClient when non-null, wrapped in an [OkHttpSender] to power
         *   the "Send Request" panel; when `null`, the Send route is hidden.
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
                senderClient = senderClient,
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
         * @param senderClient when non-null, wrapped in an [OkHttpSender] to power
         *   the "Send Request" panel; when `null`, the Send route is hidden.
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
                senderClient = senderClient,
                capturePlatformHttp = capturePlatformHttp,
                classifier = classifier,
                redactor = redactor,
                mockRuleStorage = mockRuleStorage,
                captureFilter = captureFilter,
            )

        private fun newTab(
            senderClient: OkHttpClient?,
            capturePlatformHttp: Boolean,
            classifier: NetworkClassifier,
            redactor: Redactor,
            mockRuleStorage: MockRuleStorage?,
            captureFilter: NetworkCaptureFilter,
        ): NetworkDebugTab {
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
                senderClient = senderClient,
                capturePlatformHttp = capturePlatformHttp,
                maxBodyCaptureBytes = DEFAULT_MAX_BODY_CAPTURE_BYTES,
                redactor = redactor,
                classifier = classifier,
            )
        }

        private const val SEND_CANCEL_GRACE_MS = 1_000L

        private const val EXPORT_FORMAT_HAR = "har"

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
