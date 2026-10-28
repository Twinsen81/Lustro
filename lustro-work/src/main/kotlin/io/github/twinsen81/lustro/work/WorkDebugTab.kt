package io.github.twinsen81.lustro.work

import android.content.Context
import androidx.work.Configuration
import androidx.work.analytics.WorkMetricsInfoRepository
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugTab
import io.github.twinsen81.lustro.work.internal.WorkEvents
import io.github.twinsen81.lustro.work.internal.WorkMetrics
import io.github.twinsen81.lustro.work.internal.WorkRoutes
import io.github.twinsen81.lustro.work.internal.WorkSource
import io.github.twinsen81.lustro.work.internal.WorkSupport

/**
 * The WorkManager tab: the app's work, the events of each run, and cancel and
 * prune, under `/api/v1/work/`. It needs WorkManager 2.12.0 or newer in the app.
 *
 * Register it from debug code, and pass the app's WorkManager configuration
 * through [configure], so that the tab gets WorkManager's events:
 *
 * ```
 * // src/main
 * class App : Application(), Configuration.Provider {
 *     override val workManagerConfiguration: Configuration
 *         get() = LustroBootstrap.workManagerConfiguration(Configuration.Builder()).build()
 * }
 *
 * // src/debug, LustroBootstrap
 * val work = WorkDebugTab.create(app, uniqueNames = listOf("sync"))
 * fun workManagerConfiguration(builder: Configuration.Builder) = work.configure(builder)
 * Lustro.builder(app).addTab(work).build().start()
 * ```
 *
 * The listeners go into the app's own `Configuration`, so the app initializes
 * WorkManager on demand: it implements `Configuration.Provider` or calls
 * `WorkManager.initialize`, and removes `WorkManagerInitializer` from the
 * manifest. Without that, the tab shows the work without events.
 *
 * The tab reads the work through WorkManager's public API: its state, tags,
 * attempts, output, progress, constraints, and schedule. It knows the unique name
 * of work only for the names that the app lists in `uniqueNames`, since
 * `WorkInfo` does not have it. It asks for the WorkManager instance only after
 * the app has initialized it, so it never changes when WorkManager starts.
 *
 * The events of each run (enqueued, unblocked, updated, cancelled, prerequisite
 * failed, started, stopped, finished, and exception), with the result, its
 * output, the stop reason, and the exception's stack trace, are kept on the
 * heap, in a buffer of `bufferBytes`, from the moment WorkManager starts. They
 * end with the process: the events of a process that the system started in the
 * background and stopped before a client asked are lost. With a
 * `WorkMetricsInfoRepository` of work-analytics, which keeps the times and
 * attempts of each run on disk, the tab also shows that history.
 *
 * `POST cancel` cancels work by its id, unique name, or tag, and `POST prune`
 * deletes finished work, as `WorkManager.pruneWork()` does. Every client that has
 * the token can do both.
 *
 * `GET /api/v1/work/_schema` describes the routes. The tab has no console view
 * yet, so it is not in the tab bar.
 */
public class WorkDebugTab private constructor(private val source: WorkSource) : DebugTab() {
    override val id: String = "work"
    override val title: String = "WorkManager"
    override val icon: String = "⏳"
    override val order: Int = 50

    // API only until the tab has a console view; _meta lists it.
    override val showInTabBar: Boolean = false

    private val routes = WorkRoutes(source)

    /** Serves the routes under `/api/v1/work/`; the static `work.openapi.json` describes them. */
    override fun handle(request: DebugRequest): DebugResponse? = routes.handle(request)

    /**
     * Sets the tab's event listeners on [builder] and returns it, for the app to
     * call `build()` on. Call it last, after the app sets its own listeners: each of
     * the tab's listeners records the event and then calls the app's listener with
     * it, and what the app's listener throws reaches WorkManager as before. A
     * work-analytics repository that the app set as its listeners keeps working.
     *
     * Returns [builder] as it is when the app's WorkManager is older than 2.12.0,
     * or has listener methods that this version of the tab does not know; the tab
     * then shows the work without events, and says why in the routes' `state`.
     */
    public fun configure(builder: Configuration.Builder): Configuration.Builder = source.configure(builder)

    /** Factory for [WorkDebugTab]. */
    public companion object {
        /** The default size of the event buffer: 1 MiB, by the size of the events on the heap. */
        public const val DEFAULT_BUFFER_BYTES: Int = 1024 * 1024

        /**
         * Creates a [WorkDebugTab].
         *
         * @param context a context of the app. The tab keeps its application
         *   context, for `WorkManager.getInstance`.
         * @param uniqueNames the unique work names whose work the tab labels with
         *   the name. `GET items?name=` finds the work of any name.
         * @param bufferBytes the most memory that the held events may take, by the
         *   size of their JSON on the heap (two bytes a character) and a fixed cost
         *   for each event. When a new event goes over it, the oldest events go.
         * @throws IllegalArgumentException when [bufferBytes] is not positive.
         */
        @JvmStatic
        @JvmOverloads
        public fun create(
            context: Context,
            uniqueNames: List<String> = emptyList(),
            bufferBytes: Int = DEFAULT_BUFFER_BYTES,
        ): WorkDebugTab = build(context, uniqueNames, bufferBytes, metrics = null)

        /**
         * Creates a [WorkDebugTab] that also shows the history that work-analytics
         * keeps: the times, attempts, and stop reasons of each run, also of the runs
         * of earlier processes.
         *
         * @param metrics the app's repository, which the app also sets as its
         *   execution and schedule listeners, before [configure].
         * @see create
         */
        @JvmStatic
        @JvmOverloads
        public fun create(
            context: Context,
            metrics: WorkMetricsInfoRepository,
            uniqueNames: List<String> = emptyList(),
            bufferBytes: Int = DEFAULT_BUFFER_BYTES,
        ): WorkDebugTab = build(context, uniqueNames, bufferBytes, WorkMetrics(metrics))

        private fun build(context: Context, uniqueNames: List<String>, bufferBytes: Int, metrics: WorkMetrics?): WorkDebugTab {
            require(bufferBytes > 0) { "bufferBytes must be positive, was $bufferBytes" }
            val app = context.applicationContext ?: context
            val source =
                WorkSource(
                    context = app,
                    uniqueNames = uniqueNames.distinct(),
                    events = WorkEvents(bufferBytes),
                    metrics = metrics,
                    unsupported = WorkSupport.missing(WorkDebugTab::class.java.classLoader),
                    bufferBytes = bufferBytes,
                )
            return WorkDebugTab(source)
        }

        /** Creates a tab over [source], which tests build with a clock and a support check of their own. */
        internal fun create(source: WorkSource): WorkDebugTab = WorkDebugTab(source)
    }
}
