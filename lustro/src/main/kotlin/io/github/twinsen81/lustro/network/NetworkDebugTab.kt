@file:Suppress("TooGenericExceptionCaught")

package io.github.twinsen81.lustro.network

import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.ExperimentalPlatformCapture
import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.MediaType
import io.github.twinsen81.lustro.escapeForJson
import io.github.twinsen81.lustro.internal.network.HttpUrlConnectionCapture
import io.github.twinsen81.lustro.internal.network.LustroNetworkInterceptor
import io.github.twinsen81.lustro.internal.network.MockRuleImpl
import io.github.twinsen81.lustro.internal.network.NetworkCaptureProvider
import io.github.twinsen81.lustro.internal.network.NetworkSendRequestImpl
import io.github.twinsen81.lustro.internal.network.NetworkTrafficStore
import io.github.twinsen81.lustro.internal.network.NetworkTransaction
import io.github.twinsen81.lustro.internal.toDebugTimestamp
import io.github.twinsen81.lustro.DebugTab
import java.util.UUID
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug console tab for inspecting captured HTTP traffic and managing mock rules.
 *
 * Two-panel layout: transaction list on the left, detail/mock rules/send on the
 * right. Created through the [companion factory][Companion.create]; the
 * sink/interceptor wiring is internal. Adapted to the `/api/v1/network` wire
 * contract (cursor envelope, synchronous send) and the SPI
 * [NetworkClassifier]/[Redactor]/[MockRuleStorage] seams.
 */
public class NetworkDebugTab private constructor(
    private val store: NetworkTrafficStore,
    private val sender: NetworkSender?,
    private val capturePlatformHttp: Boolean,
    maxBodyCaptureBytes: Long,
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

    // region NetworkCaptureProvider

    override val captureSink: io.github.twinsen81.lustro.network.NetworkCaptureSink
        get() = store

    override fun createInterceptor(captureEnabled: () -> Boolean): Interceptor =
        LustroNetworkInterceptor(
            sink = store,
            captureEnabled = { captureEnabled() && !store.isPaused() },
            throttleDelayMs = { store.getThrottleDelayMs() },
            incrementMockHit = { store.incrementHitCount(it) },
            maxBodySize = maxBodyCaptureBytes,
        )

    // endregion

    /** Records the live bind address so the Send panel can detect self-requests. */
    internal fun bindTo(host: String, port: Int) {
        serverHost = host
        serverPort = port
    }

    /**
     * Internal config-injection seam invoked by `Lustro.Builder.build()` so the
     * tab honours [DebugConfig] instead of its standalone factory defaults:
     * the transaction ring cap, the per-body capture cap, and the base URL used
     * to resolve relative Send Request URLs.
     */
    internal fun applyConfig(
        maxCaptureTransactions: Int,
        maxBodyCaptureBytes: Long,
        appServerBaseUrl: String?,
        captureBudgetBytes: Long,
    ) {
        store.maxTransactions = maxCaptureTransactions
        store.captureBudgetBytes = captureBudgetBytes
        this.maxBodyCaptureBytes = maxBodyCaptureBytes
        this.appServerBaseUrl = appServerBaseUrl
    }

    override fun onStart() {
        // Platform capture installs a process-global handler; it is best-effort and
        // fail-open. Gated by capturePlatformHttp. Built here (after applyConfig)
        // so it observes the configured body cap.
        if (capturePlatformHttp) {
            HttpUrlConnectionCapture(
                sink = store,
                isPaused = { store.isPaused() },
                maxBodySize = maxBodyCaptureBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            ).install()
        }
    }

    override fun renderContent(): String =
        """
        <div class="split-container">
            <div class="pane net-list-pane" id="list-pane">
                <div class="pane-header">
                    <h3>Network Traffic</h3>
                    <span id="tx-count" class="net-tx-count">0 requests</span>
                    <button class="debug-btn" id="pause-btn" data-action="togglePause" style="margin-left:auto" title="Pause traffic capture. The interceptor still runs but new requests are not recorded into the list. Click again to resume.">⏸ Pause</button>
                    <button class="debug-btn" id="overwrite-btn" data-action="toggleOverwriteMode" title="Overwrite mode: when a new request arrives, any earlier completed transaction with the same method + URL path is removed from the list. In-flight requests are never evicted.">Overwrite: off</button>
                    <select class="debug-btn net-throttle-select" id="throttle-select" name="throttleDelayMs" aria-label="Global throttle" data-action="setThrottle" title="Global throttle: sleep this long before every request (mocked or real). Useful for testing loading spinners and timeout handling.">
                        <option value="0">No throttle</option>
                        <option value="500">500ms</option>
                        <option value="1000">1s</option>
                        <option value="3000">3s</option>
                        <option value="5000">5s</option>
                    </select>
                    <button class="debug-btn debug-btn-danger" data-action="clearTraffic" title="Clear the captured transaction list. Mock rules and settings are preserved. Keyboard shortcut: C (when not typing in an input).">Clear</button>
                </div>
                <div class="debug-search-bar">
                    <label class="dc-field" for="search-input"><span class="dc-field__prefix">&gt;</span><input type="text" id="search-input" name="search" class="dc-input" placeholder="filter url, method, body…" data-action="onSearchInput" title="Search across URL, method, request body, and response body (server-side, 300ms debounce). Matches are highlighted in body views. Shortcut: Ctrl/Cmd+K to focus." /></label>
                </div>
                <div class="net-category-bar" id="category-filters"></div>
                <div class="net-filter-bar" id="status-filters"></div>
                <div class="net-filter-bar" id="method-filters"></div>
                <div style="flex:1;overflow-y:auto">
                    <table class="debug-table" style="width:100%">
                        <thead>
                            <tr>
                                <th style="width:74px">Method</th>
                                <th>URL</th>
                                <th style="width:60px">Status</th>
                                <th style="width:66px">Time</th>
                                <th style="width:96px">Cat</th>
                            </tr>
                        </thead>
                        <tbody id="tx-list"></tbody>
                    </table>
                </div>
            </div>
            <div class="pane-divider" data-left="list-pane" data-right="detail-pane">
                <div class="divider-handle"></div>
            </div>
            <div class="pane net-detail-pane" id="detail-pane">
                <div class="pane-header">
                    <div class="net-tab-switcher">
                        <button class="net-tab-btn active" id="tab-btn-detail" data-action="switchRightTab" data-tab="detail" title="Inspect the selected transaction: headers, body, status, timing, byte sizes.">Detail</button>
                        <button class="net-tab-btn" id="tab-btn-rules" data-action="switchRightTab" data-tab="rules" title="Manage mock rules — short-circuit matching requests with a synthetic response. Rules persist across app restarts.">Mock Rules</button>
                        <button class="net-tab-btn" id="tab-btn-send" data-action="switchRightTab" data-tab="send" title="Dispatch an arbitrary request through the app's OkHttpClient. Result appears in the traffic list. Self-requests to the debug server are rejected.">Send Request</button>
                        <div class="net-tab-actions">
                            <button class="net-tab-btn net-action-btn" id="copy-curl-btn" data-action="copyCurl" style="display:none" title="Copy a cURL command that reproduces the selected request (paste into a terminal to re-run).">cURL</button>
                            <button class="net-tab-btn net-action-btn" id="copy-all-btn" data-action="copyAllDetail" style="display:none" title="Copy the full request and response (status, headers, bodies) as plain text for sharing or pasting into a bug report.">Copy</button>
                        </div>
                    </div>
                </div>
                <div id="detail-content" class="net-tab-content active">
                    <div class="net-empty-state">
                        <div class="net-empty-icon">🔍</div>
                        <p>Select a request to inspect</p>
                    </div>
                </div>
                <div id="rules-content" class="net-tab-content">
                    <div id="rules-list"></div>
                    <div id="rule-form-container"></div>
                </div>
                <div id="send-content" class="net-tab-content">
                    <div id="send-form-container"></div>
                </div>
            </div>
        </div>
        """.trimIndent()

    // The static OpenAPI lives at assets/lustro/network.openapi.json (authored by
    // another agent); the tab still works without a dynamic schema.
    override fun schema(): String? = null

    override fun handle(request: DebugRequest): DebugResponse? {
        val path = request.path
        val method = request.method.uppercase()
        val body = request.bodyAsString()
        return when {
            path == "transactions" && method == "GET" -> handleTransactions(request)
            path.startsWith("transactions/") && method == "GET" ->
                handleTransactionDetail(path.removePrefix("transactions/"))
            path == "clear" && method == "POST" -> handleClear()
            path == "rules" && method == "GET" -> handleGetRules()
            path == "rules" && method == "POST" -> handleAddRule(body)
            path == "rules/_/sync" && method == "POST" -> handleSyncRules(body)
            path == "rules/delete" && method == "POST" -> handleDeleteRule(body)
            path == "rules/toggle" && method == "POST" -> handleToggleRule(body)
            path == "pause" && method == "POST" -> handleTogglePause()
            path == "overwrite-mode" && method == "POST" -> handleToggleOverwriteMode()
            path == "throttle" && method == "POST" -> handleSetThrottle(body)
            path == "send" && method == "POST" && sender != null -> handleSendRequest(body)
            else -> null
        }
    }

    // region Transactions + cursor envelope

    private fun handleTransactions(request: DebugRequest): DebugResponse {
        val search = request.queryParam("search")?.takeIf { it.isNotBlank() }
        return DebugResponse.cursorEnvelope(
            currentSequence = store.getSequence(),
            clientCursor = request.queryParam("cursor"),
            state = stateJson(),
        ) {
            val transactions = store.getTransactions(search = search)
            transactions.forEachIndexed { index, tx ->
                if (index > 0) append(",")
                appendTransaction(tx, brief = true)
            }
        }
    }

    private fun stateJson(): String =
        buildString {
            append("{")
            append("\"paused\":").append(store.isPaused()).append(",")
            append("\"overwriteMode\":").append(store.isOverwriteMode()).append(",")
            append("\"throttleDelayMs\":").append(store.getThrottleDelayMs())
            append("}")
        }

    private fun handleTransactionDetail(txId: String): DebugResponse {
        val tx = store.getTransaction(txId) ?: return DebugResponse.notFound("Transaction not found")
        return DebugResponse.json { appendTransaction(tx, brief = false) }
    }

    private fun handleClear(): DebugResponse {
        store.clear()
        return ok()
    }

    // endregion

    // region Mock rules

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
            val json = JSONObject(body ?: "{}")
            val urlPattern = json.optString("urlPattern", "")
            if (urlPattern.isBlank()) {
                DebugResponse.error("urlPattern is required", field = "urlPattern")
            } else {
                val rule =
                    MockRuleImpl(
                        id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = json.optString("name", ""),
                        urlPattern = urlPattern,
                        method = if (json.isNull("method")) null else json.optString("method").ifBlank { null },
                        statusCode = json.optInt("statusCode", 200),
                        responseHeaders = headersFromJson(json.optJSONObject("responseHeaders")),
                        responseBody = json.optString("responseBody", ""),
                        enabled = json.optBoolean("enabled", true),
                    )
                store.addMockRule(rule)
                DebugResponse.json { append("{\"status\":\"ok\",\"id\":\"${rule.id.escapeForJson()}\"}") }
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
                val rule = ruleFromJsonOrNull(obj)
                    ?: return DebugResponse.error("rule at index $i is missing urlPattern", field = "urlPattern")
                rules.add(rule)
            }
            store.replaceMockRules(rules)
            DebugResponse.json { append("{\"status\":\"ok\",\"count\":${rules.size}}") }
        } catch (e: Exception) {
            DebugResponse.error("Invalid JSON: ${e.message}")
        }

    private fun ruleFromJsonOrNull(obj: JSONObject): MockRuleImpl? {
        val pattern = obj.optString("urlPattern")
        if (pattern.isBlank()) return null
        return MockRuleImpl(
            id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = obj.optString("name", ""),
            urlPattern = pattern,
            method = if (obj.isNull("method")) null else obj.optString("method").ifBlank { null },
            statusCode = obj.optInt("statusCode", 200),
            responseHeaders = headersFromJson(obj.optJSONObject("responseHeaders")),
            responseBody = obj.optString("responseBody", ""),
            enabled = obj.optBoolean("enabled", true),
        )
    }

    private fun headersFromJson(obj: JSONObject?): Headers {
        if (obj == null) return Headers.EMPTY
        val builder = Headers.Builder()
        obj.keys().forEach { key -> builder.add(key, obj.optString(key)) }
        return builder.build()
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

    // endregion

    // region State mutations

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

    // endregion

    // region Send (synchronous)

    private fun handleSendRequest(body: String?): DebugResponse {
        val activeSender = sender ?: return DebugResponse.notFound("Send is not configured")
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
            val result = activeSender.send(sendRequest)
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

    // endregion

    // region JSON serialization

    private fun StringBuilder.appendTransaction(tx: NetworkTransaction, brief: Boolean) {
        append("{")
        append("\"id\":\"${tx.id.escapeForJson()}\",")
        append("\"timestamp\":\"${tx.timestamp.toDebugTimestamp()}\",")
        append("\"method\":\"${tx.method.escapeForJson()}\",")
        append("\"url\":\"${tx.url.escapeForJson()}\",")
        append("\"statusCode\":${tx.statusCode ?: "null"},")
        append("\"durationMs\":${tx.durationMs ?: "null"},")
        append("\"categories\":[")
        tx.categories.forEachIndexed { i, cat ->
            if (i > 0) append(",")
            append("\"${cat.escapeForJson()}\"")
        }
        append("],")
        append("\"isMocked\":${tx.isMocked},")
        append("\"requestBodyBytes\":${tx.requestBodyBytes ?: "null"},")
        append("\"responseBodyBytes\":${tx.responseBodyBytes ?: "null"},")
        append("\"responseComplete\":${tx.responseComplete},")
        append("\"error\":${tx.error?.let { "\"${it.escapeForJson()}\"" } ?: "null"}")
        if (!brief) {
            append(",")
            append("\"requestHeaders\":${headersToJson(tx.requestHeaders)},")
            append("\"requestBody\":${tx.requestBody?.let { "\"${it.escapeForJson()}\"" } ?: "null"},")
            append("\"requestBodyTruncated\":${tx.requestBodyTruncated},")
            append("\"responseHeaders\":${tx.responseHeaders?.let { headersToJson(it) } ?: "null"},")
            append("\"responseBody\":${tx.responseBody?.let { "\"${it.escapeForJson()}\"" } ?: "null"},")
            append("\"responseBodyTruncated\":${tx.responseBodyTruncated}")
        }
        append("}")
    }

    private fun StringBuilder.appendMockRule(rule: MockRuleImpl) {
        append("{")
        append("\"id\":\"${rule.id.escapeForJson()}\",")
        append("\"name\":\"${rule.name.escapeForJson()}\",")
        append("\"urlPattern\":\"${rule.urlPattern.escapeForJson()}\",")
        append("\"method\":${rule.method?.let { "\"${it.escapeForJson()}\"" } ?: "null"},")
        append("\"statusCode\":${rule.statusCode},")
        // responseHeaders is stored and applied by the interceptor, so surface it
        // here too (network.js may ignore it for display, but agents/clients can
        // round-trip the full rule).
        append("\"responseHeaders\":${headersToJson(rule.responseHeaders.toMap())},")
        append("\"responseBody\":\"${rule.responseBody.escapeForJson()}\",")
        append("\"enabled\":${rule.enabled},")
        append("\"hitCount\":${rule.hitCount}")
        append("}")
    }

    private fun Headers.toMap(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        forEach { name, value ->
            val existing = out[name]
            out[name] = if (existing != null) "$existing, $value" else value
        }
        return out
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

    // endregion

    /** Factory for [NetworkDebugTab]. */
    public companion object {
        /**
         * Creates a [NetworkDebugTab] that captures only OkHttp traffic (via the
         * interceptor from [Lustro.networkInterceptor]).
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
         */
        @JvmStatic
        @JvmOverloads
        public fun create(
            senderClient: OkHttpClient? = null,
            classifier: NetworkClassifier = NoOpNetworkClassifier,
            redactor: Redactor = DefaultRedactor,
            mockRuleStorage: MockRuleStorage? = null,
        ): NetworkDebugTab =
            newTab(
                senderClient = senderClient,
                capturePlatformHttp = false,
                classifier = classifier,
                redactor = redactor,
                mockRuleStorage = mockRuleStorage,
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
        ): NetworkDebugTab =
            newTab(
                senderClient = senderClient,
                capturePlatformHttp = capturePlatformHttp,
                classifier = classifier,
                redactor = redactor,
                mockRuleStorage = mockRuleStorage,
            )

        private fun newTab(
            senderClient: OkHttpClient?,
            capturePlatformHttp: Boolean,
            classifier: NetworkClassifier,
            redactor: Redactor,
            mockRuleStorage: MockRuleStorage?,
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
                sender = senderClient?.let { OkHttpSender(it) },
                capturePlatformHttp = capturePlatformHttp,
                maxBodyCaptureBytes = DEFAULT_MAX_BODY_CAPTURE_BYTES,
            )
        }

        // The built-in defaults. The configurable DebugConfig values are applied by the
        // runtime when the tab is registered via Lustro.Builder (the proven defaults are
        // kept here so create() works standalone).
        private const val DEFAULT_MAX_TRANSACTIONS = 1000
        private const val DEFAULT_MAX_BODY_CAPTURE_BYTES = 256L * 1024
    }
}
