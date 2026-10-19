package io.github.twinsen81.lustro.logs.internal

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.TimeZone

/**
 * Pins the golden fixtures of the Logs routes to what the routes write, so the
 * fixtures that clients validate against can't drift from the server. The
 * cursors are random per buffer, so they are left out of the comparison.
 */
class LogsGoldenTest {
    private val zone = TimeZone.getDefault()
    private val parser = ThreadtimeParser(TimeZone.getTimeZone("UTC")) { NOW }
    private val buffer = LogBuffer(1 shl 20)
    private val reader = LogcatReader(buffer, pid = 12873, processStartMs = { 0L }, launch = FakeLauncher(FakeLogcat()))
    private val routes = LogRoutes(buffer, reader)

    @Before
    fun setUp() {
        // The fixtures model a device in UTC.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        // A running reader whose logcat writes nothing: the lines come from emit().
        reader.start()
    }

    @After
    fun tearDown() {
        reader.stop()
        TimeZone.setDefault(zone)
    }

    @Test
    fun `the golden reset, delta, and unchanged fixtures are what entries writes`() {
        emit(
            "10-19 14:22:18.204 12873 12873 I LustroSample: Lustro start() returned ENABLED",
            "10-19 14:22:18.311 12873 12873 D SyncWorker: sync started for 3 journals",
            "10-19 14:22:18.402 12873 12901 I okhttp.OkHttpClient: --> GET https://httpbingo.org/get",
            "10-19 14:22:19.006 12873 12901 I okhttp.OkHttpClient: <-- 200 https://httpbingo.org/get (604ms)",
            "10-19 14:22:19.512 12873 12901 W SyncWorker: retry 1 of 3 after a timeout",
            "10-19 14:22:20.000 12873 12873 V Heartbeat: tick",
            "10-19 14:22:21.000 12873 12873 V Heartbeat: tick",
        )
        val reset = entries(null)
        assertGolden(RESET, reset)

        emit(
            "10-19 14:22:22.000 12873 12873 V Heartbeat: tick",
            "10-19 14:22:23.000 12873 12873 V Heartbeat: tick",
            "10-19 14:22:23.410 12873 12901 E SyncWorker: sync failed: java.net.SocketTimeoutException: timeout",
            "10-19 14:22:23.411 12873 12873 I SyncWorker: next sync in 300 s",
        )
        val delta = entries(JSONObject(reset).getString("cursor"))
        assertGolden(DELTA, delta)

        routes.handle(post("pause", """{"paused":true}"""))
        emit("10-19 14:22:24.000 12873 12873 V Heartbeat: tick")
        assertGolden(UNCHANGED, entries(JSONObject(delta).getString("cursor")))
    }

    private fun emit(vararg lines: String) = lines.forEach { buffer.append(parser.parse(it)!!) }

    private fun entries(cursor: String?): String {
        val request = if (cursor == null) get("entries") else get("entries", "cursor" to cursor)
        return routes.handle(request)!!.text()
    }

    private fun assertGolden(path: String, written: String) {
        assertEquals(
            "$path differs from:\n$written\n",
            canonical(JSONObject(File(path).readText()).apply { remove("cursor") }),
            canonical(JSONObject(written).apply { remove("cursor") }),
        )
    }

    // Key order is not part of JSON, so objects compare as sorted maps.
    private fun canonical(value: Any?): Any? =
        when (value) {
            is JSONObject -> value.keys().asSequence().associateWith { canonical(value.get(it)) }.toSortedMap()
            is JSONArray -> (0 until value.length()).map { canonical(value.get(it)) }
            else -> value
        }

    private companion object {
        // Unit tests run in the module directory.
        const val RESET = "../wire-protocol/v1/golden/logs-reset.json"
        const val DELTA = "../wire-protocol/v1/golden/logs-delta.json"
        const val UNCHANGED = "../wire-protocol/v1/golden/logs-unchanged.json"

        // 2026-10-19 14:22:20.010 UTC
        const val NOW = 1_792_419_740_010L
    }
}
