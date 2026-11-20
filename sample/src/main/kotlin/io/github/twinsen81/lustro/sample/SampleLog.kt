package io.github.twinsen81.lustro.sample

import android.util.Log

/**
 * The sample's own logger, as many apps have one. It gives the whole line to the
 * Logs tab through [LustroBootstrap.appendLog], and writes it to logcat in chunks
 * that logcat keeps whole. The debug bootstrap names [TAG] to the tab, so the tab
 * drops the chunks from logcat and shows the line once, in full. It appends first:
 * the tab drops the logcat lines of a tag from its first append on, so the chunks
 * of the first line drop too.
 */
internal object SampleLog {
    const val TAG = "SampleState"

    // logcat keeps at most about 4 KB of a message.
    private const val CHUNK_CHARS = 4_000

    fun d(message: String) {
        LustroBootstrap.appendLog(Log.DEBUG, TAG, message, null)
        message.chunked(CHUNK_CHARS).forEach { Log.d(TAG, it) }
    }
}
