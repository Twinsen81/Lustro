package io.github.twinsen81.lustro.network

/**
 * Sends a [NetworkSendRequest] and returns its [NetworkSendResult].
 *
 * The supertype of `OkHttpSender`, the sender of the Network tab's Send form.
 * `NetworkDebugTab.create` takes only `OkHttpSender`s, so an implementation of
 * your own has no place to register: make the sender with
 * `OkHttpSender.create`. The runtime calls [send] off the main thread, and the
 * call blocks until it has the response, or until the request times out.
 */
public fun interface NetworkSender {
    /** Sends [request] and returns the result; called off the main thread. */
    public fun send(request: NetworkSendRequest): NetworkSendResult
}
