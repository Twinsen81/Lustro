package io.github.twinsen81.lustro.work.internal

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import io.github.twinsen81.lustro.work.internal.WorkFixtures.json
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkRoutesTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var listeners: WorkFixtures.AppListeners
    private lateinit var source: WorkSource
    private lateinit var routes: WorkRoutes
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        listeners = WorkFixtures.AppListeners()
        source = WorkFixtures.source(app, uniqueNames = listOf("sync"))
        WorkFixtures.initialize(app, source, listeners, listeners)
        routes = WorkRoutes(source)
        workManager = WorkManager.getInstance(app)
    }

    @After
    fun tearDown() {
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun `items lists all work, labels a registered unique name, and has the schedule of periodic work`() {
        val sync = step(1)
        workManager.enqueueUniqueWork("sync", ExistingWorkPolicy.REPLACE, sync).result.get()
        val plain = step(2)
        workManager.enqueue(plain).result.get()
        val periodic =
            PeriodicWorkRequestBuilder<StepWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresCharging(true).build())
                .addTag("periodic")
                .build()
        workManager.enqueue(periodic).result.get()

        val body = WorkFixtures.get(routes, "items").json()
        assertEquals("reset", body.getString("status"))
        val items = byId(body.getJSONArray("items"))
        assertEquals(3, items.size)

        val synced = items.getValue(sync.id.toString())
        assertEquals("sync", synced.getString("uniqueName"))
        assertEquals("SUCCEEDED", synced.getString("state"))
        assertEquals(StepWorker::class.java.name, synced.getString("workerClass"))
        assertEquals(1, synced.getInt("runAttempts"))
        assertEquals(1, synced.getJSONObject("output").getInt("step"))
        // A long beyond 2^53 - 1 is a string; a string array is an array.
        assertEquals(Long.MAX_VALUE.toString(), synced.getJSONObject("output").getString("big"))
        assertEquals("b", synced.getJSONObject("output").getJSONArray("names").getString(1))
        assertEquals(JSONObject().put("code", -256).put("name", "NOT_STOPPED").toString(), synced.getJSONObject("stopReason").toString())
        assertFalse(synced.has("periodicity"))
        assertFalse(synced.has("nextScheduleTimeMillis"))

        assertFalse(items.getValue(plain.id.toString()).has("uniqueName"))

        val repeating = items.getValue(periodic.id.toString())
        assertEquals("ENQUEUED", repeating.getString("state"))
        assertEquals(TimeUnit.MINUTES.toMillis(15), repeating.getJSONObject("periodicity").getLong("repeatIntervalMillis"))
        assertTrue(repeating.getLong("nextScheduleTimeMillis") > 0)
        val constraints = repeating.getJSONObject("constraints")
        assertEquals("CONNECTED", constraints.getString("network"))
        assertTrue(constraints.getBoolean("requiresCharging"))
        assertTrue(repeating.getJSONArray("tags").toList().contains("periodic"))

        val state = body.getJSONObject("state")
        assertEquals("installed", state.getString("listeners"))
        assertFalse(state.has("reason"))
        assertTrue(state.getBoolean("initialized"))
        assertFalse(state.getBoolean("metrics"))
        assertEquals("sync", state.getJSONArray("uniqueNames").getString(0))
    }

    @Test
    fun `items filters by state, tag, class, and any unique name`() {
        workManager.enqueue(step(1, tag = "a")).result.get()
        workManager.enqueue(step(2, fail = true)).result.get()
        workManager.enqueueUniqueWork("other", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<RetryWorker>().build()).result.get()

        assertEquals(listOf("FAILED"), states(WorkFixtures.get(routes, "items", "state" to "failed")))
        assertEquals(listOf("FAILED", "SUCCEEDED"), states(WorkFixtures.get(routes, "items", "state" to "SUCCEEDED,failed")).sorted())
        assertEquals(1, items(WorkFixtures.get(routes, "items", "tag" to "a")).length())
        assertEquals(2, items(WorkFixtures.get(routes, "items", "class" to "StepWorker")).length())
        assertEquals(2, items(WorkFixtures.get(routes, "items", "class" to StepWorker::class.java.name)).length())
        assertEquals(0, items(WorkFixtures.get(routes, "items", "class" to "Step")).length())
        // A name that the app did not register still finds its work, which then carries the name.
        val other = items(WorkFixtures.get(routes, "items", "name" to "other"))
        assertEquals(1, other.length())
        assertEquals("other", other.getJSONObject(0).getString("uniqueName"))
        assertEquals(0, items(WorkFixtures.get(routes, "items", "name" to "none")).length())
    }

    @Test
    fun `a poll of an unchanged list is unchanged, and a change sends the whole list again`() {
        workManager.enqueue(step(1)).result.get()
        val first = WorkFixtures.get(routes, "items").json()
        val same = WorkFixtures.get(routes, "items", "cursor" to first.getString("cursor")).json()
        assertEquals("unchanged", same.getString("status"))
        assertFalse(same.has("items"))
        assertTrue(same.has("state"))

        workManager.enqueue(step(2)).result.get()
        val changed = WorkFixtures.get(routes, "items", "cursor" to first.getString("cursor")).json()
        assertEquals("delta", changed.getString("status"))
        assertEquals(2, changed.getJSONArray("items").length())
        assertEquals("reset", WorkFixtures.get(routes, "items", "cursor" to "not-a-cursor").json().getString("status"))
    }

    @Test
    fun `the events of a run carry its result, output, exception, and stop reason, and the app's listeners get them all`() {
        val ok = step(1)
        workManager.enqueue(ok).result.get()
        val throwing = OneTimeWorkRequestBuilder<ThrowingWorker>().build()
        workManager.enqueue(throwing).result.get()
        val retry = OneTimeWorkRequestBuilder<RetryWorker>().build()
        workManager.enqueue(retry).result.get()
        val waiting = OneTimeWorkRequestBuilder<WaitingWorker>().build()
        workManager.enqueue(waiting).result.get()
        WorkManagerTestInitHelper.getTestDriver(app)!!.stopRunningWorkWithReason(waiting.id, WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY)

        val events = WorkFixtures.get(routes, "events").json().getJSONArray("items")
        val seen = (0 until events.length()).map { events.getJSONObject(it) }
        assertEquals((0 until seen.size).map { it.toLong() }, seen.map { it.getLong("seq") })
        assertTrue(seen.all { it.getLong("at") == WorkFixtures.NOW })

        val okEvents = seen.filter { it.getString("id") == ok.id.toString() }
        assertEquals(listOf("enqueued", "unblocked", "started", "finished"), okEvents.map { it.getString("type") })
        val finished = okEvents.last()
        assertEquals("success", finished.getString("result"))
        assertEquals(1, finished.getJSONObject("output").getInt("step"))
        assertEquals("SUCCEEDED", finished.getString("state"))

        val thrown = seen.first { it.getString("id") == throwing.id.toString() && it.getString("type") == "exception" }
        val exception = thrown.getJSONObject("exception")
        assertEquals(IllegalStateException::class.java.name, exception.getString("type"))
        assertEquals("the journal store is corrupt", exception.getString("message"))
        assertTrue(exception.getString("stackTrace").contains("ThrowingWorker.doWork"))
        assertFalse(exception.has("truncated"))
        // A worker that throws has no result: the exception event ends its run, as FAILED.
        assertEquals("FAILED", thrown.getString("state"))
        assertFalse(seen.any { it.getString("id") == throwing.id.toString() && it.getString("type") == "finished" })

        val retried = seen.first { it.getString("id") == retry.id.toString() && it.getString("type") == "finished" }
        assertEquals("retry", retried.getString("result"))
        assertEquals("ENQUEUED", retried.getString("state"))

        val stopped = seen.first { it.getString("id") == waiting.id.toString() && it.getString("type") == "stopped" }
        assertEquals(
            JSONObject().put("code", WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY).put("name", "CONSTRAINT_CONNECTIVITY").toString(),
            stopped.getJSONObject("stopReason").toString(),
        )

        // The app's own listeners got the same events, in the same order.
        assertEquals(seen.map { "${it.getString("type")} ${it.getString("id")}" + stopSuffix(it) }, listeners.seen.toList())
    }

    @Test
    fun `a failed step of a chain fails the steps after it with prerequisiteFailed`() {
        val first = step(1)
        val failing = step(2, fail = true)
        val third = step(3)
        workManager.beginWith(first).then(failing).then(third).enqueue().result.get()

        val events = WorkFixtures.get(routes, "events", "id" to third.id.toString()).json().getJSONArray("items")
        val types = (0 until events.length()).map { events.getJSONObject(it).getString("type") }
        assertEquals(listOf("enqueued", "prerequisiteFailed"), types)
        assertEquals("FAILED", events.getJSONObject(1).getString("state"))
        val failed = WorkFixtures.get(routes, "items/${failing.id}").json()
        assertEquals("failure", lastOf(failed.getJSONArray("events")).getString("result"))
        // The step's input is merged with the output of the step before it, so the step number is 1.
        assertTrue(lastOf(failed.getJSONArray("events")).getJSONObject("output").getString("reason").endsWith("failed"))
    }

    @Test
    fun `events follow the cursor, filter by id, class, and type, and page with limit`() {
        val first = step(1)
        workManager.enqueue(first).result.get()
        val reset = WorkFixtures.get(routes, "events").json()
        assertEquals(4, reset.getJSONArray("items").length())
        val unchanged = WorkFixtures.get(routes, "events", "cursor" to reset.getString("cursor")).json()
        assertEquals("unchanged", unchanged.getString("status"))

        val second = OneTimeWorkRequestBuilder<RetryWorker>().build()
        workManager.enqueue(second).result.get()
        val delta = WorkFixtures.get(routes, "events", "cursor" to reset.getString("cursor")).json()
        assertEquals("delta", delta.getString("status"))
        assertEquals(0, delta.getLong("dropped"))
        assertTrue((0 until delta.getJSONArray("items").length()).all { delta.getJSONArray("items").getJSONObject(it).getString("id") == second.id.toString() })

        assertEquals(4, items(WorkFixtures.get(routes, "events", "id" to first.id.toString())).length())
        assertEquals(4, items(WorkFixtures.get(routes, "events", "class" to "StepWorker")).length())
        val finishes = items(WorkFixtures.get(routes, "events", "type" to "finished,STARTED"))
        assertEquals(4, finishes.length())

        // A new client gets the newest events; a delta pages from its cursor.
        val newest = items(WorkFixtures.get(routes, "events", "limit" to "1"))
        assertEquals(1, newest.length())
        assertEquals(second.id.toString(), newest.getJSONObject(0).getString("id"))
        val page = WorkFixtures.get(routes, "events", "cursor" to reset.getString("cursor"), "limit" to "2").json()
        assertEquals(2, page.getJSONArray("items").length())
        val rest = WorkFixtures.get(routes, "events", "cursor" to page.getString("cursor")).json()
        assertEquals("delta", rest.getString("status"))
        assertEquals(delta.getJSONArray("items").length() - 2, rest.getJSONArray("items").length())
    }

    @Test
    fun `item gives the work with its events, and the events of pruned work`() {
        val request = step(1)
        workManager.enqueueUniqueWork("sync", ExistingWorkPolicy.REPLACE, request).result.get()
        val body = WorkFixtures.get(routes, "items/${request.id}").json()
        assertEquals("sync", body.getJSONObject("item").getString("uniqueName"))
        assertEquals(4, body.getJSONArray("events").length())
        assertFalse(body.has("metrics"))
        assertEquals("installed", body.getJSONObject("state").getString("listeners"))

        val pruned = WorkFixtures.post(routes, "prune", null).json()
        assertEquals(1, pruned.getInt("pruned"))
        val gone = WorkFixtures.get(routes, "items/${request.id}").json()
        assertTrue(gone.isNull("item"))
        assertEquals(4, gone.getJSONArray("events").length())

        val unknown = WorkFixtures.get(routes, "items/${UUID.randomUUID()}")
        assertEquals(404, unknown.status)
        assertEquals("id", unknown.json().getString("field"))
        assertEquals(400, WorkFixtures.get(routes, "items/not-an-id").status)
    }

    @Test
    fun `cancel takes an id, a unique name, or a tag, and counts the work that was not finished`() {
        val a = delayed("a")
        val b = delayed("b")
        workManager.enqueue(listOf(a, b)).result.get()
        workManager.enqueueUniqueWork("sync", ExistingWorkPolicy.REPLACE, delayed("c")).result.get()
        val done = step(1, tag = "a")
        workManager.enqueue(done).result.get()

        assertEquals(1, WorkFixtures.post(routes, "cancel", JSONObject().put("id", b.id.toString())).json().getInt("cancelled"))
        assertEquals(WorkInfo.State.CANCELLED, workManager.getWorkInfoById(b.id).get()!!.state)
        assertEquals(1, WorkFixtures.post(routes, "cancel", JSONObject().put("name", "sync")).json().getInt("cancelled"))
        // The tag matches a and the finished work; only a changes.
        assertEquals(1, WorkFixtures.post(routes, "cancel", JSONObject().put("tag", "a")).json().getInt("cancelled"))
        assertEquals(WorkInfo.State.SUCCEEDED, workManager.getWorkInfoById(done.id).get()!!.state)
        assertTrue(items(WorkFixtures.get(routes, "events", "type" to "cancelled")).length() == 3)
        // Again: everything is finished now.
        assertEquals(0, WorkFixtures.post(routes, "cancel", JSONObject().put("tag", "a")).json().getInt("cancelled"))

        assertEquals(404, WorkFixtures.post(routes, "cancel", JSONObject().put("id", UUID.randomUUID().toString())).status)
        assertEquals(404, WorkFixtures.post(routes, "cancel", JSONObject().put("name", "none")).status)
        assertEquals(404, WorkFixtures.post(routes, "cancel", JSONObject().put("tag", "none")).status)
        val two = WorkFixtures.post(routes, "cancel", JSONObject().put("id", a.id.toString()).put("tag", "a"))
        assertEquals(400, two.status)
        assertEquals("tag", two.json().getString("field"))
        assertEquals(400, WorkFixtures.post(routes, "cancel", JSONObject()).status)
        // A misspelled key is refused, not ignored, and nothing is cancelled.
        val misspelled = WorkFixtures.post(routes, "cancel", JSONObject().put("tag", "a").put("nmae", "sync"))
        assertEquals(400, misspelled.status)
        assertEquals("nmae", misspelled.json().getString("field"))
        assertEquals("name", WorkFixtures.post(routes, "cancel", JSONObject().put("name", 3)).json().getString("field"))
        assertEquals(400, WorkFixtures.post(routes, "cancel", null).status)
        assertEquals("id", WorkFixtures.post(routes, "cancel", JSONObject().put("id", "nope")).json().getString("field"))
    }

    @Test
    fun `prune deletes the finished work and keeps the rest`() {
        workManager.enqueue(step(1)).result.get()
        workManager.enqueue(step(2, fail = true)).result.get()
        val waiting = delayed("w")
        workManager.enqueue(waiting).result.get()
        val body = WorkFixtures.post(routes, "prune", null).json()
        assertEquals("ok", body.getString("status"))
        assertEquals(2, body.getInt("pruned"))
        assertEquals(listOf(waiting.id.toString()), ids(WorkFixtures.get(routes, "items")))
    }

    @Test
    fun `a parameter the routes can't take is a 400 that names it, and a wrong method is a 405`() {
        assertEquals("state", WorkFixtures.get(routes, "items", "state" to "DONE").json().getString("field"))
        assertEquals("limit", WorkFixtures.get(routes, "events", "limit" to "0").json().getString("field"))
        assertEquals("limit", WorkFixtures.get(routes, "events", "limit" to "5001").json().getString("field"))
        assertEquals("type", WorkFixtures.get(routes, "events", "type" to "ran").json().getString("field"))
        assertEquals("id", WorkFixtures.get(routes, "events", "id" to "x").json().getString("field"))
        assertEquals(405, WorkFixtures.post(routes, "items", null).status)
        assertEquals(405, WorkFixtures.get(routes, "prune").status)
        assertNull(routes.handle(io.github.twinsen81.lustro.DebugRequest("jobs", "GET")))
        val off = WorkFixtures.get(routes, "metrics")
        assertEquals(404, off.status)
        assertEquals("metrics_off", off.json().getString("code"))
    }

    @Test
    fun `before the app initializes WorkManager, the work routes say so and the events still answer`() {
        val waiting = WorkRoutes(WorkFixtures.source(app, initialized = { null }))
        for (response in listOf(WorkFixtures.get(waiting, "items"), WorkFixtures.post(waiting, "prune", null))) {
            assertEquals(503, response.status)
            assertEquals("work_not_initialized", response.json().getString("code"))
        }
        val events = WorkFixtures.get(waiting, "events").json()
        assertEquals("missing", events.getJSONObject("state").getString("listeners"))
        assertFalse(events.getJSONObject("state").getBoolean("initialized"))
    }

    @Test
    fun `without WorkManager 2_12, every route is a 503 and configure leaves the builder as it is`() {
        val old = WorkFixtures.source(app, unsupported = "The tab needs WorkManager 2.12.0 or newer; the app has an older one")
        val routes = WorkRoutes(old)
        for (response in listOf(WorkFixtures.get(routes, "items"), WorkFixtures.get(routes, "events"), WorkFixtures.post(routes, "cancel", null))) {
            assertEquals(503, response.status)
            assertEquals("work_unsupported", response.json().getString("code"))
            assertTrue(response.json().getString("hint").contains("2.12.0"))
        }
        val builder = androidx.work.Configuration.Builder()
        old.configure(builder)
        assertNull(builder.build().getExecutionEventListener())
    }

    @Test
    fun `the state says when WorkManager runs with a Configuration that did not go through configure`() {
        val unused = WorkFixtures.source(app)
        assertEquals("missing", WorkRoutes(unused).let { WorkFixtures.get(it, "items").json() }.getJSONObject("state").getString("listeners"))
        assertTrue(WorkFixtures.get(WorkRoutes(unused), "items").json().getJSONObject("state").getString("reason").contains("configure()"))

        unused.configure(androidx.work.Configuration.Builder())
        val state = WorkFixtures.get(WorkRoutes(unused), "items").json().getJSONObject("state")
        assertEquals("missing", state.getString("listeners"))
        assertTrue(state.getString("reason").contains("another Configuration"))
    }

    private fun step(step: Int, fail: Boolean = false, tag: String? = null): OneTimeWorkRequest {
        val builder = OneTimeWorkRequestBuilder<StepWorker>().setInputData(workDataOf("step" to step, "fail" to fail))
        tag?.let { builder.addTag(it) }
        return builder.build()
    }

    // Work that waits for its delay, which the test driver never meets.
    private fun delayed(tag: String): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<StepWorker>().setInitialDelay(1, TimeUnit.HOURS).addTag(tag).build()

    private fun stopSuffix(event: JSONObject): String = if (event.has("stopReason")) " ${event.getJSONObject("stopReason").getInt("code")}" else ""

    private fun items(response: io.github.twinsen81.lustro.DebugResponse): JSONArray = response.json().getJSONArray("items")

    private fun states(response: io.github.twinsen81.lustro.DebugResponse): List<String> =
        items(response).let { array -> (0 until array.length()).map { array.getJSONObject(it).getString("state") } }

    private fun ids(response: io.github.twinsen81.lustro.DebugResponse): List<String> =
        items(response).let { array -> (0 until array.length()).map { array.getJSONObject(it).getString("id") } }

    private fun byId(array: JSONArray): Map<String, JSONObject> =
        (0 until array.length()).map { array.getJSONObject(it) }.associateBy { it.getString("id") }

    private fun lastOf(array: JSONArray): JSONObject = array.getJSONObject(array.length() - 1)

    private fun JSONArray.toList(): List<Any> = (0 until length()).map { get(it) }
}
