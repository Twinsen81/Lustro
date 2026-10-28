package io.github.twinsen81.lustro.work.internal

import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import io.github.twinsen81.lustro.CursorCodec
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.work.internal.WorkFixtures.json
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkEventsTest {
    @Test
    fun `the oldest events go when the buffer is over its budget, and a delta counts them as dropped`() {
        val events = WorkEvents(maxBytes = 2_000) { WorkFixtures.NOW }.apply { recording = true }
        repeat(3) { events.record("started", info()) }
        val cursor = CursorCodec.encode(1, events.epoch)
        repeat(20) { events.record("started", info()) }
        val snapshot = events.snapshot(null)
        assertEquals(23, snapshot.total)
        assertTrue(snapshot.retained in 2..22)
        assertTrue(events.usedBytes() <= 2_000)

        val routes = WorkRoutes(WorkSource(ApplicationProvider.getApplicationContext(), emptyList(), events, null, null, 2_000) { null })
        val delta = routes.handle(DebugRequest("events", "GET", mapOf("cursor" to listOf(cursor))))!!.json()
        assertEquals("delta", delta.getString("status"))
        assertEquals(23L - snapshot.retained - 1, delta.getLong("dropped"))
        assertEquals(snapshot.retained, delta.getJSONArray("items").length())
    }

    @Test
    fun `before Lustro starts and after it stops, the buffer keeps no events`() {
        val events = WorkEvents(maxBytes = 1024) { WorkFixtures.NOW }
        events.record("started", info())
        events.recording = true
        events.record("started", info())
        events.recording = false
        events.record("finished", info())
        assertEquals(1, events.snapshot(null).total)
    }

    @Test
    fun `the newest event stays even when it alone is over the budget`() {
        val events = WorkEvents(maxBytes = 10) { WorkFixtures.NOW }.apply { recording = true }
        events.record("started", info())
        events.record("finished", info())
        assertEquals(1, events.snapshot(null).retained)
    }

    @Test
    fun `a stack trace beyond the limit is cut and says so`() {
        val events = WorkEvents(maxBytes = 1024 * 1024) { WorkFixtures.NOW }.apply { recording = true }
        val deep = deepException(2_000)
        events.record("exception", info()) {
            append(",\"exception\":")
            WorkJson.appendException(this, deep)
        }
        val event = JSONObject(events.snapshot(null)[0].json)
        val exception = event.getJSONObject("exception")
        assertEquals(WorkJson.MAX_STACK_TRACE_CHARS, exception.getString("stackTrace").length)
        assertTrue(exception.getBoolean("truncated"))
        assertFalse(exception.has("message"))
    }

    @Test
    fun `a long exception message is cut and says so`() {
        val out = StringBuilder()
        WorkJson.appendException(out, IllegalStateException("x".repeat(WorkJson.MAX_MESSAGE_CHARS + 1)))
        val exception = JSONObject(out.toString())
        assertEquals(WorkJson.MAX_MESSAGE_CHARS, exception.getString("message").length)
        assertTrue(exception.getBoolean("truncated"))
    }

    @Test
    fun `the result kind comes from the public factories' classes`() {
        assertEquals("success", WorkJson.resultKind(androidx.work.ListenableWorker.Result.success(androidx.work.workDataOf("a" to 1))))
        assertEquals("failure", WorkJson.resultKind(androidx.work.ListenableWorker.Result.failure()))
        assertEquals("retry", WorkJson.resultKind(androidx.work.ListenableWorker.Result.retry()))
    }

    @Test
    fun `stop reasons have the constant's name, and an unknown one is OTHER`() {
        assertEquals("UNKNOWN", WorkJson.stopReasonName(WorkInfo.STOP_REASON_UNKNOWN))
        assertEquals("QUOTA", WorkJson.stopReasonName(WorkInfo.STOP_REASON_QUOTA))
        assertEquals("OTHER", WorkJson.stopReasonName(99))
    }

    @Test
    fun `data values keep their JSON types, and a value JavaScript can't hold is a string`() {
        val data =
            androidx.work.Data.Builder()
                .putLong("safe", 9_007_199_254_740_991L)
                .putLong("big", 9_007_199_254_740_992L)
                .putDouble("nan", Double.NaN)
                .putBoolean("flag", true)
                .putIntArray("ints", intArrayOf(1, 2))
                .putString("text", "a \"quoted\" line\n")
                .build()
        val json = JSONObject(StringBuilder().also { WorkJson.appendData(it, data) }.toString())
        assertEquals(9_007_199_254_740_991L, json.getLong("safe"))
        assertEquals("9007199254740992", json.getString("big"))
        assertEquals("NaN", json.getString("nan"))
        assertTrue(json.getBoolean("flag"))
        assertEquals(2, json.getJSONArray("ints").getInt(1))
        assertEquals("a \"quoted\" line\n", json.getString("text"))
    }

    private fun info(): WorkInfo = WorkInfo(UUID.randomUUID(), WorkInfo.State.RUNNING, emptySet())

    private fun deepException(depth: Int): Throwable = if (depth == 0) RuntimeException() else deepException(depth - 1)
}
