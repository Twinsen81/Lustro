package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.network.NetworkCaptureFilter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Asks the app's [NetworkCaptureFilter] whether to capture a request, for both
 * capture adapters. A filter that throws must not fail the app's call or hide
 * its traffic, so the request is captured. Only the first failure is logged,
 * because the filter runs on every request.
 */
internal class SafeCaptureFilter(private val filter: NetworkCaptureFilter) {
    private val failureLogged = AtomicBoolean(false)

    fun shouldCapture(url: String, method: String, headers: Headers): Boolean =
        try {
            filter.shouldCapture(NetworkCaptureRequestImpl(url, method, headers))
        } catch (t: Throwable) {
            if (failureLogged.compareAndSet(false, true)) {
                logCaptureFailure("The capture filter failed, so the request is captured; later failures are not logged", t)
            }
            true
        }
}
