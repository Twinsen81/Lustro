package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.MediaType
import io.github.twinsen81.lustro.network.CapturedBody
import io.github.twinsen81.lustro.network.CapturedResponse
import io.github.twinsen81.lustro.network.MockRule
import io.github.twinsen81.lustro.network.NetworkCaptureSink
import io.github.twinsen81.lustro.network.TransactionId

/**
 * The sink that `Lustro.networkCaptureSink()` gives the app, for an HTTP client
 * that Lustro has no adapter for. It skips a request while capture is paused or
 * when the capture filter leaves it out, as the built-in adapters do, and it
 * counts a hit on each mock rule it returns. The app's adapter calls it from
 * its own requests, so nothing it does throws.
 */
internal class AppCaptureSink(
    private val store: NetworkTrafficStore,
    private val captureEnabled: () -> Boolean,
    private val captureFilter: SafeCaptureFilter,
) : NetworkCaptureSink {
    override fun beginRequest(
        url: String,
        method: String,
        headers: Headers,
        requestBody: CapturedBody?,
        contentType: MediaType?,
    ): TransactionId =
        try {
            if (captureEnabled() && captureFilter.shouldCapture(url, method, headers)) {
                store.beginRequest(url, method, headers, requestBody, contentType)
            } else {
                SKIPPED_TRANSACTION
            }
        } catch (t: Throwable) {
            logCaptureFailure("Could not record a request reported by the app", t)
            SKIPPED_TRANSACTION
        }

    override fun findMockRule(url: String, method: String): MockRule? =
        try {
            store.findMockRule(url, method)?.also { store.incrementHitCount(it.id) }
        } catch (t: Throwable) {
            logCaptureFailure("Could not look up a mock rule for the app", t)
            null
        }

    override fun completeRequest(id: TransactionId, response: CapturedResponse) {
        if (id == SKIPPED_TRANSACTION) return
        try {
            store.completeRequest(id, response)
        } catch (t: Throwable) {
            logCaptureFailure("Could not record a response reported by the app", t)
        }
    }

    override fun failRequest(id: TransactionId, durationMs: Long, error: String) {
        if (id == SKIPPED_TRANSACTION) return
        try {
            store.failRequest(id, durationMs, error)
        } catch (t: Throwable) {
            logCaptureFailure("Could not record a failure reported by the app", t)
        }
    }
}

/** A sink that records nothing, for an app with no network tab. */
internal object NoOpCaptureSink : NetworkCaptureSink {
    override fun beginRequest(
        url: String,
        method: String,
        headers: Headers,
        requestBody: CapturedBody?,
        contentType: MediaType?,
    ): TransactionId = SKIPPED_TRANSACTION

    override fun findMockRule(url: String, method: String): MockRule? = null

    override fun completeRequest(id: TransactionId, response: CapturedResponse) = Unit

    override fun failRequest(id: TransactionId, durationMs: Long, error: String) = Unit
}

// The id of a request that is not recorded. The store's ids are UUIDs, so none is empty.
@Suppress("RestrictedApi") // TransactionId's constructor is @RestrictTo(LIBRARY_GROUP); same-group call.
internal val SKIPPED_TRANSACTION = TransactionId("")
