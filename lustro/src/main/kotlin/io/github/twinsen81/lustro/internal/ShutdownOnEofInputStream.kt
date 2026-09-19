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
 */
internal class ShutdownOnEofInputStream(input: InputStream) : FilterInputStream(input) {
    override fun read(): Int = super.read().also { if (it < 0) throw clientClosed() }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it < 0) throw clientClosed() }

    // NanoHTTPD closes the connection without logging for exactly this message.
    private fun clientClosed() = SocketException("NanoHttpd Shutdown")
}
