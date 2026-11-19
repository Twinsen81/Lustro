package io.github.twinsen81.lustro.sample

import android.util.Log

/**
 * The sample's own logger, as many apps have one. It writes each line to logcat in
 * chunks that logcat keeps whole, and gives the whole line to the Logs tab through
 * [LustroBootstrap.appendLog]. The debug bootstrap names [TAG] to the tab, so the
 * tab drops the chunks from logcat and shows the line once, in full.
 */
internal object SampleLog {
    const val TAG = "SampleState"

    // logcat keeps at most about 4 KB of a message.
    private const val CHUNK_CHARS = 4_000

    fun d(message: String) {
        message.chunked(CHUNK_CHARS).forEach { Log.d(TAG, it) }
        LustroBootstrap.appendLog(Log.DEBUG, TAG, message, null)
    }
}
