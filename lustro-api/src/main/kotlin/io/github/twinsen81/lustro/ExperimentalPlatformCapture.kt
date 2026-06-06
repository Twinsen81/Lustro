package io.github.twinsen81.lustro

/**
 * Marks API that enables capture of platform `HttpURLConnection` traffic.
 *
 * Platform `HttpURLConnection` capture relies on a non-public platform detail
 * (a process-global stream handler) that can degrade across OS and SDK
 * versions. The behaviour is best-effort and fail-open, so it is gated behind
 * this opt-in marker to make the trade-off explicit at the call site. Opting in
 * acknowledges that the capture path is not covered by the library's binary- and
 * behaviour-compatibility guarantees.
 */
@RequiresOptIn(
    message = "Platform HttpURLConnection capture relies on a non-public platform detail that can " +
        "degrade across OS/SDK versions. Opt in to acknowledge this.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
public annotation class ExperimentalPlatformCapture
