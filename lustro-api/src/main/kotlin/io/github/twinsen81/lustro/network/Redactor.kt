package io.github.twinsen81.lustro.network

import io.github.twinsen81.lustro.MediaType

/**
 * Removes sensitive data from captured network traffic before it is stored.
 *
 * Applied at capture time so redacted values are never persisted. The default
 * implementation (covering auth/cookie headers; token/key/secret/password/
 * auth-like URL query params, JSON fields, and form fields) lives in `:lustro`.
 */
public interface Redactor {
    /** Returns [url] with sensitive query parameters redacted. */
    public fun redactUrl(url: String): String

    /**
     * Returns the header [value], or the placeholder `"[REDACTED]"` when the
     * header [name] is considered sensitive.
     */
    public fun redactHeaderValue(name: String, value: String): String

    /**
     * Returns [body] with sensitive fields redacted, using [contentType] to
     * decide how to parse it (e.g. JSON vs. form-encoded), or `null` if unknown.
     */
    public fun redactBody(body: String, contentType: MediaType?): String
}
