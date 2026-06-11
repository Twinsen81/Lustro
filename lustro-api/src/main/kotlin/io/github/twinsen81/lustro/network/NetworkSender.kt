package io.github.twinsen81.lustro.network

/**
 * Sends a [NetworkSendRequest] and returns its [NetworkSendResult].
 *
 * A functional SPI implemented by the host (e.g. an OkHttp-backed sender in
 * `:lustro`). The runtime invokes [send] off the main thread; it is
 * intentionally non-suspending.
 */
public fun interface NetworkSender {
    /** Sends [request] and returns the result; called off the main thread. */
    public fun send(request: NetworkSendRequest): NetworkSendResult
}
