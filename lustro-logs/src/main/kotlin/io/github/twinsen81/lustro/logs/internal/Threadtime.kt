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
     *
     * The line is the first [length] bytes of [line], UTF-8 as logcat writes it,
     * without its line break. The fields before the tag are ASCII, and the tag
     * and the message are decoded on their own, because logcat cuts its prefix
     * at a byte count.
     */
    fun parse(line: ByteArray, length: Int = line.size): LogLine? {
        if (length < MIN_LENGTH || !hasTimestampShape(line)) return null
        var i = TIMESTAMP_LENGTH
        if (line.char(i) != ' ') return null
        i = skipSpaces(line, i, length)
        val offsetMinutes = offsetAt(line, i, length)
        if (offsetMinutes != null) i = skipSpaces(line, i + OFFSET_LENGTH, length)
        val pidEnd = digitsEnd(line, i, length)
        if (pidEnd == i) return null
        val pid = number(line, i, pidEnd) ?: return null
        i = skipSpaces(line, pidEnd, length)
        if (i == pidEnd) return null
        val tidEnd = digitsEnd(line, i, length)
        if (tidEnd == i) return null
        val tid = number(line, i, tidEnd) ?: return null
        i = skipSpaces(line, tidEnd, length)
        if (i == tidEnd || i + 1 >= length) return null
        val level = levelOf(line.char(i)) ?: return null
        if (line.char(i + 1) != ' ') return null
        val tagStart = i + 2
        val tagEnd: Int
        val messageStart: Int
        // logcat pads the tag to 8 bytes before its ": ", so a ": " in a shorter tag
        // is not the separator. It writes the prefix, from the time to that ": ",
        // into 128 bytes with the C string's NUL, so a ": " that ends after byte
        // 127 is in the message.
        val padded = separator(line, tagStart + TAG_WIDTH, minOf(length, MAX_PREFIX_BYTES))
        if (padded >= 0) {
            tagEnd = padded
            messageStart = padded + 2
        } else if (length >= MAX_PREFIX_BYTES) {
            // A long tag: logcat cut the prefix at byte 127, the ": " with it, and the message starts there.
            tagEnd = wholeCharactersEnd(line, tagStart, MAX_PREFIX_BYTES)
            messageStart = MAX_PREFIX_BYTES
        } else {
            // A tag without the padding, or an empty message without the space after the colon.
            val unpadded = separator(line, tagStart, length)
            if (unpadded >= 0) {
                tagEnd = unpadded
                messageStart = unpadded + 2
            } else if (line.char(length - 1) == ':' && length - 1 >= tagStart) {
                tagEnd = length - 1
                messageStart = length
            } else {
                return null
            }
        }
        val tag = String(line, tagStart, tagEnd - tagStart, Charsets.UTF_8).trimEnd()
        val message = String(line, messageStart, length - messageStart, Charsets.UTF_8)
        val at = timeOf(line, offsetMinutes) ?: return null
        return LogLine(at, pid, tid, level, tag, message)
    }

    /** [parse] of [line] as UTF-8. */
    fun parse(line: String): LogLine? = parse(line.toByteArray(Charsets.UTF_8))

    // The offset of "+hhmm" or "-hhmm" followed by a space at [at], in minutes, or null.
    private fun offsetAt(line: ByteArray, at: Int, length: Int): Int? {
        if (at + OFFSET_LENGTH >= length || line.char(at + OFFSET_LENGTH) != ' ') return null
        val sign =
            when (line.char(at)) {
                '+' -> 1
                '-' -> -1
                else -> return null
            }
        if ((at + 1..at + 4).any { line.char(it) !in '0'..'9' }) return null
        return sign * (twoDigits(line, at + 1) * MINUTES_PER_HOUR + twoDigits(line, at + 3))
    }

    private fun timeOf(line: ByteArray, offsetMinutes: Int?): Long? {
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
        const val TAG_WIDTH = 8
        const val MAX_PREFIX_BYTES = 127
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
        const val BYTE_MASK = 0xff
        const val UTF8_CONTINUATION_MASK = 0xc0
        const val UTF8_CONTINUATION = 0x80
        const val UTF8_TWO_BYTES = 0xc0
        const val UTF8_THREE_BYTES = 0xe0
        const val UTF8_FOUR_BYTES = 0xf0

        // A byte as a char: an ASCII byte is its character, and a byte of a
        // multi-byte UTF-8 character is never one of the ASCII ones compared with.
        fun ByteArray.char(index: Int): Char = (this[index].toInt() and BYTE_MASK).toChar()

        fun hasTimestampShape(line: ByteArray): Boolean =
            TIMESTAMP_SHAPE.indices.all { index ->
                val expected = TIMESTAMP_SHAPE[index]
                if (expected == '0') line.char(index) in '0'..'9' else line.char(index) == expected
            }

        fun skipSpaces(line: ByteArray, from: Int, length: Int): Int {
            var i = from
            while (i < length && line.char(i) == ' ') i++
            return i
        }

        fun digitsEnd(line: ByteArray, from: Int, length: Int): Int {
            var i = from
            while (i < length && line.char(i) in '0'..'9') i++
            return i
        }

        fun number(line: ByteArray, from: Int, to: Int): Int? = String(line, from, to - from, Charsets.US_ASCII).toIntOrNull()

        // The end of the last whole UTF-8 character before [end]: a cut at a byte count
        // can end in the middle of one.
        fun wholeCharactersEnd(line: ByteArray, from: Int, end: Int): Int {
            var lead = end - 1
            while (lead > from && (line[lead].toInt() and UTF8_CONTINUATION_MASK) == UTF8_CONTINUATION) lead--
            val first = line[lead].toInt() and BYTE_MASK
            val size =
                when {
                    first >= UTF8_FOUR_BYTES -> 4
                    first >= UTF8_THREE_BYTES -> 3
                    first >= UTF8_TWO_BYTES -> 2
                    else -> 1
                }
            return if (lead + size > end) lead else end
        }

        // The index of the first ": " at [from] or later that ends by [end], or -1.
        fun separator(line: ByteArray, from: Int, end: Int): Int {
            for (i in from until end - 1) {
                if (line.char(i) == ':' && line.char(i + 1) == ' ') return i
            }
            return -1
        }

        fun twoDigits(line: ByteArray, at: Int): Int = (line.char(at) - '0') * 10 + (line.char(at + 1) - '0')

        fun threeDigits(line: ByteArray, at: Int): Int = (line.char(at) - '0') * 100 + twoDigits(line, at + 1)

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
