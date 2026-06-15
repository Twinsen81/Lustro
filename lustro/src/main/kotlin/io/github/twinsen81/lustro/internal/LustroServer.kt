@file:Suppress("TooGenericExceptionCaught", "SwallowedException")

package io.github.twinsen81.lustro.internal

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugTab
import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.MediaType
import io.github.twinsen81.lustro.escapeForJson
import io.github.twinsen81.lustro.escapeHtml
import java.io.ByteArrayInputStream
import java.net.URI

/**
 * Internal NanoHTTPD-backed HTTP server. The NanoHTTPD subclass stays fully
 * internal; nothing here leaks into the public surface.
 *
 * Implements the `/api/v1` routing contract:
 * `/`, `/tab/<id>`, `/shared.css`, `/shared.js`, `/api/v1/_meta`,
 * `/api/v1/_schema`, `/api/v1/<id>/_schema`, `/api/v1/<id>/_view[.js|.css]`, and
 * `* /api/v1/<id>/<path...>` → `DebugTab.handle(...)`.
 *
 * Auth — implemented here:
 * - Token auth (Bearer header OR `lustro_token` cookie, constant-time compare).
 * - Auth gating: `GET /`, `GET /tab/<id>` (chrome only — NO `renderContent()`
 *   output, NO tab data), `GET /shared.css`, `GET /shared.js`,
 *   `POST /api/v1/_auth`, and the favicon are UNAUTHENTICATED; every other
 *   `/api/v1/` route requires auth → enveloped 401.
 * - `POST /api/v1/_auth` sets the `lustro_token` cookie on a token match.
 * - CSP + `X-Content-Type-Options: nosniff` attached to all responses.
 * - Origin / `Sec-Fetch-Site` validation on state-changing POST `/api/v1/` requests.
 *
 * Also implemented here:
 * - Bounded dispatch: max request body (413 before allocating), bounded
 *   concurrency + queue (503), per-request timeout (504, interrupts the handler).
 *   The chrome/asset routes are exempt; only the `/api/v1/` surface flows
 *   through the limiter.
 *
 * Lifecycle binding is driven from [io.github.twinsen81.lustro.Lustro]; the
 * [beginDrain]/[shutdownLimiter] hooks below let it stop accepting new requests
 * and wait for in-flight work before closing the socket.
 */
internal class LustroServer(
    hostname: String,
    port: Int,
    private val registry: DebugTabRegistry,
    private val assetLoader: DebugAssetLoader,
    private val tokenStore: LustroTokenStore,
    private val allowedOrigins: List<String>,
    private val appName: String = DEFAULT_APP_NAME,
    private val maxRequestBodyBytes: Long = DEFAULT_MAX_REQUEST_BODY_BYTES,
    maxConcurrentRequests: Int = DEFAULT_MAX_CONCURRENT_REQUESTS,
    requestQueueCapacity: Int = DEFAULT_REQUEST_QUEUE_CAPACITY,
    requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
) : NanoHTTPD(hostname, port) {

    // Bounds concurrency, the wait queue, and the per-request timeout for the
    // debug API. Exposed counters drive the lifecycle drain in Lustro.
    private val limiter =
        RequestLimiter(
            maxConcurrent = maxConcurrentRequests,
            queueCapacity = requestQueueCapacity,
            timeoutMs = requestTimeoutMs,
        )

    /** In-flight `/api/v1/` request count, used by the lifecycle drain. */
    fun inFlightCount(): Int = limiter.activeCount()

    /** Stops admitting new API requests; in-flight work keeps running. */
    fun beginDrain() {
        limiter.beginDrain()
    }

    /** Releases the limiter's worker pool. Call when the server is fully stopped. */
    fun shutdownLimiter() {
        limiter.shutdown()
    }

    override fun serve(session: IHTTPSession): Response =
        try {
            withSecurityHeaders(route(session))
        } catch (e: Exception) {
            // Library exceptions must never escape into the host app.
            Log.w(TAG, "Error serving request: ${session.uri}", e)
            withSecurityHeaders(toNanoResponse(DebugResponse.error("Internal error", status = 500)))
        }

    private fun route(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        return when {
            uri == "/" -> handleRoot()
            uri == "/favicon.ico" -> handleFavicon()
            uri == "/lustro-icon.png" -> serveIcon()
            uri == "/shared.css" -> serveSharedAsset("shared.css", "text/css")
            uri == "/shared.js" -> serveSharedAsset("shared.js", "application/javascript")
            uri.startsWith("/tab/") -> handleTabPage(uri.removePrefix("/tab/").substringBefore('/'))
            // The debug API flows through the bounded dispatcher (concurrency,
            // queue, timeout). Chrome/asset routes above are intentionally exempt.
            uri.startsWith("/api/v1/") -> bounded { routeApi(session, uri) }
            else -> toNanoResponse(DebugResponse.notFound("Not found"))
        }
    }

    /** Routes the authenticated `/api/v1/` surface (called inside the limiter). */
    private fun routeApi(session: IHTTPSession, uri: String): Response {
        // Reject oversize bodies BEFORE reading/allocating them (413). The
        // in-process server has no isolation, so we never buffer an over-cap body.
        oversizeRejection(session)?.let { return it }
        return when {
            uri == "/api/v1/_auth" -> handleAuth(session)
            uri == "/api/v1/_meta" -> requireAuth(session) { handleMeta() }
            uri == "/api/v1/_schema" -> requireAuth(session) { handleSharedSchema() }
            else -> requireAuth(session) { handleApi(session, uri.removePrefix("/api/v1/")) }
        }
    }

    /**
     * Returns an enveloped 413 when the declared `Content-Length` exceeds
     * [maxRequestBodyBytes], or `null` otherwise. Checked before any body bytes
     * are read so an over-cap request never allocates a buffer.
     */
    private fun oversizeRejection(session: IHTTPSession): Response? {
        val declared = session.headers?.get("content-length")?.toLongOrNull() ?: return null
        if (declared <= maxRequestBodyBytes) return null
        return toNanoResponse(
            DebugResponse.error(
                message = "Request body too large",
                status = 413,
                hint = "Maximum request body is $maxRequestBodyBytes bytes",
            ),
        )
    }

    /**
     * Runs [block] through the [RequestLimiter], mapping a saturated
     * concurrency+queue to an enveloped 503 and a per-request timeout to an
     * enveloped 504. A handler that throws propagates out to [serve]'s 500 guard.
     */
    private inline fun bounded(crossinline block: () -> Response): Response =
        when (val outcome = limiter.dispatch { block() }) {
            is RequestLimiter.Outcome.Completed -> outcome.value
            RequestLimiter.Outcome.Rejected ->
                toNanoResponse(
                    DebugResponse.error(
                        message = "Server busy",
                        status = 503,
                        hint = "Too many concurrent debug requests; retry shortly",
                    ),
                )
            RequestLimiter.Outcome.TimedOut ->
                toNanoResponse(DebugResponse.error(message = "Request timed out", status = 504))
        }

    /**
     * Runs [block] only when the request carries a valid Bearer token OR a valid
     * `lustro_token` cookie. Otherwise short-circuits to an enveloped 401.
     */
    private inline fun requireAuth(session: IHTTPSession, block: () -> Response): Response {
        if (!isAuthenticated(session)) {
            return toNanoResponse(
                DebugResponse.error(
                    message = "Authentication required",
                    status = 401,
                    hint = "Send Authorization: Bearer <token> or authenticate via POST /api/v1/_auth",
                ),
            )
        }
        return block()
    }

    /** True when a valid Bearer header OR a valid `lustro_token` cookie is present. */
    private fun isAuthenticated(session: IHTTPSession): Boolean {
        val expected = tokenStore.token()
        bearerToken(session)?.let { if (constantTimeEquals(it, expected)) return true }
        cookieToken(session)?.let { if (constantTimeEquals(it, expected)) return true }
        return false
    }

    private fun bearerToken(session: IHTTPSession): String? {
        val header = session.headers?.get("authorization") ?: return null
        val prefix = "Bearer "
        if (!header.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) return null
        return header.substring(prefix.length).trim().ifEmpty { null }
    }

    private fun cookieToken(session: IHTTPSession): String? {
        val cookieHeader = session.headers?.get("cookie") ?: return null
        for (part in cookieHeader.split(';')) {
            val trimmed = part.trim()
            val eq = trimmed.indexOf('=')
            if (eq <= 0) continue
            if (trimmed.substring(0, eq).trim() == COOKIE_NAME) {
                return trimmed.substring(eq + 1).trim().ifEmpty { null }
            }
        }
        return null
    }

    /**
     * `POST /api/v1/_auth` with body `{"token":"<t>"}`. On a match: 200 plus a
     * `Set-Cookie: lustro_token=<t>; HttpOnly; SameSite=Strict; Path=/`. Else 401.
     * Unauthenticated by itself (it is how a browser becomes authenticated).
     */
    private fun handleAuth(session: IHTTPSession): Response {
        if (session.method != Method.POST) {
            return toNanoResponse(DebugResponse.error("Method not allowed", status = 405))
        }
        // State-changing endpoint: still subject to the origin gate.
        originRejection(session)?.let { return it }
        val body = readRawBody(session)?.toString(Charsets.UTF_8).orEmpty()
        val provided = extractJsonToken(body)
        val expected = tokenStore.token()
        if (provided == null || !constantTimeEquals(provided, expected)) {
            return toNanoResponse(DebugResponse.error("Invalid token", status = 401))
        }
        val ok = toNanoResponse(DebugResponse.ok("{\"ok\":true}"))
        ok.addHeader("Set-Cookie", "$COOKIE_NAME=$expected; HttpOnly; SameSite=Strict; Path=/")
        return ok
    }

    /** Extracts the `token` string from a tiny `{"token":"..."}` JSON body. */
    private fun extractJsonToken(body: String): String? {
        val match = TOKEN_JSON_REGEX.find(body) ?: return null
        val raw = match.groupValues[1]
        // Unescape the handful of JSON escapes that can appear in a Base64-URL token
        // context (defensive; tokens themselves are escape-free).
        return raw
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\/", "/")
            .ifEmpty { null }
    }

    /** Length-independent, branch-stable comparison to avoid timing leaks. */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        val ab = a.toByteArray(Charsets.UTF_8)
        val bb = b.toByteArray(Charsets.UTF_8)
        var diff = ab.size xor bb.size
        val max = maxOf(ab.size, bb.size, 1)
        for (i in 0 until max) {
            val x = if (i < ab.size) ab[i].toInt() else 0
            val y = if (i < bb.size) bb[i].toInt() else 0
            diff = diff or (x xor y)
        }
        return diff == 0
    }

    /**
     * Validates the request's `Origin` / `Sec-Fetch-Site` headers against the
     * same-origin rule + [allowedOrigins]. Returns an enveloped 403 [Response] when
     * the request must be rejected, or `null` when it is allowed (including when
     * the headers are missing — older clients / CLI are accepted).
     *
     * Applied to state-changing (`POST`) `/api/v1/` requests.
     */
    private fun originRejection(session: IHTTPSession): Response? =
        if (isOriginAllowed(session)) {
            null
        } else {
            toNanoResponse(
                DebugResponse.error(
                    message = "Cross-origin request rejected",
                    status = 403,
                ),
            )
        }

    private fun isOriginAllowed(session: IHTTPSession): Boolean {
        val headers = session.headers ?: return true
        val secFetchSite = headers["sec-fetch-site"]
        val origin = headers["origin"]
        // MISSING headers are accepted (older clients / CLI send neither).
        if (secFetchSite == null && origin == null) return true
        // Sec-Fetch-Site same-origin/none is always allowed: the browser vouches
        // that the request did not originate from another site.
        if (secFetchSite == "same-origin" || secFetchSite == "none") return true
        if (origin != null) {
            // ONLY the server's own origin is auto-trusted. A different loopback
            // PORT is a different origin and must NOT be trusted implicitly: cookies
            // are not isolated by port and SameSite treats other localhost ports as
            // same-site, so allowing "any loopback" would let a page on another local
            // port drive this API (CSRF) using the ambient auth cookie.
            if (isOwnOrigin(origin)) return true
            if (allowedOrigins.any { it.equals(origin, ignoreCase = true) }) return true
        }
        // Sec-Fetch-Site present but not same-origin/none, and no acceptable Origin.
        return false
    }

    /**
     * True when [origin] is the server's OWN origin: a loopback host on the actual
     * listening port. The port match is what separates the real console from a
     * different-local-port attacker; the host set tolerates the
     * `localhost` / `127.0.0.1` / `::1` aliasing a browser may use for the same
     * logical origin.
     */
    private fun isOwnOrigin(origin: String): Boolean {
        val uri =
            try {
                URI(origin)
            } catch (_: Exception) {
                return false
            }
        val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.lowercase() ?: return false
        val isLoopbackHost =
            host == "127.0.0.1" || host == "localhost" || host == "::1" || host.startsWith("127.")
        if (!isLoopbackHost) return false
        val port = if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80
        return port == listeningPort
    }

    private fun handleFavicon(): Response {
        return serveIcon()
    }

    private fun serveIcon(): Response {
        val bytes =
            assetLoader.loadBytes(ICON_ASSET)
                ?: return newFixedLengthResponse(Response.Status.NO_CONTENT, "image/png", "")
        return newFixedLengthResponse(
            Response.Status.OK,
            "image/png",
            ByteArrayInputStream(bytes),
            bytes.size.toLong(),
        )
    }

    private fun serveSharedAsset(name: String, mime: String): Response {
        val asset = assetLoader.load(name) ?: return toNanoResponse(DebugResponse.notFound("Asset not found: $name"))
        return newFixedLengthResponse(Response.Status.OK, "$mime; charset=utf-8", asset)
    }

    private fun handleRoot(): Response {
        val defaultTab = registry.defaultTab()
        return if (defaultTab != null) {
            redirectTo("/tab/${defaultTab.id}")
        } else {
            newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", buildNoTabsHtml())
        }
    }

    private fun handleTabPage(tabId: String): Response {
        val tab = registry.findTab(tabId) ?: return toNanoResponse(DebugResponse.notFound("Tab not found: $tabId"))
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", buildPageHtml(tab))
    }

    /**
     * Builds the pre-auth CHROME for a tab page. It deliberately contains NO
     * [DebugTab.renderContent] output and NO tab data — only the tab bar, the
     * connecting/authorizing shell, and the EXTERNAL shared/`_view` script+style
     * tags (which 401 until the browser authenticates via the fragment bootstrap
     * in `shared.js`).
     */
    private fun buildPageHtml(activeTab: DebugTab): String {
        val tabsHtml =
            registry.visibleTabs().joinToString("\n") { tab ->
                val activeClass = if (tab.id == activeTab.id) "active" else ""
                """<a href="/tab/${tab.id}" class="tab $activeClass"><span class="tab-icon">${tab.icon.escapeHtml()}</span>${tab.title.escapeHtml()}</a>"""
            }
        val pageTitle = pageTitle()
        // EXTERNAL scripts/styles only (no inline tab logic — CSP holds). The tab
        // content area starts empty (the "authorizing" shell); shared.js loads the
        // authenticated /_view content once a cookie/token is present.
        return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>$pageTitle</title>
    <link rel="icon" type="image/png" href="/lustro-icon.png">
    <link rel="apple-touch-icon" href="/lustro-icon.png">
    <link rel="stylesheet" href="/shared.css">
    <!-- The tab's own _view.css / _view.js are auth-gated (tab-authored output is
         not served before auth). shared.js injects them dynamically AFTER the auth
         cookie is set; loading them as static tags here would fire the requests
         during HTML parse, before auth, and 401. -->
</head>
<body data-lustro-tab="${activeTab.id.escapeHtml()}">
    <div class="header">
        <div class="tabs">
$tabsHtml
        </div>
        <div class="status-bar">
            <button id="theme-toggle" class="theme-toggle" type="button">◑ Auto</button>
            <span id="status" class="status disconnected">Connecting...</span>
        </div>
    </div>
    <div class="content">
        <div id="lustro-tab-content" data-lustro-content></div>
    </div>
    <script src="/shared.js"></script>
</body>
</html>
        """.trimIndent()
    }

    private fun buildNoTabsHtml(): String =
        """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>${pageTitle()}</title>
    <link rel="icon" type="image/png" href="/lustro-icon.png">
    <link rel="apple-touch-icon" href="/lustro-icon.png">
    <style>
        body {
            font-family: 'Geist Mono', ui-monospace, 'SF Mono', Menlo, monospace;
            background: #08090b;
            color: #7b828d;
            letter-spacing: 0.04em;
            display: flex;
            align-items: center;
            justify-content: center;
            height: 100vh;
            margin: 0;
        }
    </style>
</head>
<body>
    <p>No debug tabs registered.</p>
</body>
</html>
        """.trimIndent()

    private fun pageTitle(): String = "Lustro - ${appName.escapeHtml()}"

    private fun handleMeta(): Response {
        val body = buildString {
            append("{")
            append("\"libraryVersion\":\"").append(Versions.LIBRARY_VERSION.escapeForJson()).append("\",")
            append("\"protocolVersion\":\"").append(Versions.PROTOCOL_VERSION.escapeForJson()).append("\",")
            append("\"tabs\":[")
            // Only tabs that expose a schema (dynamic or static asset) are listed:
            // schema-less tabs work in the UI but are invisible to agents.
            val schemaTabs = registry.sortedTabs.filter { hasSchema(it) }
            schemaTabs.forEachIndexed { index, tab ->
                if (index > 0) append(",")
                append("{")
                append("\"id\":\"").append(tab.id.escapeForJson()).append("\",")
                append("\"title\":\"").append(tab.title.escapeForJson()).append("\",")
                append("\"schemaUrl\":\"").append("/api/v1/${tab.id}/_schema".escapeForJson()).append("\",")
                append("\"version\":\"").append(Versions.PROTOCOL_VERSION.escapeForJson()).append("\"")
                append("}")
            }
            append("]}")
        }
        return toNanoResponse(DebugResponse.ok(body))
    }

    private fun hasSchema(tab: DebugTab): Boolean =
        tab.schema() != null || assetLoader.load("${tab.id}.openapi.json") != null

    private fun handleSharedSchema(): Response {
        val schema = assetLoader.load("_schema.json")
            ?: return toNanoResponse(DebugResponse.notFound("Shared schema not available"))
        return toNanoResponse(DebugResponse.ok(schema))
    }

    private fun handleTabSchema(tab: DebugTab): Response {
        val dynamic = tab.schema()
        if (dynamic != null) return toNanoResponse(DebugResponse.ok(dynamic))
        val static = assetLoader.load("${tab.id}.openapi.json")
        return if (static != null) {
            toNanoResponse(DebugResponse.ok(static))
        } else {
            toNanoResponse(DebugResponse.notFound("No schema for tab: ${tab.id}"))
        }
    }

    private fun handleApi(session: IHTTPSession, remainder: String): Response {
        val parts = remainder.split("/", limit = 2)
        val tabId = parts.getOrNull(0).orEmpty()
        val subPath = parts.getOrNull(1).orEmpty()
        val tab = registry.findTab(tabId) ?: return toNanoResponse(DebugResponse.notFound("Unknown tab: $tabId"))

        return when (subPath) {
            "_schema" -> handleTabSchema(tab)
            "_view" -> serveViewContent(tab)
            "_view.js" -> serveView(tab, isCss = false)
            "_view.css" -> serveView(tab, isCss = true)
            else -> dispatchToTab(session, tab, subPath)
        }
    }

    /** Serves the tab's [DebugTab.renderContent] HTML fragment as text/html. */
    private fun serveViewContent(tab: DebugTab): Response =
        newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", tab.renderContent())

    private fun serveView(tab: DebugTab, isCss: Boolean): Response {
        val rendered = if (isCss) tab.renderStyles() else tab.renderScript()
        val mime = if (isCss) "text/css" else "application/javascript"
        if (rendered.isNotBlank()) {
            return newFixedLengthResponse(Response.Status.OK, "$mime; charset=utf-8", rendered)
        }
        // Fall back to the static asset; empty when neither is present so the
        // <link>/<script> reference resolves without a 404.
        val assetName = "${tab.id}.${if (isCss) "css" else "js"}"
        val asset = assetLoader.load(assetName) ?: ""
        return newFixedLengthResponse(Response.Status.OK, "$mime; charset=utf-8", asset)
    }

    private fun dispatchToTab(session: IHTTPSession, tab: DebugTab, subPath: String): Response {
        // State-changing requests must pass the origin gate.
        if (session.method == Method.POST) {
            originRejection(session)?.let { return it }
        }
        val request = buildDebugRequest(session, subPath)
        val response =
            try {
                tab.handle(request)
            } catch (e: Exception) {
                // Thrown -> enveloped 500, logged WARN. Never escapes.
                Log.w(TAG, "Tab ${tab.id} handle() threw for path '$subPath'", e)
                DebugResponse.error("Internal error", status = 500)
            } ?: DebugResponse.notFound("Not found")
        return toNanoResponse(response)
    }

    private fun buildDebugRequest(session: IHTTPSession, subPath: String): DebugRequest {
        val method = session.method.name
        val queryParams: Map<String, List<String>> =
            session.parameters?.mapValues { (_, v) -> v.toList() } ?: emptyMap()
        val headers = Headers.from(session.headers ?: emptyMap())
        val contentTypeHeader = session.headers?.get("content-type")
        val contentType = contentTypeHeader?.let { MediaType.parse(it) }
        val body = readRawBody(session)
        return DebugRequest(
            path = subPath,
            method = method,
            queryParams = queryParams,
            headers = headers,
            body = body,
            contentType = contentType,
        )
    }

    /**
     * Reads the raw request body bytes. Works around a NanoHTTPD limitation:
     * NanoHTTPD's `parseBody` decodes with US-ASCII when the Content-Type carries
     * no `charset=` (the common case for `application/json` from `fetch`), which
     * mangles multi-byte chars before we ever see them. We read the bytes
     * ourselves so the consumer decodes as UTF-8 via [DebugRequest.bodyAsString].
     *
     * Form-encoded bodies are parsed via NanoHTTPD so their params populate the
     * query map; we still return the raw bytes for those too. Over-cap requests
     * are already rejected with a 413 in [oversizeRejection] before we get here;
     * this cap is a defensive backstop so a lying `Content-Length` cannot OOM.
     */
    private fun readRawBody(session: IHTTPSession): ByteArray? {
        val method = session.method.name
        if (method != "POST" && method != "PUT" && method != "PATCH" && method != "DELETE") {
            return null
        }
        val length = session.headers?.get("content-length")?.toIntOrNull() ?: return null
        if (length <= 0) return null
        val maxBytes = maxRequestBodyBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val capped = minOf(length, maxBytes)
        val buffer = ByteArray(capped)
        var read = 0
        return try {
            val input = session.inputStream
            while (read < capped) {
                val n = input.read(buffer, read, capped - read)
                if (n < 0) break
                read += n
            }
            // Drain any remainder beyond the cap so the socket stream is consumed.
            if (length > capped) {
                val skipBuf = ByteArray(8 * 1024)
                var remaining = length - capped
                while (remaining > 0) {
                    val n = input.read(skipBuf, 0, minOf(skipBuf.size, remaining))
                    if (n < 0) break
                    remaining -= n
                }
            }
            if (read == capped) buffer else buffer.copyOf(read)
        } catch (_: Exception) {
            if (read > 0) buffer.copyOf(read) else null
        }
    }

    private fun redirectTo(location: String): Response {
        val response = newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "")
        response.addHeader("Location", location)
        return response
    }

    private fun toNanoResponse(response: DebugResponse): Response {
        val mime = response.contentType?.toString() ?: JSON_CONTENT_TYPE
        val nano =
            newFixedLengthResponse(
                StatusAdapter(response.status),
                mime,
                ByteArrayInputStream(response.body),
                response.body.size.toLong(),
            )
        response.headers.forEach { name, value -> nano.addHeader(name, value) }
        return nano
    }

    /**
     * Attaches the CSP + `X-Content-Type-Options` security headers to every
     * response. Attaching uniformly is harmless and avoids
     * per-route bookkeeping.
     */
    private fun withSecurityHeaders(response: Response): Response {
        response.addHeader("Content-Security-Policy", CSP)
        response.addHeader("X-Content-Type-Options", "nosniff")
        return response
    }

    /**
     * Adapts an arbitrary HTTP status code to NanoHTTPD's [Response.IStatus].
     * NanoHTTPD's built-in `Status.lookup(int)` returns null for codes it doesn't
     * enumerate, so we wrap any code ourselves.
     */
    private class StatusAdapter(private val code: Int) : Response.IStatus {
        override fun getRequestStatus(): Int = code

        override fun getDescription(): String = "$code ${Response.Status.lookup(code)?.description ?: ""}".trim()
    }

    private companion object {
        private const val TAG = "LustroServer"
        private const val ICON_ASSET = "lustro-icon-transparent.png"
        private const val DEFAULT_APP_NAME = "App"

        // The browser auth cookie name (must match the value expected by shared.js).
        private const val COOKIE_NAME = "lustro_token"

        // The exact CSP header served on the chrome and tab views.
        private const val CSP =
            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
                "connect-src 'self'; img-src 'self' data:; form-action 'self'; " +
                "object-src 'none'; base-uri 'none'; frame-ancestors 'none'"

        // Matches the "token" string value in a small {"token":"..."} JSON body.
        private val TOKEN_JSON_REGEX = Regex("\"token\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

        // Defaults mirroring DebugConfig so direct LustroServer construction (tests,
        // and any non-runtime caller) gets the same bounds the runtime injects.
        private const val DEFAULT_MAX_REQUEST_BODY_BYTES = 1L * 1024 * 1024 // 1 MB
        private const val DEFAULT_MAX_CONCURRENT_REQUESTS = 16
        private const val DEFAULT_REQUEST_QUEUE_CAPACITY = 64
        private const val DEFAULT_REQUEST_TIMEOUT_MS = 30_000L
    }
}
