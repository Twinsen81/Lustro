package io.github.twinsen81.lustro.work.internal

import android.content.Context
import android.util.Log
import androidx.work.Configuration
import androidx.work.ExecutionEventListener
import androidx.work.ScheduleEventListener
import androidx.work.WorkManager
import io.github.twinsen81.lustro.escapeForJson

/**
 * The app's WorkManager as the tab sees it: the [events] that its listeners
 * record, the [metrics] of work-analytics when the app has them, and the
 * WorkManager instance, which the tab asks for only once the app has initialized it.
 *
 * [unsupported] says why the app's WorkManager can't be read, or is `null`; when
 * it is set, the tab calls nothing in `androidx.work`.
 */
internal class WorkSource(
    context: Context,
    val uniqueNames: List<String>,
    val events: WorkEvents,
    val metrics: WorkMetrics?,
    val unsupported: String?,
    private val bufferBytes: Int,
    private val initialized: () -> WorkManager? = { if (WorkManager.isInitialized()) WorkManager.getInstance(context) else null },
) {
    @Volatile
    private var configured = false

    // Why configure() set no listeners although the app's WorkManager is 2.12.0 or newer.
    @Volatile
    private var configureFailure: String? = null

    /**
     * Sets the tab's listeners on [builder], each forwarding to the listener that the
     * app set on it before, and returns [builder]. Leaves [builder] as it is when the
     * app's WorkManager can't take them.
     */
    fun configure(builder: Configuration.Builder): Configuration.Builder {
        if (unsupported != null) return builder
        try {
            val unknown =
                WorkSupport.unknownMethods(ExecutionEventListener::class.java, ExecutionRecorder::class.java) +
                    WorkSupport.unknownMethods(ScheduleEventListener::class.java, ScheduleRecorder::class.java)
            if (unknown.isNotEmpty()) {
                fail(
                    "The app's WorkManager has listener methods that this version of the tab does not forward " +
                        "(${unknown.joinToString()}), so the tab sets no listeners: update Lustro",
                )
                return builder
            }
            // The getters are on Configuration only: building one costs nothing and has
            // no effect outside the object, which is dropped.
            val built = builder.build()
            val appExecution = built.getExecutionEventListener().let { if (it is ExecutionRecorder) it.app else it }
            val appSchedule = built.getScheduleEventListener().let { if (it is ScheduleRecorder) it.app else it }
            builder.setExecutionEventListener(ExecutionRecorder(events, appExecution))
            builder.setScheduleEventListener(ScheduleRecorder(events, appSchedule))
            configureFailure = null
            configured = true
        } catch (e: LinkageError) {
            fail("The app's WorkManager does not have a call that the tab makes: $e")
        }
        return builder
    }

    /** The app's WorkManager, or `null` while the app has not initialized it: the tab never initializes it. */
    fun workManager(): WorkManager? = initialized()

    /** The `state` member of the envelopes, for [workManager] as [workManager] returned it. */
    fun stateJson(workManager: WorkManager?): String {
        val (listeners, reason) = listeners(workManager)
        return buildString {
            append("{\"listeners\":\"").append(listeners).append('"')
            if (reason != null) append(",\"reason\":\"").append(reason.escapeForJson()).append('"')
            append(",\"initialized\":").append(workManager != null)
            append(",\"metrics\":").append(metrics != null)
            append(",\"uniqueNames\":")
            WorkJson.appendStrings(this, uniqueNames)
            append(",\"bufferBytes\":").append(bufferBytes)
            append('}')
        }
    }

    // installed, missing, or unsupported, and why when not installed. Once WorkManager
    // runs, its live Configuration decides: the app may have built another one.
    private fun listeners(workManager: WorkManager?): Pair<String, String?> {
        configureFailure?.let { return UNSUPPORTED to it }
        if (workManager != null) {
            val live =
                try {
                    workManager.configuration.getExecutionEventListener()
                } catch (e: LinkageError) {
                    return UNSUPPORTED to "The app's WorkManager does not have a call that the tab makes: $e"
                }
            return when {
                live is ExecutionRecorder && live.events === events -> INSTALLED to null
                configured -> MISSING to RUNS_ANOTHER
                else -> MISSING to NOT_CONFIGURED
            }
        }
        return if (configured) INSTALLED to null else MISSING to NOT_CONFIGURED
    }

    private fun fail(reason: String) {
        configureFailure = reason
        Log.w(WorkEvents.TAG, reason)
    }

    private companion object {
        const val INSTALLED = "installed"
        const val MISSING = "missing"
        const val UNSUPPORTED = "unsupported"

        const val NOT_CONFIGURED =
            "WorkManager runs with a Configuration that did not go through WorkDebugTab.configure(), so the tab records no events. " +
                "If the app's Configuration.Provider calls configure(), WorkManagerInitializer is still in the manifest: it " +
                "initializes WorkManager with a default Configuration at startup and never asks the provider. Remove it from " +
                "the manifest, or call configure() where the app builds its Configuration"
        const val RUNS_ANOTHER =
            "configure() set the tab's listeners on a Configuration.Builder, but WorkManager runs with other listeners: the " +
                "app set its own listener on the builder after configure(), or built another Configuration. Call configure() " +
                "last, right before build(), on the Configuration that WorkManager gets"
    }
}
