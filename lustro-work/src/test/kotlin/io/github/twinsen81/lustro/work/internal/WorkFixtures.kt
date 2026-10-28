package io.github.twinsen81.lustro.work.internal

import android.content.Context
import android.util.Log
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ExecutionEventListener
import androidx.work.ListenableWorker
import androidx.work.ScheduleEventListener
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import org.json.JSONObject

/** The WorkManager, the workers, and the requests that the route tests use. */
internal object WorkFixtures {
    /** A fixed clock, so that event times are the same in every run. */
    const val NOW: Long = 1_792_419_740_000L

    fun source(
        context: Context,
        uniqueNames: List<String> = emptyList(),
        metrics: WorkMetrics? = null,
        unsupported: String? = null,
        bufferBytes: Int = 1024 * 1024,
        clock: () -> Long = { NOW },
        initialized: (() -> WorkManager?)? = null,
    ): WorkSource {
        // As after Lustro.start(), which turns recording on.
        val events = WorkEvents(bufferBytes, clock).apply { recording = true }
        return if (initialized == null) {
            WorkSource(context, uniqueNames, events, metrics, unsupported, bufferBytes)
        } else {
            WorkSource(context, uniqueNames, events, metrics, unsupported, bufferBytes, initialized)
        }
    }

    /**
     * Initializes the test WorkManager with a configuration that went through
     * [source]'s configure(), on executors that run each step on the calling
     * thread, so a worker without constraints has run when enqueue() returns.
     */
    fun initialize(
        context: Context,
        source: WorkSource?,
        execution: ExecutionEventListener? = null,
        schedule: ScheduleEventListener? = null,
        clock: androidx.work.Clock = androidx.work.Clock { NOW },
    ) {
        val builder =
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setExecutor(SynchronousExecutor())
                .setTaskExecutor(SynchronousExecutor())
                // Enqueue times, and the next schedule times that WorkManager derives from them.
                .setClock(clock)
        execution?.let { builder.setExecutionEventListener(it) }
        schedule?.let { builder.setScheduleEventListener(it) }
        source?.configure(builder)
        WorkManagerTestInitHelper.initializeTestWorkManager(context, builder.build())
    }

    fun get(routes: WorkRoutes, path: String, vararg params: Pair<String, String>): DebugResponse =
        routes.handle(DebugRequest(path, "GET", params.groupBy({ it.first }, { it.second })))
            ?: error("$path is not a route")

    fun post(routes: WorkRoutes, path: String, body: JSONObject?): DebugResponse =
        routes.handle(DebugRequest(path, "POST", body = body?.toString()?.toByteArray()))
            ?: error("$path is not a route")

    fun DebugResponse.text(): String = body.toString(Charsets.UTF_8)

    fun DebugResponse.json(): JSONObject = JSONObject(text())

    /** Records every event that it gets, as `<type> <id>`, as an app's own listener would. */
    class AppListeners : ExecutionEventListener, ScheduleEventListener {
        val seen: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

        override suspend fun onStarted(workInfo: WorkInfo) {
            seen += "started ${workInfo.id}"
        }

        override suspend fun onStopped(stopReason: Int, workInfo: WorkInfo) {
            seen += "stopped ${workInfo.id} $stopReason"
        }

        override suspend fun onFinished(result: ListenableWorker.Result, workInfo: WorkInfo) {
            seen += "finished ${workInfo.id}"
        }

        override suspend fun onException(throwable: Throwable, workInfo: WorkInfo) {
            seen += "exception ${workInfo.id}"
        }

        override suspend fun onEnqueued(workInfo: WorkInfo) {
            seen += "enqueued ${workInfo.id}"
        }

        override suspend fun onUpdated(oldWorkInfo: WorkInfo, updatedWorkInfo: WorkInfo) {
            seen += "updated ${updatedWorkInfo.id}"
        }

        override suspend fun onUnblocked(workInfo: WorkInfo) {
            seen += "unblocked ${workInfo.id}"
        }

        override suspend fun onCancelled(workInfo: WorkInfo) {
            seen += "cancelled ${workInfo.id}"
        }

        override suspend fun onPrerequisiteFailed(workInfo: WorkInfo) {
            seen += "prerequisiteFailed ${workInfo.id}"
        }
    }
}

/** Succeeds with its input's step in the output. */
class StepWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val step = inputData.getInt("step", 0)
        if (inputData.getBoolean("fail", false)) return Result.failure(workDataOf("reason" to "step $step failed"))
        return Result.success(workDataOf("step" to step, "big" to Long.MAX_VALUE, "ratio" to 0.5, "names" to arrayOf("a", "b")))
    }
}

/** Asks for a retry until its third attempt. */
class RetryWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = if (runAttemptCount < 2) Result.retry() else Result.success()
}

/** Throws, as a worker with a bug does. */
class ThrowingWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = throw IllegalStateException("the journal store is corrupt")
}

/** Waits until it is stopped. */
class WaitingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        kotlinx.coroutines.awaitCancellation()
    }
}
