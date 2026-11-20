package io.github.twinsen81.lustro

/**
 * The routes of a tab, as [DebugTab.routes] declares them. From the declarations, the tab's
 * default [DebugTab.handle] routes each request, answers `405` for a declared path with
 * another method and `400` with `field` for a missing or wrong parameter, and calls the
 * route's handler. The tab's default [DebugTab.schema] returns an OpenAPI document made from
 * the same declarations, so `GET /api/v1/_meta` lists the tab and the server can name the
 * query parameters that a route does not declare.
 *
 * ```
 * @OptIn(ExperimentalDebugRoutes::class)
 * override fun routes(routes: DebugRoutes) {
 *     routes.version = "1.0"
 *     routes.get("snapshot") {
 *         summary = "Poll the flags with an opaque cursor."
 *         val cursor = query.string("cursor") { description = "The cursor of the previous response." }
 *         handle { call -> snapshot(call[cursor]) }
 *     }
 * }
 * ```
 *
 * A route's path is relative to `/api/v1/<tab id>/`, such as `snapshot` or `items/{id}`: a
 * segment in braces is a path parameter, which the route declares with [DebugRoute.path]. A
 * literal segment wins over a parameter, so `items/new` and `items/{id}` can both exist.
 *
 * A mistake in the declarations, such as two routes with the same method and path, a path
 * parameter that the route does not declare, a JSON fragment that is not valid JSON, or a
 * route without [DebugRoute.handle], throws [IllegalStateException] from the first
 * [DebugTab.handle] or [DebugTab.schema] call. A unit test that calls `tab.schema()` finds it.
 *
 * The API is for Kotlin. Java is not a target: the declaration blocks are Kotlin function
 * types with a receiver.
 */
@ExperimentalDebugRoutes
@RoutesDsl
public class DebugRoutes internal constructor(internal val tab: DebugTab) {
    internal val declared = ArrayList<DebugRoute>()
    internal val components = LinkedHashMap<String, String>()
    internal var open = true

    /** The title of the tab's OpenAPI document. Without it, `"<tab title> debug tab"`. */
    public var title: String? = null

    /**
     * The version of the tab's routes: the `info.version` of the OpenAPI document, which
     * `GET /api/v1/_meta` lists for the tab. Change it when a route changes. Required when the
     * tab declares a route.
     */
    public var version: String? = null

    /** The description of the tab's routes, for the OpenAPI document. */
    public var description: String? = null

    /**
     * Adds the JSON Schema [schema] to the OpenAPI document under `components/schemas/<name>`,
     * so that a response or a parameter can refer to it with [ref]. `ErrorEnvelope`, the
     * shared error envelope, is always there.
     */
    public fun component(name: String, schema: String) {
        checkOpen()
        check(components.put(name, schema) == null) { "Tab ${tab.id}: two components named $name" }
    }

    /** Declares the `GET` route at [path]. */
    public fun get(path: String, declare: DebugRoute.() -> Unit) {
        route("GET", path, declare)
    }

    /** Declares the `POST` route at [path]. */
    public fun post(path: String, declare: DebugRoute.() -> Unit) {
        route("POST", path, declare)
    }

    /** Declares the `PUT` route at [path]. */
    public fun put(path: String, declare: DebugRoute.() -> Unit) {
        route("PUT", path, declare)
    }

    /** Declares the `PATCH` route at [path]. */
    public fun patch(path: String, declare: DebugRoute.() -> Unit) {
        route("PATCH", path, declare)
    }

    /** Declares the `DELETE` route at [path]. */
    public fun delete(path: String, declare: DebugRoute.() -> Unit) {
        route("DELETE", path, declare)
    }

    private fun route(method: String, path: String, declare: DebugRoute.() -> Unit) {
        checkOpen()
        val route = DebugRoute(this, method, path)
        route.declare()
        route.open = false
        declared += route
    }

    internal fun checkOpen() {
        check(open) { "Tab ${tab.id}: the routes are declared only in DebugTab.routes()" }
    }

    /** Helpers for the JSON fragments of a declaration. */
    public companion object {
        /**
         * Returns the JSON Schema `{"$ref": "#/components/schemas/<name>"}`, a reference to a
         * schema that [component] added.
         */
        public fun ref(name: String): String = "{\"\$ref\":\"#/components/schemas/${name.escapeForJson()}\"}"
    }
}

/**
 * One route of a tab: its documentation, its parameters, its body, its response, and the
 * handler that answers it. [DebugRoutes.get] and the other methods of [DebugRoutes] declare it.
 *
 * Each parameter that the block declares returns a [DebugParam], and the handler reads its
 * value from the [DebugCall] with it: `call[cursor]`. The block runs once, when the tab
 * declares its routes; the handler runs for each request.
 */
@ExperimentalDebugRoutes
@RoutesDsl
public class DebugRoute internal constructor(
    internal val routes: DebugRoutes,
    internal val method: String,
    internal val template: String,
) {
    internal val params = ArrayList<ParamDecl>()
    internal var rawBodyType: String? = null
    internal var rawBodySchema: String? = null
    internal var responseDescription: String? = null
    internal var responseSchema: String? = null
    internal val errorStatuses = LinkedHashSet<Int>()
    internal var handler: DebugHandler? = null
    internal var open = true

    /** The OpenAPI `operationId`. Without it, one made from the method and the path, such as `getSnapshot`. */
    public var operationId: String? = null

    /** A short summary of what the route does, for the OpenAPI document. */
    public var summary: String? = null

    /** A longer description of the route, for the OpenAPI document. */
    public var description: String? = null

    /** A description of the request body, for the OpenAPI document. */
    public var bodyDescription: String? = null

    /**
     * Whether the route also takes its [body] fields as an `application/x-www-form-urlencoded`
     * form, as `curl -d id=dark-mode` sends it. Off by default: the route then reads every body
     * as JSON, whatever its content type. A route with a [DebugBodyParams.json] field can't
     * take a form.
     */
    public var acceptsForm: Boolean = false

    /** The parameters in the route's path: one for each segment in braces. */
    public val path: DebugPathParams = DebugPathParams(this)

    /** The query parameters of the route. */
    public val query: DebugParams = DebugParams(this, ParamIn.QUERY)

    /**
     * The fields of the route's request body, a JSON object, or a form when [acceptsForm] is
     * on. When the route declares a required field, an empty body gets a `400` with field `body`.
     */
    public val body: DebugBodyParams = DebugBodyParams(this)

    /**
     * Says that the route reads its whole body itself, as [contentType] (such as
     * `application/json`) that the JSON Schema [schema] describes. The route then gets a `400`
     * with field `body` for an empty body, and the handler reads [DebugRequest.body]. A route
     * can't have both a raw body and [body] fields.
     */
    public fun rawBody(contentType: String, schema: String) {
        checkOpen()
        check(rawBodyType == null) { "${describe()}: two raw bodies" }
        rawBodyType = contentType
        rawBodySchema = schema
    }

    /**
     * Describes the route's `200` response for the OpenAPI document, with the JSON Schema
     * [schema] of its `application/json` body, or none. A route that answers another status
     * or content type, such as `204` or [DebugResponse.text], has no way to say so here: the
     * tab can change the generated document in its [DebugTab.schema].
     */
    public fun response(description: String, schema: String? = null) {
        checkOpen()
        responseDescription = description
        responseSchema = schema
    }

    /**
     * Adds error statuses that the route's handler can answer, such as `404`, to the OpenAPI
     * document. The document already lists `400` for a route with parameters or a body, and
     * the statuses that the server can answer for any route.
     */
    public fun errors(vararg statuses: Int) {
        checkOpen()
        for (status in statuses) {
            check(status in 400..599) { "${describe()}: $status is not an error status" }
            errorStatuses += status
        }
    }

    /**
     * Sets the handler that answers the route. It runs for each request whose parameters are
     * valid, off the main thread and possibly at the same time as other requests, as
     * [DebugTab.handle] does.
     */
    public fun handle(handler: DebugHandler) {
        checkOpen()
        check(this.handler == null) { "${describe()}: two handlers" }
        this.handler = handler
    }

    internal fun checkOpen() {
        check(open) { "${describe()}: declare the route in its block" }
    }

    internal fun describe(): String = "Tab ${routes.tab.id}, $method $template"
}

/** Keeps a declaration block from setting a property of an outer block by mistake. */
@DslMarker
internal annotation class RoutesDsl
