package io.github.twinsen81.lustro.sample

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExecutionEventListener
import androidx.work.ExistingWorkPolicy
import androidx.work.ExperimentalEventsApi
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.ScheduleEventListener
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.analytics.ExperimentalWorkMetricsApi
import androidx.work.analytics.WorkMetricsInfoRepository
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The sample's WorkManager work, which the WorkManager tab shows: a periodic sync,
 * a chain of three steps, a chain whose second step fails, an upload that retries
 * twice, a worker that throws, a slow worker with a delay, and work that waits for
 * Wi-Fi and a charger.
 *
 * The app initializes WorkManager on demand ([SampleApplication] is a
 * `Configuration.Provider`), with work-analytics' repository as its own listeners,
 * so that the tab can add its listeners in front of them.
 */
@OptIn(ExperimentalEventsApi::class, ExperimentalWorkMetricsApi::class)
internal class DemoWork(private val context: Context) {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /** How many work requests WorkManager has in each state, while the screen collects it. */
    val counts: Flow<Map<WorkInfo.State, Int>> =
        workManager.getWorkInfosFlow(WorkQuery.fromStates(WorkInfo.State.entries))
            .map { infos -> infos.groupingBy { it.state }.eachCount() }

    fun enqueueAll(): Int {
        workManager.enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).addTag(TAG).build(),
        )
        workManager.beginUniqueWork(CHAIN, ExistingWorkPolicy.REPLACE, step(1, tag = "chain"))
            .then(step(2, tag = "chain"))
            .then(step(3, tag = "chain"))
            .enqueue()
        workManager.beginUniqueWork(FAILING_CHAIN, ExistingWorkPolicy.REPLACE, step(1, tag = "failing-chain"))
            .then(step(2, tag = "failing-chain", fail = true))
            .then(step(3, tag = "failing-chain"))
            .enqueue()
        workManager.enqueueUniqueWork(
            RETRY,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<UploadWorker>().setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS).addTag(TAG).build(),
        )
        workManager.enqueueUniqueWork(THROWING, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<CrashingWorker>().addTag(TAG).build())
        return 9
    }

    /** A worker that starts after 20 s and runs for 15 s: time to send the app to the background, or to stop its process. */
    fun enqueueDelayed() {
        workManager.enqueueUniqueWork(
            DELAYED,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SlowWorker>().setInitialDelay(20, TimeUnit.SECONDS).addTag(TAG).build(),
        )
    }

    /** Work that stays enqueued until the phone is on Wi-Fi and charging: something to cancel from the console. */
    fun enqueueConstrained() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresCharging(true).build()
        workManager.enqueueUniqueWork(
            CONSTRAINED,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).addTag(TAG).build(),
        )
    }

    fun cancelPeriodic() {
        workManager.cancelUniqueWork(PERIODIC)
    }

    private fun step(step: Int, tag: String, fail: Boolean = false): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<ChainStepWorker>().setInputData(workDataOf(STEP to step, FAIL to fail)).addTag(tag).build()

    companion object {
        const val PERIODIC = "demo-periodic"
        const val CHAIN = "demo-chain"
        const val FAILING_CHAIN = "demo-chain-failing"
        const val RETRY = "demo-retry"
        const val THROWING = "demo-throwing"
        const val DELAYED = "demo-delayed"
        const val CONSTRAINED = "demo-constrained"

        /** The unique names that both bootstraps register, so the tab labels their work. */
        val UNIQUE_NAMES: List<String> = listOf(PERIODIC, CHAIN, FAILING_CHAIN, RETRY, THROWING, DELAYED, CONSTRAINED)

        const val TAG = "demo"
        const val STEP = "step"
        const val FAIL = "fail"

        @Volatile
        private var metrics: WorkMetricsInfoRepository? = null

        /** work-analytics' repository: the app's own WorkManager listeners, which keep each run's times on disk. */
        fun metrics(context: Context): WorkMetricsInfoRepository =
            metrics ?: synchronized(this) {
                metrics ?: WorkMetricsInfoRepository(context.applicationContext).also { metrics = it }
            }

        /** The app's own WorkManager configuration, before the bootstrap passes it to Lustro. */
        fun configuration(context: Context): Configuration.Builder {
            val listeners = MetricsListeners(metrics(context))
            return Configuration.Builder()
                .setMinimumLoggingLevel(Log.INFO)
                .setExecutionEventListener(listeners)
                .setScheduleEventListener(listeners)
        }
    }
}

/**
 * work-analytics' [repository] as the app's listeners, with one exception caught. In
 * work-analytics 2.12.0, cancelling work while it runs ends the process: `onCancelled`
 * closes the run's period, and `onStopped`, which comes after it, inserts the same period
 * again and fails with a `SQLiteConstraintException` on WorkManager's thread. The console's
 * `POST work/cancel` cancels running work as the app's own calls do, so the sample catches
 * that exception; the period that `onCancelled` closed stays as it is.
 */
@OptIn(ExperimentalEventsApi::class, ExperimentalWorkMetricsApi::class)
internal class MetricsListeners(private val repository: WorkMetricsInfoRepository) :
    ExecutionEventListener by repository,
    ScheduleEventListener by repository {
    override suspend fun onStopped(stopReason: Int, workInfo: WorkInfo) {
        try {
            repository.onStopped(stopReason, workInfo)
        } catch (e: SQLiteConstraintException) {
            Log.w("LustroSample", "work-analytics could not record that ${workInfo.id} stopped", e)
        }
    }
}

/** The periodic sync: succeeds with the time it ran. */
internal class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        Thread.sleep(WORK_MS)
        return Result.success(workDataOf("syncedAt" to System.currentTimeMillis(), "items" to 12))
    }
}

/** One step of a chain. Its input also holds the outputs of the steps before it. */
internal class ChainStepWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val step = inputData.getInt(DemoWork.STEP, 0)
        Thread.sleep(WORK_MS)
        if (inputData.getBoolean(DemoWork.FAIL, false)) return Result.failure(workDataOf("reason" to "step $step failed on purpose"))
        return Result.success(workDataOf("doneStep" to step))
    }
}

/** Asks for a retry twice, then succeeds. */
internal class UploadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result =
        if (runAttemptCount < 2) Result.retry() else Result.success(workDataOf("attempts" to runAttemptCount + 1))
}

/** Throws, as a worker with a bug does. */
internal class CrashingWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = error("the journal store is corrupt (demo)")
}

/** Runs for 15 s. */
internal class SlowWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        Thread.sleep(SLOW_MS)
        return Result.success()
    }
}

private const val WORK_MS = 300L
private const val SLOW_MS = 15_000L
