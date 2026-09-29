package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.network.NetworkCaptureRequest

internal class NetworkCaptureRequestImpl(
    override val url: String,
    override val method: String,
    override val headers: Headers,
) : NetworkCaptureRequest
