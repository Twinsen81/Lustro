package io.github.twinsen81.lustro.internal

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.net.ServerSocket

/**
 * Becomes the socket factory of [server], makes its listening socket with the factory
 * that it had, and keeps the socket, so that [close] can close it while the server's
 * connections stay open: NanoHTTPD keeps the socket in a private field, and its `stop()`
 * closes the connections too.
 */
internal class ListeningSocketFactory(server: NanoHTTPD) : NanoHTTPD.ServerSocketFactory {
    private val delegate = server.serverSocketFactory

    init {
        server.serverSocketFactory = this
    }

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
