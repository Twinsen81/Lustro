package io.github.twinsen81.lustro.work.internal

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.analytics.WorkMetricsInfoRepository
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import io.github.twinsen81.lustro.work.internal.WorkFixtures.text
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the golden fixtures of the WorkManager routes to what the routes write, so the
 * fixtures that clients validate against can't drift from the server. A cursor holds
 * the process's epoch, a stack trace the test runner's frames, and work-analytics
 * takes its times from the system clock, so those values are left out of the comparison.
 */
@RunWith(RobolectricTestRunner::class)
class WorkGoldenTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() {
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun `the golden items, item, events, and metrics fixtures are what the routes write`() {
        val repository = WorkMetricsInfoRepository(app, SynchronousExecutor())
        val source = WorkFixtures.source(app, uniqueNames = listOf("sync"), metrics = WorkMetrics(repository))
        WorkFixtures.initialize(app, source, execution = repository, schedule = repository)
        val routes = WorkRoutes(source)
        val workManager = WorkManager.getInstance(app)

        // Room refuses the main thread, and the synchronous executors run the listeners
        // on the thread that enqueues.
        offMain {
            workManager.enqueueUniqueWork(
                "sync",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<StepWorker>().setId(SYNC).setInputData(workDataOf("step" to 3)).addTag("demo").build(),
            ).result.get()
            workManager.enqueueUniquePeriodicWork(
                "cleanup",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<StepWorker>(15, TimeUnit.MINUTES)
                    .setId(CLEANUP)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build())
                    .addTag("demo")
                    .build(),
            ).result.get()
            workManager.enqueue(OneTimeWorkRequestBuilder<RetryWorker>().setId(RETRY).build()).result.get()
            workManager.enqueue(OneTimeWorkRequestBuilder<ThrowingWorker>().setId(THROWING).build()).result.get()
        }

        assertGolden(ITEMS, WorkFixtures.get(routes, "items").text())
        assertGolden(ITEM, offMain { WorkFixtures.get(routes, "items/$SYNC") }.text())
        assertGolden(EVENTS, WorkFixtures.get(routes, "events", "limit" to "12").text())
        assertGolden(METRICS, offMain { WorkFixtures.get(routes, "metrics", "class" to "StepWorker") }.text())
    }

    private fun assertGolden(path: String, written: String) {
        assertEquals(
            "$path differs from:\n$written\n",
            canonical(JSONObject(File(path).readText())),
            canonical(JSONObject(written)),
        )
    }

    // Key order is not part of JSON, so objects compare as sorted maps.
    private fun canonical(value: Any?): Any? =
        when (value) {
            is JSONObject -> value.keys().asSequence().filter { it !in VARYING_KEYS }.associateWith { canonical(value.get(it)) }.toSortedMap()
            is JSONArray -> (0 until value.length()).map { canonical(value.get(it)) }
            else -> value
        }

    private fun <T> offMain(block: () -> T): T {
        var result: Result<T>? = null
        val thread = Thread { result = runCatching(block) }
        thread.start()
        thread.join()
        return result!!.getOrThrow()
    }

    private companion object {
        val SYNC: UUID = UUID.fromString("3f1c2a4e-8b9d-4e2f-a6c1-0d7e5b3a9f12")
        val CLEANUP: UUID = UUID.fromString("8a6b0c1d-2e3f-4a5b-9c7d-1e2f3a4b5c6d")
        val RETRY: UUID = UUID.fromString("c2d4e6f8-0a1b-4c3d-8e5f-7a9b1c3d5e7f")
        val THROWING: UUID = UUID.fromString("5e7f9a1b-3c5d-4e7f-a1b3-c5d7e9f1a3b5")

        val VARYING_KEYS =
            setOf(
                "cursor",
                "stackTrace",
                "enqueueTimeMillis",
                "unblockTimeMillis",
                "firstStartTimeMillis",
                "finishTimeMillis",
                "workerDurationMillis",
                "totalRuntimeMillis",
            )

        // Unit tests run in the module directory.
        const val ITEMS = "../wire-protocol/v1/golden/work-items.json"
        const val ITEM = "../wire-protocol/v1/golden/work-item.json"
        const val EVENTS = "../wire-protocol/v1/golden/work-events.json"
        const val METRICS = "../wire-protocol/v1/golden/work-metrics.json"
    }
}
