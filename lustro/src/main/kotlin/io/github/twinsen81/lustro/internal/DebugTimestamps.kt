package io.github.twinsen81.lustro.internal

import java.util.TimeZone

// Internal-to-:lustro JSON content-type constant. NanoHTTPD 2.3.x defaults to
// US-ASCII for any mime type that's not text/* and doesn't carry an explicit
// charset, so non-ASCII chars in the body get encoded as `?`. Pin charset=utf-8
// on every JSON response so multi-byte chars (e.g. an ellipsis "…" in an
// auto-generated rule name) round-trip cleanly to the JS UI.
internal const val JSON_CONTENT_TYPE: String = "application/json; charset=utf-8"

// The formatters below are written by hand because java.time needs API 26 or
// core library desugaring, and desugaring would become a requirement on every
// app that adds Lustro.

private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
private const val MINUTES_PER_HOUR = 60L
private const val MILLIS_PER_DAY = 24L * MINUTES_PER_HOUR * SECONDS_PER_MINUTE * MILLIS_PER_SECOND
private const val TIME_LENGTH = 12
private const val UTC_LENGTH = 24

/** This instant, in epoch milliseconds, as `HH:mm:ss.SSS` in the device's time zone. */
internal fun Long.toDebugTimestamp(): String =
    buildString(TIME_LENGTH) { appendTimeOfDay(this@toDebugTimestamp + TimeZone.getDefault().getOffset(this@toDebugTimestamp)) }

/** This instant, in epoch milliseconds, as `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`, the UTC form of ISO 8601. */
internal fun Long.toUtcTimestamp(): String =
    buildString(UTC_LENGTH) {
        appendDate(this@toUtcTimestamp.floorDiv(MILLIS_PER_DAY))
        append('T')
        appendTimeOfDay(this@toUtcTimestamp)
        append('Z')
    }

private fun StringBuilder.appendTimeOfDay(millis: Long) {
    val millisOfDay = millis.mod(MILLIS_PER_DAY)
    val secondOfDay = millisOfDay / MILLIS_PER_SECOND
    appendPadded(secondOfDay / (MINUTES_PER_HOUR * SECONDS_PER_MINUTE), 2)
    append(':')
    appendPadded(secondOfDay / SECONDS_PER_MINUTE % MINUTES_PER_HOUR, 2)
    append(':')
    appendPadded(secondOfDay % SECONDS_PER_MINUTE, 2)
    append('.')
    appendPadded(millisOfDay % MILLIS_PER_SECOND, 3)
}

private const val DAYS_FROM_ERA_START_TO_EPOCH = 719_468L
private const val YEARS_PER_ERA = 400L
private const val DAYS_PER_ERA = 146_097L
private const val DAYS_PER_100_YEARS = 36_524L
private const val DAYS_PER_4_YEARS = 1_460L
private const val DAYS_PER_YEAR = 365L
private const val DAYS_PER_5_MONTHS = 153L

// Days since 1970-01-01 to a proleptic Gregorian date, as yyyy-MM-dd. This is
// Howard Hinnant's civil_from_days: it counts from 0000-03-01 so that a leap
// day is the last day of a year, in 400-year eras of 146097 days.
private fun StringBuilder.appendDate(daysSinceEpoch: Long) {
    val daysSinceEraStart = daysSinceEpoch + DAYS_FROM_ERA_START_TO_EPOCH
    val era = daysSinceEraStart.floorDiv(DAYS_PER_ERA)
    val dayOfEra = daysSinceEraStart - era * DAYS_PER_ERA
    val yearOfEra = (dayOfEra - dayOfEra / DAYS_PER_4_YEARS + dayOfEra / DAYS_PER_100_YEARS - dayOfEra / (DAYS_PER_ERA - 1)) / DAYS_PER_YEAR
    val dayOfYear = dayOfEra - (DAYS_PER_YEAR * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val monthFromMarch = (5 * dayOfYear + 2) / DAYS_PER_5_MONTHS
    val day = dayOfYear - (DAYS_PER_5_MONTHS * monthFromMarch + 2) / 5 + 1
    val month = if (monthFromMarch < 10) monthFromMarch + 3 else monthFromMarch - 9
    val year = yearOfEra + era * YEARS_PER_ERA + (if (month <= 2) 1 else 0)
    appendPadded(year, 4)
    append('-')
    appendPadded(month, 2)
    append('-')
    appendPadded(day, 2)
}

private fun StringBuilder.appendPadded(value: Long, width: Int) {
    val digits = value.toString()
    repeat(width - digits.length) { append('0') }
    append(digits)
}
