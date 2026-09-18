package io.github.twinsen81.lustro

/**
 * The single public extension point for adding a tab to the Lustro debug UI.
 *
 * Subclass and register a [DebugTab] to expose JSON routes (via [handle]) and,
 * optionally, an HTML view (via [renderContent], [renderScript], [renderStyles]).
 * API-only tabs are valid: leave the render methods at their empty defaults.
 */
public abstract class DebugTab {
    /**
     * The stable tab id. Must match the regex `[a-z][a-z0-9-]{0,30}`; this is
     * validated at registration. Used in route paths under `/api/v1/<id>/`.
     */
    public abstract val id: String

    /** The human-readable tab title. HTML-escaped by the server before render. */
    public abstract val title: String

    /** The tab icon. HTML-escaped by the server before render. */
    public abstract val icon: String

    /** Sort order; lower comes first. Ties are broken alphabetically by [id]. */
    public open val order: Int = 100

    /**
     * Whether the tab appears in the tab bar. When `false` the tab is headless:
     * still routable and discoverable, but hidden from the tab bar.
     */
    public open val showInTabBar: Boolean = true

    /** Returns the tab's HTML body. Empty by default (API-only tabs). */
    public open fun renderContent(): String = ""

    /**
     * Returns the tab's JavaScript. When non-empty it is served at
     * `/_view.js`; otherwise the static asset is used.
     */
    public open fun renderScript(): String = ""

    /**
     * Returns the tab's CSS. When non-empty it is served at `/_view.css`;
     * otherwise the static asset is used.
     */
    public open fun renderStyles(): String = ""

    /**
     * Returns a dynamic OpenAPI schema document, or `null` to use the static
     * `assets/lustro/<id>.openapi.json`.
     */
    public open fun schema(): String? = null

    /**
     * Handles a debug API [request]. Return `null` to produce an enveloped
     * `404`; throwing anything, including an [Error] such as `TODO()`, produces an
     * enveloped `500`.
     *
     * Invoked OFF the main thread, and calls may be concurrent (the runtime
     * dispatches multiple requests in flight), so a tab holding mutable state
     * must be thread-safe. Blocking on I/O here is fine — the runtime enforces
     * a per-request timeout.
     *
     * When a request times out, the client gets a `504` and the runtime cancels
     * the request: [DebugRequest.isCancelled] turns `true`, the actions
     * registered with [DebugRequest.onCancel] run, and this thread is
     * interrupted. A handler that ignores all three keeps its request's
     * concurrency slot until it returns.
     *
     * The runtime authenticates the request before calling this and does not
     * pass the credentials on: [DebugRequest.headers] never contains
     * `Authorization` or `Cookie`.
     */
    public open fun handle(request: DebugRequest): DebugResponse? = null

    /** Called once when the debug server starts. Anything it throws is logged and ignored. */
    public open fun onStart() {}

    /** Called once when the debug server stops. Anything it throws is logged and ignored. */
    public open fun onStop() {}
}
