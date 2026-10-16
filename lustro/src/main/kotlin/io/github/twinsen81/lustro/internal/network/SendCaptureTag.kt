package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.network.TransactionId

/**
 * Rides on a request that the Send form sent, as an OkHttp request tag. The
 * Lustro interceptor fills in the transaction it recorded, so the send result
 * can name it. Stays empty when the sender's client has no Lustro interceptor,
 * or when the request wasn't captured.
 */
internal class SendCaptureTag {
    @Volatile
    var transactionId: TransactionId? = null
}
