package io.github.twinsen81.lustro.internal

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.net.ServerSocket

/**
 * Makes NanoHTTPD's listening socket with [delegate] and keeps it, so that [close] can
 * close it while the server's connections stay open: NanoHTTPD keeps the socket in a
 * private field, and its `stop()` closes the connections too.
 */
internal class ListeningSocketFactory(private val delegate: NanoHTTPD.ServerSocketFactory) :
    NanoHTTPD.ServerSocketFactory {
    @Volatile
    private var socket: ServerSocket? = null

    override fun create(): ServerSocket = delegate.create().also { socket = it }

    /** Closes the listening socket: a new connection is refused, and the port is free. */
    fun close() {
        try {
            socket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing the listening socket of the debug server", e)
        }
    }

    private companion object {
        private const val TAG = "LustroServer"
    }
}
