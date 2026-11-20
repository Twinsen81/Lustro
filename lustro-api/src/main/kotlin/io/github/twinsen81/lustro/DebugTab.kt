@file:OptIn(ExperimentalDebugRoutes::class)

package io.github.twinsen81.lustro

/**
 * The single public extension point for adding a tab to the Lustro debug UI.
 *
 * Subclass and register a [DebugTab] to expose JSON routes and, optionally, an HTML view
 * (via [renderContent], [renderScript], [renderStyles]). API-only tabs are valid: leave the
 * render methods at their empty defaults.
 *
 * A tab declares its routes in [routes], and gets the routing, the parameter checks, and
 * its OpenAPI document from the declarations. A tab can also route by hand in [handle] and
 * give its own document in [schema].
 */
public abstract class DebugTab {
    private val routesLock = Any()

    @Volatile
    private var routeTable: RouteTable? = null

    @Volatile
    private var routesRead = false

    private var readingRoutes = false
    /**
     * The stable tab id. Must match the regex `[a-z][a-z0-9-]{0,30}`; this is
     * validated at registration. Used in route paths under `/api/v1/<id>/`.
     */
    public abstract val id: String

    /**
     * The human-readable tab title, shown in the tab bar and listed for the tab
     * in `GET /api/v1/_meta`. HTML-escaped by the server before render. A
     * headless tab (see [showInTabBar]) must still set it: `_meta` lists it.
     */
    public abstract val title: String

    /**
     * The tab icon, shown before the [title] in the tab bar. HTML-escaped by the
     * server before render. Nothing shows the icon of a headless tab (see
     * [showInTabBar]), so such a tab can set any value, such as `""`.
     */
    public abstract val icon: String

    /** Sort order; lower comes first. Ties are broken alphabetically by [id]. */
    public open val order: Int = 100

    /**
     * Whether the tab appears in the tab bar. When `false` the tab is headless:
     * its routes work and `_meta` lists it when it has an OpenAPI document, but
     * it has no link in the tab bar and no page: `/tab/<id>` answers `404`, as
     * for an unknown tab.
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
     * Declares the tab's routes on [routes]: each route's method, path, parameters, body,
     * response, and handler. Called once, by the first [handle] or [schema] call, which only
     * the Lustro runtime makes, so a no-op build never builds them. When it declares a route,
     * the default [handle] routes the requests to the handlers, and the default [schema]
     * returns the OpenAPI document of the routes. See [DebugRoutes].
     *
     * The declarations must not depend on state that changes: the first call's result stays.
     * They must not call [handle] or [schema], which throw [IllegalStateException] then.
     */
    @ExperimentalDebugRoutes
    protected open fun routes(routes: DebugRoutes) {}

    /**
     * Returns a dynamic OpenAPI schema document, or `null` to use the static
     * `assets/lustro/<id>.openapi.json`.
     *
     * By default, the document of the routes that [routes] declares, or `null` when it
     * declares none. Override it to give the tab's own document while the declared routes
     * keep the routing, or to change the generated document, which `super.schema()` returns.
     *
     * @throws IllegalStateException when the declarations have a mistake.
     */
    public open fun schema(): String? = declaredRoutes()?.document

    /**
     * Handles a debug API [request]. Return `null` for a route that the tab does
     * not have, also for a method that a route does not take: the client gets an
     * enveloped `404`, or a `405` with an `Allow` header when the tab's OpenAPI
     * document declares the path with other methods. Throwing anything, including
     * an [Error] such as `TODO()`, produces an enveloped `500`. Throwing a
     * [DebugResponseException] produces its response.
     *
     * By default, routes [request] to the routes that [routes] declares: `null` when no
     * declared path matches, `405` with `Allow` for a declared path with another method,
     * `400` with `field` for a missing or wrong parameter, and else the route handler's
     * response. A tab that overrides this to route some paths by hand can call
     * `super.handle(request)` for the declared ones.
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
     *
     * The runtime also rejects browser requests from other origins, whatever the
     * method. For a `GET` or `HEAD`, though, it can go only by `Sec-Fetch-Site`,
     * which browsers send only to loopback and HTTPS addresses, and older ones
     * not at all. So `GET` and `HEAD` must not change state: use `POST`, `PUT`,
     * `PATCH`, or `DELETE` for anything that does.
     */
    public open fun handle(request: DebugRequest): DebugResponse? = declaredRoutes()?.handle(request)

    /** Called once when the debug server starts. Anything it throws is logged and ignored. */
    public open fun onStart() {}

    /** Called once when the debug server stops. Anything it throws is logged and ignored. */
    public open fun onStop() {}

    // Built on first use and never in the constructor, so that a tab that R8 keeps for
    // another reason does not keep the router: only the runtime calls handle() and schema().
    private fun declaredRoutes(): RouteTable? {
        if (routesRead) return routeTable
        synchronized(routesLock) {
            if (!routesRead) {
                check(!readingRoutes) { "Tab $id: routes() must not call handle() or schema()" }
                readingRoutes = true
                try {
                    val declared = DebugRoutes(this)
                    routes(declared)
                    routeTable = RouteTable.build(declared)
                    routesRead = true
                } finally {
                    readingRoutes = false
                }
            }
            return routeTable
        }
    }
}
