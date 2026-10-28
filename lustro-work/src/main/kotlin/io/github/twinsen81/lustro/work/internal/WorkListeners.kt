package io.github.twinsen81.lustro.work.internal

import androidx.work.ExecutionEventListener
import androidx.work.ListenableWorker
import androidx.work.ScheduleEventListener
import androidx.work.WorkInfo

/*
 * The listeners that the tab sets on the app's Configuration.Builder. Each one
 * records the event and then calls the app's own listener, if the app set one, with
 * the same arguments. Recording never throws (WorkEvents.record), and what the
 * app's listener throws reaches WorkManager as it would without the tab.
 */

/** Records the execution events of [events], then calls [app]. */
internal class ExecutionRecorder(val events: WorkEvents, val app: ExecutionEventListener?) : ExecutionEventListener {
    override suspend fun onStarted(workInfo: WorkInfo) {
        events.record("started", workInfo)
        app?.onStarted(workInfo)
    }

    override suspend fun onStopped(stopReason: Int, workInfo: WorkInfo) {
        events.record("stopped", workInfo) {
            append(",\"stopReason\":")
            WorkJson.appendStopReason(this, stopReason)
        }
        app?.onStopped(stopReason, workInfo)
    }

    override suspend fun onFinished(result: ListenableWorker.Result, workInfo: WorkInfo) {
        events.record("finished", workInfo) {
            append(",\"result\":\"").append(WorkJson.resultKind(result)).append('"')
            append(",\"output\":")
            WorkJson.appendData(this, result.outputData)
        }
        app?.onFinished(result, workInfo)
    }

    override suspend fun onException(throwable: Throwable, workInfo: WorkInfo) {
        events.record("exception", workInfo) {
            append(",\"exception\":")
            WorkJson.appendException(this, throwable)
        }
        app?.onException(throwable, workInfo)
    }
}

/** Records the scheduling events of [events], then calls [app]. */
internal class ScheduleRecorder(val events: WorkEvents, val app: ScheduleEventListener?) : ScheduleEventListener {
    override suspend fun onEnqueued(workInfo: WorkInfo) {
        events.record("enqueued", workInfo)
        app?.onEnqueued(workInfo)
    }

    override suspend fun onUpdated(oldWorkInfo: WorkInfo, updatedWorkInfo: WorkInfo) {
        events.record("updated", updatedWorkInfo) { append(",\"previousGeneration\":").append(oldWorkInfo.generation) }
        app?.onUpdated(oldWorkInfo, updatedWorkInfo)
    }

    override suspend fun onUnblocked(workInfo: WorkInfo) {
        events.record("unblocked", workInfo)
        app?.onUnblocked(workInfo)
    }

    override suspend fun onCancelled(workInfo: WorkInfo) {
        events.record("cancelled", workInfo)
        app?.onCancelled(workInfo)
    }

    override suspend fun onPrerequisiteFailed(workInfo: WorkInfo) {
        events.record("prerequisiteFailed", workInfo)
        app?.onPrerequisiteFailed(workInfo)
    }
}
