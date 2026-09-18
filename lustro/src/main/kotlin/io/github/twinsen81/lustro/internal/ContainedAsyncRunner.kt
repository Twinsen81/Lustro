@file:Suppress("TooGenericExceptionCaught")

package io.github.twinsen81.lustro.internal

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs each NanoHTTPD connection on its own daemon thread, like NanoHTTPD's
 * `DefaultAsyncRunner`, but never lets a throwable leave that thread.
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
internal class ContainedAsyncRunner : NanoHTTPD.AsyncRunner {
    private val connectionCount = AtomicLong()
    private val running: MutableSet<NanoHTTPD.ClientHandler> = ConcurrentHashMap.newKeySet()

    override fun exec(clientHandler: NanoHTTPD.ClientHandler) {
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
