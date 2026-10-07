package io.github.twinsen81.lustro.internal

import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.MediaType
import io.github.twinsen81.lustro.network.CapturedBody
import io.github.twinsen81.lustro.network.CapturedResponse
import io.github.twinsen81.lustro.network.MockRule
import io.github.twinsen81.lustro.network.NetworkCaptureSink
import io.github.twinsen81.lustro.network.TransactionId

/** The sink of the no-op build: it records nothing. */
internal object NoOpCaptureSink : NetworkCaptureSink {
    @Suppress("RestrictedApi") // TransactionId's constructor is @RestrictTo(LIBRARY_GROUP); same-group call.
    private val skipped = TransactionId("")

    override fun beginRequest(
        url: String,
        method: String,
        headers: Headers,
        requestBody: CapturedBody?,
        contentType: MediaType?,
    ): TransactionId = skipped

    override fun findMockRule(url: String, method: String): MockRule? = null

    override fun completeRequest(id: TransactionId, response: CapturedResponse) = Unit

    override fun failRequest(id: TransactionId, durationMs: Long, error: String) = Unit
}
