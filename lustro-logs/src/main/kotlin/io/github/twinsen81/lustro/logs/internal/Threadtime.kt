package io.github.twinsen81.lustro.logs.internal

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Parses the lines of `logcat -v threadtime`:
 * `MM-dd HH:mm:ss.SSS  PID  TID L TAG     : message`, and of
 * `logcat -v threadtime -v UTC`, which has the zone after the time:
 * `MM-dd HH:mm:ss.SSS +0000  PID ...`. The reader asks for UTC: a local time
 * shifts when the device's time zone changes, and repeats an hour when
 * daylight saving time ends. A line without a zone is read in [zone].
 *
 * The line has no year. The parser takes the current one, and the year before
 * when that puts the line more than a day in the future, as for a line from
 * December read in January. Not thread-safe: the reader thread owns one.
 */
internal class ThreadtimeParser(
    zone: TimeZone = TimeZone.getDefault(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val local = Calendar.getInstance(zone, Locale.US)
    private val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US)

    /**
     * Returns the parsed line, or `null` for a line in another format, such as
     * logcat's `--------- beginning of main` or an error message.
     */
    fun parse(line: String): LogLine? {
        if (line.length < MIN_LENGTH || !hasTimestampShape(line)) return null
        var i = TIMESTAMP_LENGTH
        if (line[i] != ' ') return null
        i = skipSpaces(line, i)
        val offsetMinutes = offsetAt(line, i)
        if (offsetMinutes != null) i = skipSpaces(line, i + OFFSET_LENGTH)
        val pidEnd = digitsEnd(line, i)
        if (pidEnd == i) return null
        val pid = line.substring(i, pidEnd).toIntOrNull() ?: return null
        i = skipSpaces(line, pidEnd)
        if (i == pidEnd) return null
        val tidEnd = digitsEnd(line, i)
        if (tidEnd == i) return null
        val tid = line.substring(i, tidEnd).toIntOrNull() ?: return null
        i = skipSpaces(line, tidEnd)
        if (i == tidEnd || i + 1 >= line.length) return null
        val level = levelOf(line[i]) ?: return null
        if (line[i + 1] != ' ') return null
        val tagStart = i + 2
        // The tag is padded to 8 characters and followed by ": ". A tag can hold
        // a colon, so the first ": " ends it.
        var separator = line.indexOf(": ", tagStart)
        val message: String
        if (separator >= 0) {
            message = line.substring(separator + 2)
        } else if (line.endsWith(':') && line.length - 1 >= tagStart) {
            separator = line.length - 1
            message = ""
        } else {
            return null
        }
        val tag = line.substring(tagStart, separator).trimEnd()
        val at = timeOf(line, offsetMinutes) ?: return null
        return LogLine(at, pid, tid, level, tag, message)
    }

    // The offset of "+hhmm" or "-hhmm" followed by a space at [at], in minutes, or null.
    private fun offsetAt(line: String, at: Int): Int? {
        if (at + OFFSET_LENGTH >= line.length || line[at + OFFSET_LENGTH] != ' ') return null
        val sign =
            when (line[at]) {
                '+' -> 1
                '-' -> -1
                else -> return null
            }
        if ((at + 1..at + 4).any { line[it] !in '0'..'9' }) return null
        return sign * (twoDigits(line, at + 1) * MINUTES_PER_HOUR + twoDigits(line, at + 3))
    }

    private fun timeOf(line: String, offsetMinutes: Int?): Long? {
        val month = twoDigits(line, 0)
        val day = twoDigits(line, MONTH_DAY_OFFSET)
        if (month !in 1..MONTHS || day !in 1..MAX_DAY) return null
        val calendar = if (offsetMinutes == null) local else utc
        val shift = (offsetMinutes ?: 0) * MILLIS_PER_MINUTE
        val now = clock()
        calendar.timeInMillis = now + shift
        val year = calendar.get(Calendar.YEAR)
        calendar.clear()
        calendar.set(
            year,
            month - 1,
            day,
            twoDigits(line, HOUR_OFFSET),
            twoDigits(line, MINUTE_OFFSET),
            twoDigits(line, SECOND_OFFSET),
        )
        calendar.set(Calendar.MILLISECOND, threeDigits(line, MILLIS_OFFSET))
        var at = calendar.timeInMillis - shift
        if (at - now > DAY_MS) {
            calendar.set(Calendar.YEAR, year - 1)
            at = calendar.timeInMillis - shift
        }
        return at
    }

    private companion object {
        // "MM-dd HH:mm:ss.SSS", with 0 where a digit goes.
        const val TIMESTAMP_SHAPE = "00-00 00:00:00.000"
        const val TIMESTAMP_LENGTH = TIMESTAMP_SHAPE.length

        // "MM-dd HH:mm:ss.SSS 1 1 D T: " is the shortest line with every field.
        const val MIN_LENGTH = TIMESTAMP_LENGTH + 8
        const val MONTH_DAY_OFFSET = 3
        const val HOUR_OFFSET = 6
        const val MINUTE_OFFSET = 9
        const val SECOND_OFFSET = 12
        const val MILLIS_OFFSET = 15
        const val MONTHS = 12
        const val MAX_DAY = 31
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val OFFSET_LENGTH = 5
        const val MINUTES_PER_HOUR = 60
        const val MILLIS_PER_MINUTE = 60_000L

        fun hasTimestampShape(line: String): Boolean =
            TIMESTAMP_SHAPE.indices.all { index ->
                val expected = TIMESTAMP_SHAPE[index]
                if (expected == '0') line[index] in '0'..'9' else line[index] == expected
            }

        fun skipSpaces(line: String, from: Int): Int {
            var i = from
            while (i < line.length && line[i] == ' ') i++
            return i
        }

        fun digitsEnd(line: String, from: Int): Int {
            var i = from
            while (i < line.length && line[i] in '0'..'9') i++
            return i
        }

        fun twoDigits(line: String, at: Int): Int = (line[at] - '0') * 10 + (line[at + 1] - '0')

        fun threeDigits(line: String, at: Int): Int = (line[at] - '0') * 100 + twoDigits(line, at + 1)

        // logcat prints the priority ASSERT as F on most versions, and as A on some.
        fun levelOf(char: Char): Char? =
            when (char) {
                'A' -> 'F'
                in LogLevels.ORDER -> char
                else -> null
            }
    }
}

/** The levels from the lowest priority to the highest. */
internal object LogLevels {
    const val ORDER: String = "VDIWEF"

    fun rank(level: Char): Int = ORDER.indexOf(level)
}

/** Formats times as logcat's threadtime does, `MM-dd HH:mm:ss.SSS`, in the device's time zone. */
internal class ThreadtimeFormat(zone: TimeZone = TimeZone.getDefault()) {
    private val format = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).apply { timeZone = zone }

    fun format(at: Long): String = format.format(Date(at))
}
