package io.github.twinsen81.lustro.internal.network

import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer

/**
 * Passes [delegate] to OkHttp unchanged and keeps a copy of the bytes it writes,
 * at most [cap] of them. [onWritten] gets the copy and the full size once, when
 * a write of the whole body returns.
 *
 * Capture can't write the body a second time for itself: an app's body can
 * report upload progress, read a file, or read a stream that gives its bytes
 * only once. OkHttp writes the body again when it retries or follows a
 * redirect; a write that throws leaves the copy to the next one, and the writes
 * after the first whole one aren't copied.
 */
internal class CapturingRequestBody(
    private val delegate: RequestBody,
    private val cap: Long,
    private val onWritten: (raw: ByteArray, fullSize: Long) -> Unit,
) : RequestBody() {
    private val written = AtomicBoolean(false)

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun isOneShot(): Boolean = delegate.isOneShot()

    override fun isDuplex(): Boolean = delegate.isDuplex()

    override fun writeTo(sink: BufferedSink) {
        if (written.get()) {
            delegate.writeTo(sink)
            return
        }
        val copy = Buffer()
        var fullSize = 0L
        val tee =
            object : ForwardingSink(sink) {
                override fun write(source: Buffer, byteCount: Long) {
                    val keep = minOf((cap - copy.size).coerceAtLeast(0L), byteCount)
                    if (keep > 0) source.copyTo(copy, 0, keep)
                    fullSize += byteCount
                    super.write(source, byteCount)
                }
            }.buffer()
        delegate.writeTo(tee)
        // A body that closed the sink has already sent what it wrote.
        if (tee.isOpen) tee.emit()
        if (written.compareAndSet(false, true)) onWritten(copy.readByteArray(), fullSize)
    }
}
