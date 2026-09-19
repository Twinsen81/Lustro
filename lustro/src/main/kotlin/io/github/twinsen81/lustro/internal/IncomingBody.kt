package io.github.twinsen81.lustro.internal

import fi.iki.elonen.NanoHTTPD
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * The body of the request [LustroServer] is serving. It's read only when a
 * route asks for it, at most once, and [isConsumed] tracks whether it was.
 *
 * NanoHTTPD 2.3.1 never skips a body that `serve()` leaves unread. It parses
 * the next request on a keep-alive connection from wherever the stream
 * stopped, so leftover body bytes end up in front of that request, or are
 * parsed as a request of their own. While [isConsumed] is false, the response
 * must close the connection.
 */
internal class IncomingBody(private val session: NanoHTTPD.IHTTPSession) {
    /** The declared `Content-Length`, or null when there's none or it's malformed. */
    val declaredLength: Long?

    /**
     * False when the end of the body can't be found: the request has a
     * `Transfer-Encoding`, which NanoHTTPD doesn't decode, or a malformed
     * `Content-Length`.
     */
    val isFramed: Boolean

    /** True once the stream is past this request: its body was read, or it has none. */
    var isConsumed: Boolean
        private set

    init {
        val headers = session.headers.orEmpty()
        val contentLength = headers["content-length"]
        declaredLength = contentLength?.toLongOrNull()?.takeIf { it >= 0 }
        isFramed = "transfer-encoding" !in headers && (contentLength == null || declaredLength != null)
        isConsumed = isFramed && (declaredLength ?: 0L) == 0L
    }

    /**
     * Reads the whole body, or returns null when there's none to read. The
     * buffer is allocated up front, so the caller must first check
     * [declaredLength] against its cap. Throws [Incomplete] when the client
     * stops sending before the end.
     */
    fun read(): ByteArray? {
        if (isConsumed || !isFramed) return null
        val length = declaredLength ?: return null
        require(length <= Int.MAX_VALUE) { "A $length-byte body can't be buffered" }
        val buffer = ByteArray(length.toInt())
        var read = 0
        try {
            val input = session.inputStream
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n < 0) throw EOFException("Body ended after $read of $length bytes")
                read += n
            }
        } catch (e: IOException) {
            throw Incomplete(e)
        }
        isConsumed = true
        return buffer
    }

    /**
     * Wraps a response's [data] so that closing it reads and drops the rest of
     * this body. NanoHTTPD closes the data right after sending the response and
     * before closing the connection: closing a socket that still has unread
     * input resets the connection, and a client still uploading can then lose
     * the response.
     */
    fun discardAfterSending(data: InputStream): InputStream =
        object : FilterInputStream(data) {
            private var closed = false

            override fun close() {
                if (closed) return
                closed = true
                try {
                    super.close()
                } finally {
                    discardRest()
                }
            }
        }

    // Stops at the end of the body, after MAX_DISCARDED_BYTES, or when the client
    // closes, stalls past the socket's read timeout, or resets.
    private fun discardRest() {
        if (isConsumed) return
        var remaining = minOf(if (isFramed) declaredLength ?: 0L else Long.MAX_VALUE, MAX_DISCARDED_BYTES)
        val buffer = ByteArray(DISCARD_CHUNK_BYTES)
        try {
            val input = session.inputStream
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (n < 0) return
                remaining -= n
            }
        } catch (_: IOException) {
            // Nothing left to protect.
        }
    }

    /** The client stopped sending before the end of the body it declared. */
    class Incomplete(cause: IOException) : Exception(cause)

    private companion object {
        // Enough for a client still uploading a body a few times the default cap
        // to get its response. Past it, the connection closes regardless.
        private const val MAX_DISCARDED_BYTES = 4L * 1024 * 1024
        private const val DISCARD_CHUNK_BYTES = 8 * 1024
    }
}
