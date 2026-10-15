package io.github.twinsen81.lustro.internal

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TimeZone
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The formatters are written by hand because java.time needs API 26. The JVM
 * that runs the unit tests has java.time, so it is the reference here.
 */
class DebugTimestampsTest {
    private val defaultZone: TimeZone = TimeZone.getDefault()

    @After
    fun restoreDefaultZone() {
        TimeZone.setDefault(defaultZone)
    }

    @Test
    fun `utc timestamp matches java time at the epoch, around leap days, and at year ends`() {
        for (instant in EDGE_INSTANTS) assertUtcMatches(instant)
    }

    @Test
    fun `utc timestamp matches java time for random instants between 1900 and 2200`() {
        val random = Random(SEED)
        repeat(RANDOM_SAMPLES) { assertUtcMatches(random.nextLong(MIN_INSTANT, MAX_INSTANT)) }
    }

    @Test
    fun `utc timestamp has the fixed width of the format`() {
        assertEquals("1970-01-01T00:00:00.000Z", 0L.toUtcTimestamp())
        assertEquals("1969-12-31T23:59:59.999Z", (-1L).toUtcTimestamp())
        assertEquals("2000-02-29T23:59:59.999Z", Instant.parse("2000-02-29T23:59:59.999Z").toEpochMilli().toUtcTimestamp())
    }

    @Test
    fun `debug timestamp follows the device zone, including its daylight saving time`() {
        for (zone in ZONES) {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            val reference = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.of(zone))
            val random = Random(SEED)
            val instants = EDGE_INSTANTS + List(RANDOM_SAMPLES) { random.nextLong(MIN_INSTANT, MAX_INSTANT) }
            for (instant in instants) {
                assertEquals("$instant in $zone", reference.format(Instant.ofEpochMilli(instant)), instant.toDebugTimestamp())
            }
        }
    }

    private fun assertUtcMatches(instant: Long) {
        assertEquals(instant.toString(), UTC_REFERENCE.format(Instant.ofEpochMilli(instant)), instant.toUtcTimestamp())
    }

    private companion object {
        val UTC_REFERENCE: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        const val SEED = 20_251_015L
        const val RANDOM_SAMPLES = 20_000
        val MIN_INSTANT: Long = Instant.parse("1900-01-01T00:00:00Z").toEpochMilli()
        val MAX_INSTANT: Long = Instant.parse("2200-01-01T00:00:00Z").toEpochMilli()

        val EDGE_INSTANTS: List<Long> =
            listOf(0L, -1L, 1L, 999L, 1000L) +
                listOf(
                    "1969-12-31T23:59:59.999Z",
                    // 1900 and 2100 are not leap years; 2000 and 2024 are.
                    "1900-02-28T23:59:59.999Z",
                    "1900-03-01T00:00:00Z",
                    "2000-02-29T00:00:00Z",
                    "2000-03-01T00:00:00Z",
                    "2024-02-29T12:00:00Z",
                    "2100-02-28T23:59:59.999Z",
                    "2100-03-01T00:00:00Z",
                    "2024-12-31T23:59:59.999Z",
                    "2025-01-01T00:00:00Z",
                    // Daylight saving time starts and ends, in the US and in the EU.
                    "2025-03-09T10:00:00Z",
                    "2025-03-09T09:59:59.999Z",
                    "2025-11-02T09:00:00Z",
                    "2025-03-30T01:00:00Z",
                    "2025-10-26T01:00:00Z",
                ).map { Instant.parse(it).toEpochMilli() }

        // Whole-hour, half-hour, and quarter-hour offsets, with and without
        // daylight saving time, on both sides of the date line.
        val ZONES: List<String> =
            listOf(
                "UTC",
                "Europe/Berlin",
                "America/Los_Angeles",
                "America/St_Johns",
                "Asia/Kolkata",
                "Asia/Kathmandu",
                "Australia/Lord_Howe",
                "Pacific/Chatham",
                "Pacific/Kiritimati",
                "Pacific/Pago_Pago",
            )
    }
}
