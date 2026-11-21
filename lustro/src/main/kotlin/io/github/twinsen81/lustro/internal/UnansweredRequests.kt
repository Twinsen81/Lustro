package io.github.twinsen81.lustro.internal

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts the requests that the server has not finished answering: from `serve()` until
 * NanoHTTPD has sent the response and closes it. The lifecycle drain waits for them too,
 * because the [RequestLimiter] frees a request's slot when its handler returns, before
 * the response is written: a drain that waited only for the limiter could close the
 * connection while a large response is still being sent.
 */
internal class UnansweredRequests {
    private val count = AtomicInteger()

    fun count(): Int = count.get()

    /** Runs [serve] for one request, which stays counted until NanoHTTPD closes its response. */
    fun track(serve: () -> NanoHTTPD.Response): NanoHTTPD.Response {
        count.incrementAndGet()
        val response =
            try {
                serve()
            } catch (t: Throwable) {
                count.decrementAndGet()
                throw t
            }
        response.data = CountedUntilClosed(response.data ?: ByteArrayInputStream(ByteArray(0)))
        return response
    }

    // NanoHTTPD closes the response once it is sent, also when the send fails.
    private inner class CountedUntilClosed(data: InputStream) : FilterInputStream(data) {
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            try {
                super.close()
            } finally {
                count.decrementAndGet()
            }
        }
    }
}
