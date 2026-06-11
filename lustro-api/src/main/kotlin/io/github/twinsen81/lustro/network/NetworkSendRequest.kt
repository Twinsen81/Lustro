package io.github.twinsen81.lustro.network

import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.MediaType

/**
 * A read-only request handed to a [NetworkSender] to be replayed/sent.
 *
 * Library-constructed: the concrete implementation is `internal` in `:lustro`.
 */
public interface NetworkSendRequest {
    /** The absolute request URL. */
    public val url: String

    /** The HTTP method, e.g. `GET` or `POST`. */
    public val method: String

    /** The request headers. */
    public val headers: Headers

    /** The request body bytes, or `null` if there is no body. */
    public val body: ByteArray?

    /** The `Content-Type` of the body, or `null` if absent. */
    public val contentType: MediaType?
}
