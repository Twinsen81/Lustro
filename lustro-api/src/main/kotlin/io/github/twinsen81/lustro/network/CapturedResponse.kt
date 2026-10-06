package io.github.twinsen81.lustro.network

import io.github.twinsen81.lustro.Headers

/**
 * A response as a capture adapter reports it through
 * [NetworkCaptureSink.completeRequest].
 *
 * Built with [Builder], never constructed directly, so a field added in a later
 * release arrives as one more optional builder call: an adapter written against
 * an earlier release keeps compiling and linking, and reports that field as
 * unknown. Immutable, and deliberately not a `data class`.
 */
public class CapturedResponse private constructor(builder: Builder) {
    // The constructor takes the Builder rather than its values, so its signature,
    // which the ABI dump lists, stays the same as fields are added.

    /** The HTTP status code. */
    public val statusCode: Int = builder.statusCode

    /** The response headers. */
    public val headers: Headers = builder.headers

    /** The captured body with its truncation flag and byte size, or `null` when none was captured. */
    public val body: CapturedBody? = builder.body

    /** Milliseconds from the start of the request to this report. */
    public val durationMs: Long = builder.durationMs

    /** Whether a mock rule produced the response instead of the network. */
    public val isMocked: Boolean = builder.isMocked

    /**
     * `true` for a finished response. `false` for an in-flight progressive
     * update: an event stream reports one when its headers arrive and on each
     * read, then a complete response at EOF or close.
     */
    public val isComplete: Boolean = builder.isComplete

    /**
     * The protocol the response came over, as the HTTP client names it, e.g.
     * `http/1.1`, `h2`, or `h3`, or `null` when it isn't known, as for a
     * mocked response.
     */
    public val protocol: String? = builder.protocol

    /**
     * The URL the response came from, when it isn't the URL the request was
     * begun with: after the client followed a redirect, or after something
     * that runs after the adapter rewrote the URL. `null` otherwise, and when
     * the adapter can't tell.
     */
    public val finalUrl: String? = builder.finalUrl

    /**
     * The responses the client answered with another request before this one,
     * oldest first: the redirects it followed and the authentication challenges
     * it answered. Empty when there were none, or when the adapter can't see
     * them, as platform `HttpURLConnection` capture can't.
     */
    public val priorResponses: List<CapturedPriorResponse> = builder.priorResponses

    /**
     * Builds a [CapturedResponse] with [statusCode] and [durationMs]. Unless
     * set, it has no headers, no body, no protocol, no final URL, and no prior
     * responses, and is complete and not mocked.
     */
    public class Builder(
        internal val statusCode: Int,
        internal val durationMs: Long,
    ) {
        internal var headers: Headers = Headers.EMPTY
        internal var body: CapturedBody? = null
        internal var isMocked: Boolean = false
        internal var isComplete: Boolean = true
        internal var protocol: String? = null
        internal var finalUrl: String? = null
        internal var priorResponses: List<CapturedPriorResponse> = emptyList()

        /** Sets [CapturedResponse.headers]. */
        public fun headers(value: Headers): Builder = apply { headers = value }

        /** Sets [CapturedResponse.body]. */
        public fun body(value: CapturedBody?): Builder = apply { body = value }

        /** Sets [CapturedResponse.isMocked]. */
        public fun mocked(value: Boolean): Builder = apply { isMocked = value }

        /** Sets [CapturedResponse.isComplete]. */
        public fun complete(value: Boolean): Builder = apply { isComplete = value }

        /** Sets [CapturedResponse.protocol]. */
        public fun protocol(value: String?): Builder = apply { protocol = value }

        /** Sets [CapturedResponse.finalUrl]. */
        public fun finalUrl(value: String?): Builder = apply { finalUrl = value }

        /** Sets [CapturedResponse.priorResponses]. The list is copied. */
        public fun priorResponses(value: List<CapturedPriorResponse>): Builder = apply { priorResponses = value.toList() }

        /** Returns a [CapturedResponse] with the values set so far. */
        public fun build(): CapturedResponse = CapturedResponse(this)
    }
}
