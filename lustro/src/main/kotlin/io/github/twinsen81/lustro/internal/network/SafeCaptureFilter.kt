package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.network.NetworkCaptureFilter
import io.github.twinsen81.lustro.network.NoOpNetworkCaptureFilter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Asks the app's [NetworkCaptureFilter] whether to capture a request, for both
 * capture adapters, and counts its answers for the Network tab. A filter that
 * throws must not fail the app's call or hide its traffic, so the request is
 * captured. Only the first failure is logged, because the filter runs on every
 * request.
 */
internal class SafeCaptureFilter(private val filter: NetworkCaptureFilter) {
    private val failureLogged = AtomicBoolean(false)
    private val skippedCount = AtomicLong()
    private val failedCount = AtomicLong()

    /** Whether the app set a filter, rather than keeping the default that captures everything. */
    val isSet: Boolean = filter !== NoOpNetworkCaptureFilter

    // Read once, and guarded: the getter is the app's code.
    val description: String =
        try {
            filter.description.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            logCaptureFailure("Could not read the capture filter's description", t)
            null
        } ?: NO_DESCRIPTION

    /** Requests the filter left out since the tab started or the list was last cleared. */
    val skipped: Long
        get() = skippedCount.get()

    /** Requests the filter threw on, and so were captured, over the same period. */
    val failed: Long
        get() = failedCount.get()

    fun shouldCapture(url: String, method: String, headers: Headers): Boolean =
        try {
            filter.shouldCapture(NetworkCaptureRequestImpl(url, method, headers))
                .also { captured -> if (!captured) skippedCount.incrementAndGet() }
        } catch (t: Throwable) {
            failedCount.incrementAndGet()
            if (failureLogged.compareAndSet(false, true)) {
                logCaptureFailure("The capture filter failed, so the request is captured; later failures are not logged", t)
            }
            true
        }

    /** Starts both counts again, as clearing the transaction list does. */
    fun resetCounts() {
        skippedCount.set(0)
        failedCount.set(0)
    }

    private companion object {
        private const val NO_DESCRIPTION = "The app gave this filter no description."
    }
}
