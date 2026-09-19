package io.github.twinsen81.lustro.internal

import java.io.FilterInputStream
import java.io.InputStream
import java.net.SocketException

/**
 * Wraps a connection's socket input so that end of stream ends the connection:
 * a read that hits it throws NanoHTTPD's quiet-shutdown [SocketException]
 * instead of returning `-1`.
 *
 * NanoHTTPD 2.3.1 mishandles a client that closes in the middle of a request's
 * headers. It rewinds its buffer to the start of the request, serves the
 * partial request, then finds the socket still open on its side and reads the
 * same buffered bytes again, forever. The connection thread spins at full CPU
 * and logs a failed send on every pass until the server stops.
 *
 * Once the client has closed, nothing more can arrive on the connection, so
 * ending it is always right. Readers still get every byte the client sent;
 * only the read past the last one throws.
 *
 * It also reports no bytes available. NanoHTTPD reads through a buffered
 * stream whose `available()` adds this stream's count to its own buffered
 * bytes, and [IncomingBody.headersFit] needs those buffered bytes alone.
 */
internal class ShutdownOnEofInputStream(input: InputStream) : FilterInputStream(input) {
    override fun read(): Int = super.read().also { if (it < 0) throw connectionEnd() }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it < 0) throw connectionEnd() }

    override fun available(): Int = 0
}

/**
 * Thrown on a connection's thread, ends the connection without a response:
 * NanoHTTPD closes it without logging for exactly this message.
 */
internal fun connectionEnd(): SocketException = SocketException("NanoHttpd Shutdown")
