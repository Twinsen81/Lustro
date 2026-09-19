@file:Suppress("TooGenericExceptionCaught")

package io.github.twinsen81.lustro.internal

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs each NanoHTTPD connection on its own daemon thread, like NanoHTTPD's
 * `DefaultAsyncRunner`, but caps how many are open at once and never lets a
 * throwable leave that thread.
 *
 * NanoHTTPD hands a connection to the runner before reading any of its
 * request, so neither auth nor the [RequestLimiter] can bound these threads.
 * Uncapped, any local process could open connections until thread creation
 * failed and the host app aborted. Past [maxConnections], a new connection is
 * closed without a thread.
 *
 * NanoHTTPD's `ClientHandler.run()` catches only [Exception], and on Android an
 * uncaught [Error] reaches the default handler, which kills the host app.
 * [LustroServer.serve] already answers 500 for anything a route throws; this is
 * the backstop for what can still escape it, such as an [OutOfMemoryError] in
 * NanoHTTPD's own request parsing.
 *
 * It catches in the thread body rather than installing an uncaught-exception
 * handler because Android logs every uncaught throwable as `FATAL EXCEPTION`,
 * even when the thread's own handler keeps the process alive.
 */
internal class ContainedAsyncRunner(private val maxConnections: Int) : NanoHTTPD.AsyncRunner {
    private val connectionCount = AtomicLong()
    private val running: MutableSet<NanoHTTPD.ClientHandler> = ConcurrentHashMap.newKeySet()

    // Logs the limit once per stretch of rejections instead of once per socket.
    private var atLimit = false

    override fun exec(clientHandler: NanoHTTPD.ClientHandler) {
        // Only NanoHTTPD's accept thread calls exec(), so nothing else can add a
        // connection between this check and the add below.
        if (running.size >= maxConnections) {
            clientHandler.close()
            if (!atLimit) {
                atLimit = true
                Log.w(TAG, "Debug server has $maxConnections open connections; closing new ones until some end")
            }
            return
        }
        atLimit = false
        running.add(clientHandler)
        try {
            Thread({ runContained(clientHandler) }, "lustro-http-${connectionCount.incrementAndGet()}")
                .apply { isDaemon = true }
                .start()
        } catch (t: Throwable) {
            // Starting a thread can fail under memory or thread pressure. Drop the
            // connection: exec() runs on NanoHTTPD's accept loop, which must survive.
            running.remove(clientHandler)
            clientHandler.close()
            Log.w(TAG, "Could not start a debug server connection thread", t)
        }
    }

    override fun closed(clientHandler: NanoHTTPD.ClientHandler) {
        running.remove(clientHandler)
    }

    override fun closeAll() {
        running.forEach { it.close() }
    }

    private fun runContained(clientHandler: NanoHTTPD.ClientHandler) {
        try {
            clientHandler.run()
        } catch (t: Throwable) {
            // run() has already closed the socket and reported closed() by now.
            Log.w(TAG, "Debug server connection failed", t)
        }
    }

    private companion object {
        private const val TAG = "LustroServer"
    }
}
