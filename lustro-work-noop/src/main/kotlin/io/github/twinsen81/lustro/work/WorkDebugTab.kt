package io.github.twinsen81.lustro.work

import android.content.Context
import androidx.work.Configuration
import androidx.work.analytics.WorkMetricsInfoRepository
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugTab

/**
 * The WorkManager tab (no-op build).
 *
 * Mirrors the `:lustro-work` [WorkDebugTab] public surface so that the app's
 * release code compiles unchanged. The tab's identity ([id], [title], [icon],
 * [order]) is the same, but [configure] returns the builder as it is, the tab
 * reads nothing, and [handle] returns `null`.
 */
public class WorkDebugTab private constructor() : DebugTab() {
    override val id: String = "work"
    override val title: String = "WorkManager"
    override val icon: String = "⏳"
    override val order: Int = 50

    /** Returns `null`; the no-op build routes nothing. */
    override fun handle(request: DebugRequest): DebugResponse? = null

    /**
     * Returns [builder] as it is.
     *
     * No-op build: the tab sets no listeners, so the app's WorkManager runs with
     * the configuration that the app built.
     */
    public fun configure(builder: Configuration.Builder): Configuration.Builder = builder

    /** Factory for [WorkDebugTab]. */
    public companion object {
        /** The default size of the event buffer: 1 MiB, by the size of the events on the heap. */
        public const val DEFAULT_BUFFER_BYTES: Int = 1024 * 1024

        /**
         * Creates a [WorkDebugTab].
         *
         * @param context a context of the app.
         * @param uniqueNames the unique work names whose work the tab labels with the name.
         * @param bufferBytes the most memory that the held events may take.
         * @throws IllegalArgumentException when [bufferBytes] is not positive.
         *
         * No-op build: the tab keeps nothing, and [bufferBytes] is only checked, so
         * that a value the runtime rejects fails in release too.
         */
        @JvmStatic
        @JvmOverloads
        @Suppress("UNUSED_PARAMETER")
        public fun create(
            context: Context,
            uniqueNames: List<String> = emptyList(),
            bufferBytes: Int = DEFAULT_BUFFER_BYTES,
        ): WorkDebugTab {
            require(bufferBytes > 0) { "bufferBytes must be positive, was $bufferBytes" }
            return WorkDebugTab()
        }

        /**
         * Creates a [WorkDebugTab] that also shows the history that work-analytics keeps.
         *
         * @param metrics the app's repository.
         * @see create
         *
         * No-op build: the tab never reads [metrics].
         */
        @JvmStatic
        @JvmOverloads
        @Suppress("UNUSED_PARAMETER")
        public fun create(
            context: Context,
            metrics: WorkMetricsInfoRepository,
            uniqueNames: List<String> = emptyList(),
            bufferBytes: Int = DEFAULT_BUFFER_BYTES,
        ): WorkDebugTab = create(context, uniqueNames, bufferBytes)
    }
}
