package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.network.NetworkCaptureSink
import okhttp3.Interceptor
import okhttp3.WebSocket

/**
 * Internal bridge that the network tab holds, so `Lustro` can build the tab's
 * interceptor and sinks without the `network` facade package depending on the
 * `internal` package's concrete types. The tab holds it as a separate object and
 * does not implement it: the members of an interface that a public class
 * implements are public members of that class, and the no-op half has none of them.
 */
internal interface NetworkCaptureProvider {
    /**
     * Builds an OkHttp interceptor that feeds this provider's sink. [captureEnabled]
     * gates capture (mock + throttle still run when capture is off).
     */
    fun createInterceptor(captureEnabled: () -> Boolean): Interceptor

    /**
     * Builds the sink that the app reports another HTTP client's requests to.
     * [captureEnabled] gates capture, as it does for the interceptor.
     */
    fun createAppCaptureSink(captureEnabled: () -> Boolean): NetworkCaptureSink

    /**
     * Returns a factory that creates its sockets with [delegate] and records
     * their messages into this provider's tab.
     */
    fun wrapWebSocketFactory(delegate: WebSocket.Factory): WebSocket.Factory

    /** Records the live bind address so the Send panel can detect self-requests. */
    fun bindTo(host: String, port: Int)
}
