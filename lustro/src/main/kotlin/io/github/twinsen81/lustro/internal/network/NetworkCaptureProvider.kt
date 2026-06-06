package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.network.NetworkCaptureSink
import okhttp3.Interceptor

/**
 * Internal bridge implemented by the network tab so `Lustro.networkInterceptor()`
 * can find the registered tab's sink and build an interceptor without the
 * `network` facade package depending on the `internal` package's concrete types.
 */
internal interface NetworkCaptureProvider {
    /** The capture sink owned by the tab. */
    val captureSink: NetworkCaptureSink

    /**
     * Builds an OkHttp interceptor that feeds this provider's sink. [captureEnabled]
     * gates capture (mock + throttle still run when capture is off).
     */
    fun createInterceptor(captureEnabled: () -> Boolean): Interceptor
}
