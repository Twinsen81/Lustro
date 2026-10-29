package io.github.twinsen81.lustro.sample

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExecutionEventListener
import androidx.work.ExperimentalEventsApi
import androidx.work.ListenableWorker
import androidx.work.ScheduleEventListener
import androidx.work.WorkInfo
import androidx.work.analytics.ExperimentalWorkMetricsApi
import androidx.work.analytics.WorkMetricsInfo
import androidx.work.analytics.WorkMetricsInfoRepository
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The events that make work-analytics 2.12.0 insert a period it closed before, sent as WorkManager sends them. */
@OptIn(ExperimentalEventsApi::class, ExperimentalWorkMetricsApi::class)
@RunWith(RobolectricTestRunner::class)
class MetricsListenersTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val repository = WorkMetricsInfoRepository(app) { it.run() }
    private val listeners = MetricsListeners(repository)

    @Test
    fun `a periodic worker that threw does not end the process at its next run`() {
        val sync = info(periodic = true)

        assertThrows(SQLiteConstraintException::class.java) { periodicThatThrew(repository, sync) }
        periodicThatThrew(listeners, info(periodic = true))
    }

    @Test
    fun `a run that work-analytics closed and WorkManager runs again does not end the process`() {
        val first = info(periodic = false)
        val history = offMain {
            listeners.onEnqueued(first)
            listeners.onStarted(first)
            listeners.onFinished(ListenableWorker.Result.success(), first)
            // The process died before WorkManager saved the result, so it runs the work again.
            listeners.onStarted(first)
            listeners.onFinished(ListenableWorker.Result.success(), first)
            repository.getWorkMetricsInfoById(first.id)
        }

        assertEquals(listOf(WorkMetricsInfo.State.SUCCEEDED), history.map { it.state })
    }

    private fun <L> periodicThatThrew(listener: L, sync: WorkInfo) where L : ExecutionEventListener, L : ScheduleEventListener =
        offMain {
            listener.onEnqueued(sync)
            listener.onStarted(sync)
            listener.onException(IllegalStateException("the sync threw"), sync)
            listener.onStarted(sync)
            listener.onFinished(ListenableWorker.Result.success(), sync)
        }

    private fun info(periodic: Boolean): WorkInfo =
        WorkInfo(
            UUID.randomUUID(), WorkInfo.State.RUNNING, setOf(DemoWork.TAG), Data.EMPTY, Data.EMPTY, 1, 0, Constraints.NONE, 0L,
            if (periodic) WorkInfo.PeriodicityInfo(PERIOD_MS, PERIOD_MS) else null, Long.MAX_VALUE,
            WorkInfo.STOP_REASON_NOT_STOPPED, SyncWorker::class.java.name,
        )

    // Room refuses the main thread, which is the test's thread under Robolectric.
    private fun <T> offMain(block: suspend () -> T): T {
        var result: Result<T>? = null
        val thread = Thread { result = runCatching { runBlocking { block() } } }
        thread.start()
        thread.join()
        return result!!.getOrThrow()
    }

    private companion object {
        const val PERIOD_MS = 15 * 60 * 1000L
    }
}
