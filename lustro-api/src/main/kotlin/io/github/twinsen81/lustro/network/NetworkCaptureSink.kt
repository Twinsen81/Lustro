package io.github.twinsen81.lustro.network

import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.MediaType

/**
 * HTTP-client-agnostic sink that records network transactions.
 *
 * The OkHttp adapter lives in `:lustro`; this SPI lets any HTTP client report
 * the lifecycle of a request: [beginRequest] on dispatch, then one or more
 * [completeRequest] calls (progressive for event streams) or a single
 * [failRequest].
 *
 * The built-in sink returns from each call right away and records the
 * transaction on a background thread once it's redacted, so a transaction can
 * take a moment to show up.
 */
public interface NetworkCaptureSink {
    /**
     * Records the start of a request to [url] with the given [method],
     * [headers], optional [requestBody], and [contentType], returning a
     * [TransactionId] to correlate completion calls. The [requestBody] carries
     * the captured text plus its truncation flag and full byte size.
     */
    public fun beginRequest(
        url: String,
        method: String,
        headers: Headers,
        requestBody: CapturedBody?,
        contentType: MediaType?,
    ): TransactionId

    /**
     * Returns the mock rule matching the [url]/[method], or `null` when no
     * enabled rule matches.
     */
    public fun findMockRule(url: String, method: String): MockRule?

    /**
     * Records the [response] to the transaction [id]. A request whose body the
     * adapter captures whole reports one complete response. One whose body the
     * app reads after that, such as a download or an event stream, reports an
     * in-flight one when its headers arrive, and more as it reads if the
     * adapter has progress to show, then a complete one when the app reaches
     * the end of the body or closes it (see [CapturedResponse.isComplete]).
     */
    public fun completeRequest(id: TransactionId, response: CapturedResponse)

    /**
     * Records a failed transaction [id] after [durationMs], with the [error]
     * description. After a response was reported, it records that the body
     * failed while the app read it, and the response's status and headers are
     * kept.
     */
    public fun failRequest(id: TransactionId, durationMs: Long, error: String)
}
