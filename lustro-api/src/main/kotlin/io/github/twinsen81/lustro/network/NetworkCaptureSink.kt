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
     * Records a completion of the transaction [id] with the response
     * [statusCode], [responseHeaders], optional [responseBody], elapsed
     * [durationMs], and whether the response was [isMocked].
     *
     * [complete] distinguishes a finished response (`true`, the default for
     * ordinary requests) from an in-flight progressive update (event streams
     * report `false` for the initial record and each progressive update, then
     * `true` at EOF/close). The [responseBody] carries the captured text plus
     * its truncation flag and full byte size.
     */
    public fun completeRequest(
        id: TransactionId,
        statusCode: Int,
        responseHeaders: Headers,
        responseBody: CapturedBody?,
        durationMs: Long,
        isMocked: Boolean,
        complete: Boolean = true,
    )

    /**
     * Records a failed transaction [id] after [durationMs], with the [error]
     * description.
     */
    public fun failRequest(id: TransactionId, durationMs: Long, error: String)
}
