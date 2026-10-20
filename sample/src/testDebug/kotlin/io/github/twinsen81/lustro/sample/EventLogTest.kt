package io.github.twinsen81.lustro.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventLogTest {
    private var now = 1_000L
    private val log = EventLog { now }

    @Test
    fun `a later state of a run replaces it and moves it to the top`() {
        val first = log.start("GET /get")
        first.running("GET /get")
        log.start("POST /post").ok("200 POST /post")
        now = 2_000L
        first.response(200, "200 GET /get")

        assertEquals(listOf("200 GET /get", "200 POST /post"), log.recent.value.map { it.text })
        assertEquals(Outcome.OK, log.latestByLabel.value.getValue("GET /get").outcome)
        assertEquals(2_000L, log.recent.value.first().atMillis)
    }

    @Test
    fun `a quiet update keeps its row in place and leaves the chip alone`() {
        val sync = log.start("Start periodic sync")
        sync.ok("Periodic sync started")
        log.start("GET /get").ok("200 GET /get")
        sync.quiet().ok("200 GET /anything/sync #1")

        assertEquals(listOf("200 GET /get", "200 GET /anything/sync #1"), log.recent.value.map { it.text })
        assertEquals("Periodic sync started", log.latestByLabel.value.getValue("Start periodic sync").text)
    }

    @Test
    fun `the history keeps the newest few runs`() {
        repeat(EventLog.HISTORY_SIZE + 2) { log.start(null).info("line $it") }

        assertEquals(EventLog.HISTORY_SIZE, log.recent.value.size)
        assertEquals("line ${EventLog.HISTORY_SIZE + 1}", log.recent.value.first().text)
    }

    @Test
    fun `a 4xx or 5xx response is an HTTP error, not a failure`() {
        log.start("a").response(404, "404 GET /status/404")
        log.start("b").response(304, "304 GET /status/304")
        log.start("c").failed("GET /delay/10", "timeout")

        val latest = log.latestByLabel.value
        assertEquals(Outcome.HTTP_ERROR, latest.getValue("a").outcome)
        assertEquals(Outcome.OK, latest.getValue("b").outcome)
        assertEquals(Outcome.FAILED, latest.getValue("c").outcome)
        assertEquals("timeout", latest.getValue("c").detail)
    }

    @Test
    fun `an event without a label goes to the history only`() {
        log.start(null).info("WS ⇠ hello 1")

        assertEquals(1, log.recent.value.size)
        assertNull(log.recent.value.first().label)
        assertEquals(emptyMap<String, ActionEvent>(), log.latestByLabel.value)
    }
}
