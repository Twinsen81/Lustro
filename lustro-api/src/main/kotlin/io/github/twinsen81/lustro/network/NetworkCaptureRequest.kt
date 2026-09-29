package io.github.twinsen81.lustro.network

import io.github.twinsen81.lustro.Headers

/**
 * A request as a [NetworkCaptureFilter] sees it, before anything is captured,
 * so its URL and headers are not redacted yet.
 *
 * Library-constructed: the concrete implementation is `internal` in `:lustro`,
 * and a later release can add properties.
 */
public interface NetworkCaptureRequest {
    /** The absolute request URL. */
    public val url: String

    /** The HTTP method, e.g. `GET` or `POST`. */
    public val method: String

    /** The headers the app set on the request, without those the HTTP client adds itself. */
    public val headers: Headers
}
