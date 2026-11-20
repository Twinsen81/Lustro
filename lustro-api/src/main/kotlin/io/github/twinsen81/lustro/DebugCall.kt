package io.github.twinsen81.lustro

/**
 * One request to a declared route, with the values of the route's parameters, which the
 * router has already checked against their declarations.
 */
@ExperimentalDebugRoutes
public class DebugCall internal constructor(
    /** The request, for what the declarations don't cover, such as a raw body or a header. */
    public val request: DebugRequest,
    private val values: Map<ParamDecl, Any?>,
) {
    /**
     * Returns the value of [param]: `null` for an optional parameter that the request does not
     * give, else a value of the declared type and options.
     *
     * @throws IllegalArgumentException when [param] is a parameter of another route.
     */
    public operator fun <T> get(param: DebugParam<T>): T {
        require(param.decl in values) { "${param.name} is not a parameter of this route" }
        @Suppress("UNCHECKED_CAST")
        return values[param.decl] as T
    }
}

/**
 * Answers a declared route: [DebugRoute.handle] sets it. A Kotlin lambda converts to it:
 * `handle { call -> ... }`.
 */
@ExperimentalDebugRoutes
public fun interface DebugHandler {
    /**
     * Answers [call]. Runs off the main thread, possibly at the same time as other requests,
     * with the rules of [DebugTab.handle]. To answer an error from deep in the handler's code,
     * throw a [DebugResponseException].
     */
    public fun handle(call: DebugCall): DebugResponse
}

/**
 * Carries [response] out of the code of a route to the client. A declared route answers
 * with [response] when its handler throws this, and so does the server for the
 * [DebugTab.handle] of any tab, so a helper deep in a handler can end the request with
 * an error, such as `throw DebugResponseException(DebugResponse.error("No such file", 404, field = "file"))`.
 */
@ExperimentalDebugRoutes
public class DebugResponseException(
    /** The response that the client gets. */
    public val response: DebugResponse,
) : RuntimeException("The request ends with status ${response.status}")
