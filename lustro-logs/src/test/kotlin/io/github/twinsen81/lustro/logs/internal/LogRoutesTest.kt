package io.github.twinsen81.lustro.logs.internal

import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class LogRoutesTest {
    private val buffer = LogBuffer(1 shl 20)
    private val reader = LogcatReader(buffer, pid = 1, processStartMs = { 0L }, launch = { error("not started") })
    private val routes = LogRoutes(buffer, reader)

    @Test
    fun `the first poll gets a reset, then a delta with the new lines, then unchanged`() {
        append("a", "b")
        val first = entries()
        assertEquals("reset", first.getString("status"))
        assertEquals(listOf("a", "b"), messages(first))

        append("c")
        val second = entries("cursor" to first.getString("cursor"))
        assertEquals("delta", second.getString("status"))
        assertEquals(listOf("c"), messages(second))
        assertEquals(0, second.getInt("dropped"))

        val third = entries("cursor" to second.getString("cursor"))
        assertEquals("unchanged", third.getString("status"))
        assertFalse(third.has("items"))
    }

    @Test
    fun `a cursor from another buffer or a broken one gets a reset`() {
        append("a")
        val other = LogRoutes(LogBuffer(1 shl 20), reader)
        val foreign = JSONObject(other.handle(get("entries"))!!.text()).getString("cursor")

        assertEquals("reset", entries("cursor" to foreign).getString("status"))
        assertEquals("reset", entries("cursor" to "%%%").getString("status"))
    }

    @Test
    fun `a delta counts the lines the buffer evicted before the client got them`() {
        val small = LogBuffer(3 * (LogBuffer.SLOT_BYTES + LogEntry.of(0, line("m0")).sizeBytes))
        val routes = LogRoutes(small, reader)
        small.append(line("m0"))
        val cursor = JSONObject(routes.handle(get("entries"))!!.text()).getString("cursor")
        (1..5).forEach { small.append(line("m$it")) }

        val delta = JSONObject(routes.handle(get("entries", "cursor" to cursor))!!.text())
        assertEquals("delta", delta.getString("status"))
        assertEquals(2, delta.getInt("dropped"))
        assertEquals(listOf("m3", "m4", "m5"), messages(delta))
    }

    @Test
    fun `clear returns a cursor whose next poll has no dropped count, and an old cursor gets a reset`() {
        append("a", "b")
        val old = entries().getString("cursor")
        val clear = JSONObject(routes.handle(post("clear"))!!.text())
        assertEquals("ok", clear.getString("status"))

        append("c")
        val next = entries("cursor" to clear.getString("cursor"))
        assertEquals("delta", next.getString("status"))
        assertEquals(0, next.getInt("dropped"))
        assertEquals(listOf("c"), messages(next))
        assertEquals("reset", entries("cursor" to old).getString("status"))
    }

    @Test
    fun `a repeated line comes again with its seq and a larger count`() {
        append("tick")
        val first = entries()
        append("tick", "tick")

        val delta = entries("cursor" to first.getString("cursor"))
        val item = delta.getJSONArray("items").getJSONObject(0)
        assertEquals(first.getJSONArray("items").getJSONObject(0).getLong("seq"), item.getLong("seq"))
        assertEquals(3, item.getInt("count"))
        assertTrue(item.has("lastAt"))
        assertFalse(first.getJSONArray("items").getJSONObject(0).has("count"))
    }

    @Test
    fun `the filters combine, and the cursor still moves past the lines they leave out`() {
        buffer.append(line("starting sync", level = 'D', tag = "SyncWorker"))
        buffer.append(line("GET /entries 200", level = 'I', tag = "OkHttp"))
        buffer.append(line("Sync failed: timeout", level = 'E', tag = "SyncWorker"))
        buffer.append(line("retry in 5 s", level = 'W', tag = "SyncWorker"))
        buffer.append(line("fatal", level = 'F', tag = "Crash"))

        assertEquals(listOf("Sync failed: timeout", "retry in 5 s", "fatal"), messages(entries("level" to "W")))
        assertEquals(listOf("Sync failed: timeout", "fatal"), messages(entries("level" to "e")))
        assertEquals(listOf("starting sync", "Sync failed: timeout", "retry in 5 s"), messages(entries("tag" to "^Sync")))
        assertEquals(listOf("starting sync", "Sync failed: timeout"), messages(entries("q" to "SYNC")))
        assertEquals(listOf("retry in 5 s"), messages(entries("mq" to "\\d+ s$")))
        assertEquals(listOf("Sync failed: timeout"), messages(entries("tag" to "Sync", "level" to "E", "q" to "timeout")))

        val none = entries("tag" to "^Nothing")
        assertEquals(emptyList<String>(), messages(none))
        buffer.append(line("later", tag = "Nothing"))
        val after = entries("tag" to "^Nothing", "cursor" to none.getString("cursor"))
        assertEquals(listOf("later"), messages(after))
    }

    @Test
    fun `limit takes the last lines on a reset, and pages a delta from the oldest`() {
        append("a")
        val cursor = entries().getString("cursor")
        append("b", "c", "d", "e")
        assertEquals(listOf("d", "e"), messages(entries("limit" to "2")))

        val page1 = entries("cursor" to cursor, "limit" to "2")
        assertEquals(listOf("b", "c"), messages(page1))
        val page2 = entries("cursor" to page1.getString("cursor"), "limit" to "2")
        assertEquals(listOf("d", "e"), messages(page2))
        assertEquals("unchanged", entries("cursor" to page2.getString("cursor")).getString("status"))
    }

    @Test
    fun `bad parameters get a 400 that names the parameter`() {
        assertBadRequest(get("entries", "level" to "X"), "level")
        assertBadRequest(get("entries", "level" to "warn"), "level")
        assertBadRequest(get("entries", "limit" to "0"), "limit")
        assertBadRequest(get("entries", "limit" to "5001"), "limit")
        assertBadRequest(get("entries", "limit" to "ten"), "limit")
        assertBadRequest(get("entries", "tag" to "("), "tag")
        assertBadRequest(get("entries", "mq" to "[a"), "mq")
        assertBadRequest(get("text", "limit" to "-1"), "limit")
    }

    @Test
    fun `a regex is at most 200 characters`() {
        append("a")
        assertEquals(200, LogFilter.MAX_REGEX_LENGTH)
        assertEquals(200, routes.handle(get("entries", "tag" to "a".repeat(200)))!!.status)
        assertBadRequest(get("entries", "tag" to "a".repeat(201)), "tag")
        assertBadRequest(get("entries", "mq" to "a".repeat(201)), "mq")
    }

    @Test
    fun `filters that pass their time get a 400`() {
        append("a")
        val filter = LogFilter.from(get("entries", "mq" to "a"))
        val budget = FilterBudget(timeoutMs = 0)
        try {
            filter.matches(buffer.snapshot(null, issuesCursor = false)[0]!!, budget)
            throw AssertionError("expected a timeout")
        } catch (e: FilterTimeoutException) {
            assertTrue(e.message!!.contains("mq"))
        }
        assertEquals("mq", filter.regexField)
    }

    @Test
    fun `text prints threadtime lines with the same filters, and limit keeps the last ones`() {
        val utc = TimeZone.getDefault()
        buffer.append(line("first", at = 0L, level = 'D', tag = "A", pid = 1234, tid = 56))
        buffer.append(line("second", at = 1L, level = 'W', tag = "LongerThanEight", pid = 1234, tid = 56))
        buffer.append(line("second", at = 2L, level = 'W', tag = "LongerThanEight", pid = 1234, tid = 56))

        val response = routes.handle(get("text"))!!
        assertEquals("text/plain; charset=utf-8", response.contentType.toString())
        val format = ThreadtimeFormat(utc)
        assertEquals(
            "${format.format(0)}  1234    56 D A       : first\n" +
                "${format.format(1)}  1234    56 W LongerThanEight: second [×2]\n",
            response.text(),
        )
        assertEquals("${format.format(1)}  1234    56 W LongerThanEight: second [×2]\n", routes.handle(get("text", "level" to "W"))!!.text())
        assertEquals(1, routes.handle(get("text", "limit" to "1"))!!.text().lines().filter { it.isNotEmpty() }.size)
    }

    @Test
    fun `text with a cursor prints only the lines after it`() {
        append("a")
        val cursor = entries().getString("cursor")
        append("b")
        assertTrue(routes.handle(get("text", "cursor" to cursor))!!.text().endsWith(": b\n"))
        assertEquals(1, routes.handle(get("text", "cursor" to cursor))!!.text().lines().filter { it.isNotEmpty() }.size)
    }

    @Test
    fun `pause and compact take a value, or toggle without one, and show in the state`() {
        assertEquals(false, entries().getJSONObject("state").getBoolean("paused"))
        assertEquals(true, JSONObject(routes.handle(post("pause", """{"paused":true}"""))!!.text()).getBoolean("paused"))
        assertEquals(true, JSONObject(routes.handle(post("pause", """{"paused":true}"""))!!.text()).getBoolean("paused"))
        assertTrue(buffer.paused)
        assertEquals(false, JSONObject(routes.handle(post("pause"))!!.text()).getBoolean("paused"))

        assertEquals(false, JSONObject(routes.handle(post("compact"))!!.text()).getBoolean("compact"))
        assertEquals(true, JSONObject(routes.handle(post("compact", "{}"))!!.text()).getBoolean("compact"))
        val state = entries().getJSONObject("state")
        assertEquals(true, state.getBoolean("compact"))
        assertEquals("stopped", state.getString("reader"))
        assertFalse(state.has("readerError"))

        assertBadRequest(post("pause", """{"paused":"yes"}"""), "paused")
        assertBadRequest(post("compact", "not json"), "compact")
        assertFalse(buffer.paused)
    }

    @Test
    fun `a route with the wrong method gets a 405, and an unknown route a 404`() {
        assertEquals(405, routes.handle(get("clear"))!!.status)
        assertEquals(405, routes.handle(post("entries"))!!.status)
        assertNull(routes.handle(get("nothing")))
    }

    private fun append(vararg messages: String) = messages.forEach { buffer.append(line(it)) }

    private fun entries(vararg params: Pair<String, String>): JSONObject {
        val response = routes.handle(get("entries", *params))!!
        assertEquals(response.text(), 200, response.status)
        return JSONObject(response.text())
    }

    private fun messages(envelope: JSONObject): List<String> {
        val items = envelope.optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).map { items.getJSONObject(it).getString("message") }
    }

    private fun assertBadRequest(request: DebugRequest, field: String) {
        val response = routes.handle(request)!!
        assertEquals(response.text(), 400, response.status)
        val error = JSONObject(response.text())
        assertEquals("bad_request", error.getString("error"))
        assertEquals(field, error.getString("field"))
    }
}

internal fun get(path: String, vararg params: Pair<String, String>): DebugRequest =
    DebugRequest(path, "GET", params.associate { (key, value) -> key to listOf(value) })

internal fun post(path: String, body: String? = null): DebugRequest = DebugRequest(path, "POST", body = body?.toByteArray())

internal fun DebugResponse.text(): String = body.toString(Charsets.UTF_8)
