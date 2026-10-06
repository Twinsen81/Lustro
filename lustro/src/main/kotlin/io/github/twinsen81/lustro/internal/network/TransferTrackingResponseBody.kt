package io.github.twinsen81.lustro.internal.network

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

/**
 * Passes [delegate] through to the app unchanged and reports when the app is
 * done with it: [onEnd] once it reads to the end or closes the body, with the
 * bytes read so far and whether it reached the end, or [onFailure] when a read
 * throws. Exactly one of them is called, once.
 *
 * A response's headers can arrive long before its body: a large download takes
 * seconds after them. This is how the interceptor learns when the body ended.
 */
internal class TransferTrackingResponseBody(
    private val delegate: ResponseBody,
    private val onEnd: (bytesRead: Long, reachedEnd: Boolean) -> Unit,
    private val onFailure: (bytesRead: Long, error: IOException) -> Unit,
) : ResponseBody() {
    private val ended = AtomicBoolean(false)

    @Volatile
    private var bytesRead = 0L

    private val trackingSource: BufferedSource by lazy {
        object : ForwardingSource(delegate.source()) {
            override fun read(
                sink: Buffer,
                byteCount: Long,
            ): Long {
                val read =
                    try {
                        super.read(sink, byteCount)
                    } catch (e: IOException) {
                        if (ended.compareAndSet(false, true)) onFailure(bytesRead, e)
                        throw e
                    }
                if (read == -1L) end(reachedEnd = true) else bytesRead += read
                return read
            }

            override fun close() {
                try {
                    super.close()
                } finally {
                    end(reachedEnd = false)
                }
            }
        }.buffer()
    }

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun source(): BufferedSource = trackingSource

    private fun end(reachedEnd: Boolean) {
        if (ended.compareAndSet(false, true)) onEnd(bytesRead, reachedEnd)
    }
}
