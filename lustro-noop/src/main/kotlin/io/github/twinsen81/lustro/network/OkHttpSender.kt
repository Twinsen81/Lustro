package io.github.twinsen81.lustro.network

import okhttp3.OkHttpClient

/**
 * The default [NetworkSender], backed by an OkHttp [OkHttpClient] (no-op build).
 *
 * Mirrors the `:lustro` `OkHttpSender` signature so consumer code compiles
 * unchanged, but [send] never issues a network call: it always returns a
 * [failure][NetworkSendResult.failure] result. The [client] is accepted and held
 * for API parity but never used.
 */
public class OkHttpSender(
    private val client: OkHttpClient,
) : NetworkSender {
    /** Returns a failure result; the no-op build never sends anything. */
    override fun send(request: NetworkSendRequest): NetworkSendResult =
        NetworkSendResult.failure("Lustro no-op build")
}
