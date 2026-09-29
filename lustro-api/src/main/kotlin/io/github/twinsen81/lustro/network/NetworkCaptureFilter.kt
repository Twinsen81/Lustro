package io.github.twinsen81.lustro.network

/**
 * Decides whether a request is captured.
 *
 * A host-supplied SPI that keeps traffic out of the Network tab, such as calls
 * to an analytics host or requests the app marks with a header of its own. A
 * request the filter skips is never stored, so it costs no redaction and no part
 * of the capture budget. The filter affects capture only: mock rules and the
 * throttle still apply to a skipped request, as they do while capture is paused.
 * Use [NoOpNetworkCaptureFilter] for the default behaviour of capturing every
 * request.
 *
 * Lustro calls it once per request, before it captures anything, on the thread
 * that makes the call: an OkHttp call's thread, or the thread that uses an
 * `HttpURLConnection` when platform capture is on. Keep it fast and
 * thread-safe. A filter that throws doesn't fail the call: the request is
 * captured, and Lustro logs the first failure only.
 */
public fun interface NetworkCaptureFilter {
    /** Returns `true` to capture [request], or `false` to leave it out of the Network tab. */
    public fun shouldCapture(request: NetworkCaptureRequest): Boolean
}

/** The default [NetworkCaptureFilter], which captures every request. */
public val NoOpNetworkCaptureFilter: NetworkCaptureFilter = NetworkCaptureFilter { true }
