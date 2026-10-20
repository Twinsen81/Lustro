package io.github.twinsen81.lustro.logs

import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugTab

/**
 * The Logs tab (no-op build).
 *
 * Mirrors the `:lustro-logs` [LogsDebugTab] public surface so that the app's
 * release code compiles unchanged. The tab's identity ([id], [title], [icon],
 * [order]) is the same, but it reads no logs, and [handle] returns `null`.
 */
public class LogsDebugTab private constructor() : DebugTab() {
    override val id: String = "logs"
    override val title: String = "Logs"
    override val icon: String = "📜"
    override val order: Int = 20

    /** Returns `null`; the no-op build routes nothing. */
    override fun handle(request: DebugRequest): DebugResponse? = null

    /** Factory for [LogsDebugTab]. */
    public companion object {
        /** The default size of the buffer: 4 MiB, by the size of the lines on the heap. */
        public const val DEFAULT_BUFFER_BYTES: Int = 4 * 1024 * 1024

        /**
         * Creates a [LogsDebugTab].
         *
         * @param bufferBytes the most memory that the held lines may take.
         * @throws IllegalArgumentException when [bufferBytes] is not positive.
         *
         * No-op build: the tab holds no lines, and [bufferBytes] is only checked,
         * so that a value the runtime rejects fails in release too.
         */
        @JvmStatic
        @JvmOverloads
        public fun create(bufferBytes: Int = DEFAULT_BUFFER_BYTES): LogsDebugTab {
            require(bufferBytes > 0) { "bufferBytes must be positive, was $bufferBytes" }
            return LogsDebugTab()
        }
    }
}
