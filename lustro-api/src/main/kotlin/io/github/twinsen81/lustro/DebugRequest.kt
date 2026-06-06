package io.github.twinsen81.lustro

/**
 * An incoming debug API request handed to [DebugTab.handle].
 *
 * Constructed by the Lustro runtime, but the constructor is public so consumers
 * can build instances directly in unit tests. The [path] is everything after
 * `/api/v1/<tab-id>/`.
 */
public class DebugRequest @JvmOverloads constructor(
    /** The request path after `/api/v1/<tab-id>/`. */
    public val path: String,
    /** The HTTP method, e.g. `GET` or `POST`. */
    public val method: String,
    /** Query parameters, each name mapped to its ordered list of values. */
    public val queryParams: Map<String, List<String>> = emptyMap(),
    /** The request headers. */
    public val headers: Headers = Headers.EMPTY,
    /** The raw request body bytes, or `null` if there is no body. */
    public val body: ByteArray? = null,
    /** The parsed `Content-Type` of the body, or `null` if absent/unparseable. */
    public val contentType: MediaType? = null,
) {
    /**
     * Returns the first value of the query parameter named [name], or `null`
     * if the parameter is absent or has no values.
     */
    public fun queryParam(name: String): String? = queryParams[name]?.firstOrNull()

    /**
     * Returns the request [body] decoded as a UTF-8 string, or `null` if there
     * is no body.
     */
    public fun bodyAsString(): String? = body?.toString(Charsets.UTF_8)
}
