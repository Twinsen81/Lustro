package io.github.twinsen81.lustro.sample

import io.github.twinsen81.lustro.network.NetworkCaptureFilter
import io.github.twinsen81.lustro.network.NetworkCaptureRequest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A [NetworkCaptureFilter] that keeps two kinds of traffic out of the Network
 * tab: calls to an analytics host, and requests the app marks with
 * [NO_CAPTURE_HEADER]. Lustro still applies mock rules and the throttle to
 * them, but doesn't record them.
 */
public class SampleCaptureFilter : NetworkCaptureFilter {
    override fun shouldCapture(request: NetworkCaptureRequest): Boolean =
        request.url.toHttpUrlOrNull()?.host != ANALYTICS_HOST && request.headers.get(NO_CAPTURE_HEADER) == null

    internal companion object {
        // A name reserved for examples, so it never resolves: a call to it fails
        // unless a mock rule answers it, and stays out of the Network tab either way.
        const val ANALYTICS_HOST: String = "analytics.example"
    }
}
