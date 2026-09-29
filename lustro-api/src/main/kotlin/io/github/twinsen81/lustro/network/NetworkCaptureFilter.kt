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
 * Once the filter skips a request, the Network tab shows an indicator, and its
 * details show the [description], so a teammate who sees fewer requests than
 * expected can find out why. Build a filter from a lambda with [of], or
 * implement both members.
 *
 * Lustro calls [shouldCapture] once per request, before it captures anything,
 * on the thread that makes the call: an OkHttp call's thread, or the thread that
 * uses an `HttpURLConnection` when platform capture is on. Keep it fast and
 * thread-safe. A filter that throws doesn't fail the call: the request is
 * captured, and Lustro logs the first failure only.
 */
public interface NetworkCaptureFilter {
    /**
     * What the filter leaves out, for a reader who hasn't seen its code, e.g.
     * `"Analytics calls, and requests marked X-No-Capture"`. Lustro reads it
     * once, when the tab is created.
     */
    public val description: String

    /** Returns `true` to capture [request], or `false` to leave it out of the Network tab. */
    public fun shouldCapture(request: NetworkCaptureRequest): Boolean

    /** Factories for [NetworkCaptureFilter]. */
    public companion object {
        /**
         * Returns a filter with the [description] that captures a request when
         * [shouldCapture] returns `true` for it.
         */
        @JvmStatic
        public fun of(
            description: String,
            shouldCapture: (NetworkCaptureRequest) -> Boolean,
        ): NetworkCaptureFilter = LambdaNetworkCaptureFilter(description, shouldCapture)
    }
}

/** The default [NetworkCaptureFilter], which captures every request. */
public val NoOpNetworkCaptureFilter: NetworkCaptureFilter = NetworkCaptureFilter.of("Captures every request") { true }

private class LambdaNetworkCaptureFilter(
    override val description: String,
    private val predicate: (NetworkCaptureRequest) -> Boolean,
) : NetworkCaptureFilter {
    override fun shouldCapture(request: NetworkCaptureRequest): Boolean = predicate(request)
}
