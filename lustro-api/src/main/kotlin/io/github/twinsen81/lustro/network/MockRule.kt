package io.github.twinsen81.lustro.network

import io.github.twinsen81.lustro.Headers

/**
 * A read-only view of a network mock rule.
 *
 * Library-constructed: the concrete implementation is `internal` in `:lustro`.
 * Consumers read these properties to inspect configured mocks.
 */
public interface MockRule {
    /** The stable rule id. */
    public val id: String

    /** Whether the rule is currently active. */
    public val enabled: Boolean

    /** The human-readable rule name. */
    public val name: String

    /**
     * The URL match pattern. Matched as a substring, or as a regular expression
     * when prefixed with `regex:`.
     */
    public val urlPattern: String

    /** The HTTP method to match, or `null` to match any method. */
    public val method: String?

    /** The HTTP status code returned by the mock. */
    public val statusCode: Int

    /** The headers returned by the mock. */
    public val responseHeaders: Headers

    /** The response body returned by the mock. */
    public val responseBody: String

    /** How many times this rule has matched a request. */
    public val hitCount: Int
}
