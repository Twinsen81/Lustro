package io.github.twinsen81.lustro.network

import io.github.twinsen81.lustro.MediaType

/**
 * The default [Redactor] (no-op build).
 *
 * In the runtime this redacts auth/cookie headers and token/key/secret/password/
 * auth-like URL, JSON, and form fields at capture time. Because the no-op build
 * never captures any traffic, redaction is moot: every method returns its input
 * unchanged. The type and members mirror the runtime so consumer code that names
 * [DefaultRedactor] compiles identically.
 */
public object DefaultRedactor : Redactor {
    /** Returns [url] unchanged (no capture happens in the no-op build). */
    override fun redactUrl(url: String): String = url

    /** Returns [value] unchanged (no capture happens in the no-op build). */
    override fun redactHeaderValue(name: String, value: String): String = value

    /** Returns [body] unchanged (no capture happens in the no-op build). */
    override fun redactBody(body: String, contentType: MediaType?): String = body
}
