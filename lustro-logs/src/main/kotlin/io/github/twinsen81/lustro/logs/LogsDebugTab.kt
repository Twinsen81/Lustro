package io.github.twinsen81.lustro.logs

import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugTab
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
 */
public class LogsDebugTab private constructor(buffer: LogBuffer, private val reader: LogcatReader) : DebugTab() {
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

    /** Factory for [LogsDebugTab]. */
    public companion object {
        /** The default size of the buffer: 4 MiB, by the size of the lines on the heap. */
        public const val DEFAULT_BUFFER_BYTES: Int = 4 * 1024 * 1024

        /**
         * Creates a [LogsDebugTab].
         *
         * @param bufferBytes the most memory that the held lines may take, by the
         *   size of their text on the heap (two bytes a character) and a fixed
         *   cost for each line. When a new line goes over it, the oldest lines go.
         *   The default, [DEFAULT_BUFFER_BYTES], holds about 12,000 lines of 100
         *   characters.
         * @throws IllegalArgumentException when [bufferBytes] is not positive.
         */
        @JvmStatic
        @JvmOverloads
        public fun create(bufferBytes: Int = DEFAULT_BUFFER_BYTES): LogsDebugTab {
            require(bufferBytes > 0) { "bufferBytes must be positive, was $bufferBytes" }
            val buffer = LogBuffer(bufferBytes)
            return LogsDebugTab(buffer, LogcatReader(buffer))
        }

        /** Creates a tab over [buffer] and [reader], which tests give a logcat of their own. */
        internal fun create(buffer: LogBuffer, reader: LogcatReader): LogsDebugTab = LogsDebugTab(buffer, reader)
    }
}
