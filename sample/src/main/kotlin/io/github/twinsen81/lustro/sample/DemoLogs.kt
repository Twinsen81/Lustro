package io.github.twinsen81.lustro.sample

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Writes demo lines to logcat for the Logs tab while [start]ed: a heartbeat every
 * two seconds and, between the heartbeats, sync lines at different levels. It
 * also writes bursts on demand. They are
 * plain `android.util.Log` calls, so this file needs no Lustro type.
 */
internal class DemoLogs {
    private val handler = Handler(Looper.getMainLooper())
    private var tick = 0

    private val ticker =
        object : Runnable {
            override fun run() {
                tick++
                // The same line every time: between two sync lines, the Logs tab folds
                // the ticks into one row with a count.
                Log.v(TAG_HEARTBEAT, "tick")
                when (tick % SYNC_EVERY) {
                    0 -> Log.d(TAG_SYNC, "sync ${tick / SYNC_EVERY} started for 3 journals")
                    1 -> Log.i(TAG_SYNC, "sync ${tick / SYNC_EVERY} done: 12 entries in 340 ms")
                    2 -> if (tick % (SYNC_EVERY * 2) == 2) Log.w(TAG_SYNC, "retry 1 of 3 after a timeout")
                    3 -> if (tick % (SYNC_EVERY * 4) == 3) Log.e(TAG_SYNC, "sync failed: java.net.SocketTimeoutException: timeout")
                }
                handler.postDelayed(this, INTERVAL_MS)
            }
        }

    fun start() {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    fun stop() {
        handler.removeCallbacks(ticker)
    }

    /** Writes [count] numbered lines at once, which shows how a poll pages a backlog. */
    fun burst(count: Int) {
        repeat(count) { Log.i(TAG_BURST, "line ${it + 1} of $count") }
    }

    /** Writes the same line [count] times in a row. */
    fun repeatLine(count: Int) {
        repeat(count) { Log.w(TAG_SYNC, "the same warning, again") }
    }

    /** Writes an error with a stack trace: logcat prints each line of it as a line of its own. */
    fun exception() {
        Log.e(TAG_SYNC, "sync crashed", IllegalStateException("journal 7 has no key"))
    }

    private companion object {
        const val TAG_HEARTBEAT = "SampleHeartbeat"
        const val TAG_SYNC = "SampleSync"
        const val TAG_BURST = "SampleBurst"
        const val INTERVAL_MS = 2_000L
        const val SYNC_EVERY = 8
    }
}
