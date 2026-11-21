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
 * literal segment wins over a parameter, so `items/new` and `items/{id}` can both exist. For
 * the route's own method, a segment that its parameter does not take, such as `items/x` for an
 * int `{id}`, matches no route, so the client gets `404`. The router checks the method before
 * the path parameters, so another method gets `405` with an `Allow` header: with only
 * `DELETE items/{id}`, `GET items/x` gets `Allow: DELETE`. The path `""` declares the route at the tab root,
 * `/api/v1/<tab id>`, such as the list and the create of a collection. The router ignores one
 * slash at the end of a request's path, so `seed/` reaches the route `seed`.
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
    internal var response: DebugResponseSpec? = null
    internal val errorStatuses = LinkedHashSet<Int>()
    internal var handler: DebugHandler? = null
    internal var open = true

    /**
     * The OpenAPI `operationId`. Without it, one made from the method and the path, such as
     * `getSnapshot`, or `getRoot` for the route at the tab root.
     */
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
     * Describes the route's success response for the OpenAPI document: its [description], and
     * the JSON Schema [schema] of its `application/json` body, or none. [declare] adds a body of
     * another content type, such as the bytes of [DebugResponse.bytes], and the response's
     * headers:
     *
     * ```
     * response("The image, as it is stored.") {
     *     content("image/png")
     *     header("Content-Disposition", "attachment, for a download.")
     * }
     * ```
     *
     * The response is `200` unless [DebugResponseSpec.status] names another success status,
     * such as `response("The new item.", schema) { status = 201 }`. The handler still answers
     * it, such as with `DebugResponse.ok(json, status = 201)`. Nothing compares the declared
     * status with the status that the handler answers, so use one constant for both, and check
     * the status in a unit test that calls `tab.handle()`.
     */
    public fun response(description: String, schema: String? = null, declare: DebugResponseSpec.() -> Unit = {}) {
        checkOpen()
        check(response == null) { "${describe()}: two responses" }
        val spec = DebugResponseSpec(this, description)
        if (schema != null) spec.content(JSON_CONTENT_TYPE, schema)
        spec.declare()
        spec.open = false
        check(spec.contents.isEmpty() || spec.status !in NO_BODY_STATUSES) {
            "${describe()}: a ${spec.status} response has no body, so it has no schema and no content"
        }
        response = spec
    }

    /**
     * Adds error statuses that the route's handler can answer, such as `404`, to the OpenAPI
     * document. The document already lists the statuses that the server can answer for any
     * route, `400` for a route with a body or a query parameter that the router can refuse, and
     * `404` for a path parameter that does not take every segment.
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

    internal fun describe(): String = "Tab ${routes.tab.id}, $method $shownTemplate"

    /** The path as the tab wrote it, with `""` for the tab root, which a message can't show as nothing. */
    internal val shownTemplate: String get() = template.ifEmpty { "\"\"" }
}

/**
 * The success response of a route, for the OpenAPI document: [DebugRoute.response] declares it.
 * Its bodies, one for each content type, and its headers are in the order of the declarations.
 */
@ExperimentalDebugRoutes
@RoutesDsl
public class DebugResponseSpec internal constructor(
    private val route: DebugRoute,
    internal val description: String,
) {
    internal val contents = LinkedHashMap<String, String>()
    internal val headers = LinkedHashMap<String, Header>()
    internal var open = true

    /**
     * The status of the response, `200` by default: a success status from `200` to `299`, such
     * as `201` for a route that creates an item. The document lists the response under it. A
     * `204` or `205` response has no body, so it declares no schema and no [content], and its
     * handler answers with an empty body, such as `DebugResponse.ok("", status = 204)`: a client
     * such as OkHttp refuses a `204` with a body.
     */
    public var status: Int = 200
        set(value) {
            checkOpen()
            check(value in 200..299) { "${route.describe()}: $value is not a success status" }
            field = value
        }

    /**
     * Adds a body of [contentType] that the JSON Schema [schema] describes. [contentType] is a
     * media type, such as `text/csv`, or a media range with `*` as the subtype, for any image,
     * or as both parts, for a body of any type. The default [schema], `{}`, takes any body.
     */
    public fun content(contentType: String, schema: String = "{}") {
        checkOpen()
        check(MEDIA_RANGE.matches(contentType)) { "${route.describe()}: $contentType is not a content type, such as image/png" }
        check(contents.keys.none { it.equals(contentType, ignoreCase = true) }) { "${route.describe()}: two responses of $contentType" }
        contents[contentType] = schema
    }

    /**
     * Adds the response header [name], with its [description] and the JSON Schema [schema] of its
     * value, a string by default. `Content-Type` is not a header here: [content] declares it.
     */
    public fun header(name: String, description: String, schema: String = STRING_SCHEMA) {
        checkOpen()
        check(HEADER_NAME.matches(name)) { "${route.describe()}: $name is not a header name" }
        check(!name.equals("Content-Type", ignoreCase = true)) { "${route.describe()}: declare the Content-Type with content()" }
        check(headers.keys.none { it.equals(name, ignoreCase = true) }) { "${route.describe()}: two response headers named $name" }
        headers[name] = Header(description, schema)
    }

    private fun checkOpen() {
        route.checkOpen()
        check(open) { "${route.describe()}: declare the response in its block" }
    }

    internal class Header(val description: String, val schema: String)
}

private const val JSON_CONTENT_TYPE = "application/json"
private val NO_BODY_STATUSES = setOf(204, 205)
private const val STRING_SCHEMA = "{\"type\":\"string\"}"

// An HTTP token, as a header name and the type and subtype of a media type must be.
private const val TOKEN = "[A-Za-z0-9!#$%&'*+.^_`|~-]+"
private val MEDIA_RANGE = Regex("$TOKEN/$TOKEN")
private val HEADER_NAME = Regex(TOKEN)

/** Keeps a declaration block from setting a property of an outer block by mistake. */
@DslMarker
internal annotation class RoutesDsl
