package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.MediaType
import io.github.twinsen81.lustro.network.NetworkSendRequest

/** Internal implementation of the read-only [NetworkSendRequest] api interface. */
internal class NetworkSendRequestImpl(
    override val url: String,
    override val method: String,
    override val headers: Headers,
    override val body: ByteArray?,
    override val contentType: MediaType?,
) : NetworkSendRequest
