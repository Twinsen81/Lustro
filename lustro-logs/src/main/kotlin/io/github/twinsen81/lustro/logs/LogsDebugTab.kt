package io.github.twinsen81.lustro.logs

import android.os.Process
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugTab
import io.github.twinsen81.lustro.logs.internal.AppendedLine
import io.github.twinsen81.lustro.logs.internal.LogBuffer
import io.github.twinsen81.lustro.logs.internal.LogRoutes
import io.github.twinsen81.lustro.logs.internal.LogcatReader
import io.github.twinsen81.lustro.logs.internal.LogsView

/**
 * The Logs tab: the app's own logcat, with a live tail, filters, and a plain-text
 * export, under `/api/v1/logs/`. The app gives it no data source: the tab runs
 * `logcat` for its own process.
 *
 * Register it from debug code, as the Network tab:
 *
 * ```
 * Lustro.builder(app).addTab(LogsDebugTab.create()).build().start()
 * ```
 *
 * The tab reads logcat from `Lustro.start()` to `Lustro.stop()`, also while the
 * app is in the background. It starts with the lines that the process wrote
 * before, from its start. It holds the newest lines in a buffer of
 * [bufferBytes][create] on the app's heap, and folds a line that repeats the one
 * before into a count.
 *
 * In the console, the tab shows a live tail of the log, with filters for the
 * level, the tag, and the message. `GET /api/v1/logs/_schema` describes its
 * routes.
 *
 * logcat keeps at most about 4 KB of a message. When the app has a logger of its
 * own, the logger can also give its lines to [append], in full, and name their
 * tags with `appendedTags` of [create], so that the tab drops the cut copies
 * that come from logcat:
 *
 * ```
 * val logs = LogsDebugTab.create(appendedTags = { it == "MyApp" })
 * // In the app's logger, after it writes the line to logcat:
 * logs.append(Log.DEBUG, "MyApp", message)
 * ```
 */
public class LogsDebugTab private constructor(private val buffer: LogBuffer, private val reader: LogcatReader) : DebugTab() {
    override val id: String = "logs"
    override val title: String = "Logs"
    override val icon: String = "📜"
    override val order: Int = 20

    private val routes = LogRoutes(buffer, reader)

    /** The view's static shell; `logs.js` and `logs.css` in the assets fill and style it. */
    override fun renderContent(): String = LogsView.HTML

    /** Serves the routes under `/api/v1/logs/`; the static `logs.openapi.json` describes them. */
    override fun handle(request: DebugRequest): DebugResponse? = routes.handle(request)

    /** Starts reading logcat. */
    override fun onStart() {
        reader.start()
    }

    /** Ends the logcat process. The buffer keeps its lines, and the next start reads on from the newest one. */
    override fun onStop() {
        reader.stop()
    }

    private val maxAppendedChars = AppendedLine.maxChars(buffer.maxBytes)

    /**
     * Adds a line that the app's own logger writes, with its whole text, to the
     * lines from logcat. The console, `GET entries`, and `GET text` show it as
     * one line, with the time, the process, and the thread of this call.
     *
     * The text is [message] and, after a line break, the stack trace of
     * [throwable], as `Log.e(tag, message, throwable)` writes them. It keeps at
     * most [MAX_APPENDED_CHARS] characters and, in a small buffer, at most the
     * characters that take an eighth of `bufferBytes`, so that one line can't
     * empty the buffer. A longer text ends with `… cut (N more characters)`.
     *
     * Safe to call from any thread, also before `Lustro.start()` and after
     * `Lustro.stop()`. It does not write to logcat: the logger does that itself,
     * when it does. The line goes to the buffer at once. A logcat line goes in
     * when the reader reads it: usually some milliseconds after it was written,
     * but much later at the app's start, when the reader first reads the lines of
     * the process from its start, and after a stop and a start. So the buffer can
     * hold an appended line before logcat lines that are older, and the times of
     * the lines are not always in order.
     *
     * @param priority the level, as an `android.util.Log` priority, such as
     *   `Log.DEBUG`. `Log.ASSERT` shows as F, as in logcat.
     */
    @JvmOverloads
    public fun append(priority: Int, tag: String, message: String, throwable: Throwable? = null) {
        val line =
            AppendedLine.of(
                priority,
                tag,
                message,
                throwable,
                maxAppendedChars,
                at = System.currentTimeMillis(),
                pid = Process.myPid(),
                tid = Process.myTid(),
            )
        buffer.append(line)
    }

    /** Factory for [LogsDebugTab]. */
    public companion object {
        /** The default size of the buffer: 4 MiB, by the size of the lines on the heap. */
        public const val DEFAULT_BUFFER_BYTES: Int = 4 * 1024 * 1024

        /** The most characters that one [append]ed line keeps, with its stack trace: 65,536. */
        public const val MAX_APPENDED_CHARS: Int = AppendedLine.MAX_CHARS

        /**
         * Creates a [LogsDebugTab].
         *
         * @param bufferBytes the most memory that the held lines may take, by the
         *   size of their text on the heap (two bytes a character) and a fixed
         *   cost for each line. When a new line goes over it, the oldest lines go.
         *   The default, [DEFAULT_BUFFER_BYTES], holds about 12,000 lines of 100
         *   characters.
         * @param appendedTags whether the app's logger also gives the lines of a
         *   tag to [append]. The tab then drops the logcat lines of that tag, so
         *   that each line shows once, in full. Only the lines written from this
         *   call on: the older ones come from logcat, because the app could not
         *   append them yet. Create the tab before the logger writes its first
         *   line. For a fixed set of tags, pass `setOf("A", "B")::contains`. The
         *   reader thread calls it for each logcat line, so keep it fast. The
         *   default drops no line.
         * @throws IllegalArgumentException when [bufferBytes] is not positive.
         */
        @JvmStatic
        @JvmOverloads
        public fun create(
            bufferBytes: Int = DEFAULT_BUFFER_BYTES,
            appendedTags: (tag: String) -> Boolean = { false },
        ): LogsDebugTab {
            require(bufferBytes > 0) { "bufferBytes must be positive, was $bufferBytes" }
            val buffer = LogBuffer(bufferBytes)
            val reader = LogcatReader(buffer, appendedTags = appendedTags, appendedSince = System.currentTimeMillis())
            return LogsDebugTab(buffer, reader)
        }

        /** Creates a tab over [buffer] and [reader], which tests give a logcat of their own. */
        internal fun create(buffer: LogBuffer, reader: LogcatReader): LogsDebugTab = LogsDebugTab(buffer, reader)
    }
}
