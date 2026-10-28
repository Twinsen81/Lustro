package io.github.twinsen81.lustro.work.internal

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.analytics.WorkMetricsInfoRepository
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.twinsen81.lustro.work.internal.WorkFixtures.json
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The history of work-analytics, with its repository as the app's own listeners, behind the tab's. */
@RunWith(RobolectricTestRunner::class)
class WorkMetricsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() {
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun `the repository still records behind the tab's listeners, and metrics and item read it`() {
        val repository = WorkMetricsInfoRepository(app, SynchronousExecutor())
        val source = WorkFixtures.source(app, metrics = WorkMetrics(repository))
        WorkFixtures.initialize(app, source, execution = repository, schedule = repository)
        val routes = WorkRoutes(source)
        val workManager = WorkManager.getInstance(app)
        val ok = OneTimeWorkRequestBuilder<StepWorker>().addTag("demo").build()
        val retry = OneTimeWorkRequestBuilder<RetryWorker>().build()
        // Room refuses the main thread, and the synchronous executors run the listeners
        // on the thread that enqueues, as the server runs a route on one of its own.
        offMain { workManager.enqueue(listOf(ok, retry)).result.get() }

        val periods = offMain { WorkFixtures.get(routes, "metrics") }.json().getJSONArray("items")
        val byId = (0 until periods.length()).map { periods.getJSONObject(it) }.groupBy { it.getString("id") }
        val done = byId.getValue(ok.id.toString()).single()
        assertEquals("SUCCEEDED", done.getString("state"))
        assertEquals(StepWorker::class.java.name, done.getString("workerClass"))
        assertEquals(1, done.getInt("runAttempts"))
        assertTrue(done.has("enqueueTimeMillis") && done.has("firstStartTimeMillis") && done.has("finishTimeMillis"))
        assertTrue(done.getJSONArray("tags").toString().contains("demo"))
        val retried = byId.getValue(retry.id.toString()).first()
        assertEquals(1, retried.getInt("explicitRetries"))

        val count = { param: Pair<String, String> -> offMain { WorkFixtures.get(routes, "metrics", param) }.json().getJSONArray("items").length() }
        assertEquals(1, count("id" to ok.id.toString()))
        assertEquals(1, count("tag" to "demo"))
        assertEquals(1, count("class" to "StepWorker"))
        assertEquals(1, count("state" to "succeeded"))
        assertEquals(0, count("from" to (System.currentTimeMillis() + 60_000).toString()))
        assertEquals("state", WorkFixtures.get(routes, "metrics", "state" to "DONE").json().getString("field"))
        assertEquals("to", WorkFixtures.get(routes, "metrics", "to" to "soon").json().getString("field"))

        val item = offMain { WorkFixtures.get(routes, "items/${ok.id}") }.json()
        assertEquals(1, item.getJSONArray("metrics").length())
        assertTrue(item.getJSONObject("state").getBoolean("metrics"))
        val stateOf = { body: JSONObject -> body.getJSONObject("state") }
        assertFalse(stateOf(WorkFixtures.get(routes, "events").json()).has("reason"))
    }

    private fun <T> offMain(block: () -> T): T {
        var result: Result<T>? = null
        val thread = Thread { result = runCatching(block) }
        thread.start()
        thread.join()
        return result!!.getOrThrow()
    }
}
