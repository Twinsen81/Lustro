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
     * Records the [response] to the transaction [id]. An ordinary request
     * reports one complete response; an event stream reports in-flight ones
     * as it reads, then a complete one at EOF or close (see
     * [CapturedResponse.isComplete]).
     */
    public fun completeRequest(id: TransactionId, response: CapturedResponse)

    /**
     * Records a failed transaction [id] after [durationMs], with the [error]
     * description.
     */
    public fun failRequest(id: TransactionId, durationMs: Long, error: String)
}
