package io.github.twinsen81.lustro.prefs.internal

import io.github.twinsen81.lustro.escapeForJson
import java.math.BigInteger
import org.json.JSONArray

/** The types a SharedPreferences value can have, by their names on the wire. */
internal enum class PrefType(val wireName: String) {
    STRING("string"),
    INT("int"),
    LONG("long"),
    FLOAT("float"),
    BOOLEAN("boolean"),
    STRING_SET("string_set"),

    /** A value that no SharedPreferences getter returns, which only a custom implementation can hold. */
    UNKNOWN("unknown"),
    ;

    companion object {
        /** The types that `POST edit` writes. */
        val EDITABLE: List<PrefType> = entries.filter { it != UNKNOWN }

        fun of(value: Any?): PrefType =
            when (value) {
                is String -> STRING
                is Int -> INT
                is Long -> LONG
                is Float -> FLOAT
                is Boolean -> BOOLEAN
                is Set<*> -> if (value.all { it is String }) STRING_SET else UNKNOWN
                else -> UNKNOWN
            }

        fun forWireName(name: String): PrefType? = EDITABLE.firstOrNull { it.wireName == name }
    }
}

/** A value in a request that its type can't take. */
internal class BadValueException(message: String) : Exception(message)

/** How the Preferences routes write values to JSON and read them from it. */
internal object PrefValues {
    /** The most characters of a value that `GET entries` writes; a set counts the characters of all its strings. */
    const val MAX_VALUE_CHARS: Int = 4096

    private const val INT_BYTES = 4L
    private const val LONG_BYTES = 8L
    private const val FLOAT_BYTES = 4L
    private const val BOOLEAN_BYTES = 1L
    private const val LONG_DIGITS = 19

    private val NON_FINITE = mapOf("NaN" to Float.NaN, "Infinity" to Float.POSITIVE_INFINITY, "-Infinity" to Float.NEGATIVE_INFINITY)

    /**
     * Writes the item of [key]: its type, its value unless [masked], and its size.
     * A longer value than [maxChars] is cut, and the item says `truncated`.
     */
    fun StringBuilder.appendItem(key: String, value: Any?, masked: Boolean, maxChars: Int = Int.MAX_VALUE) {
        val type = PrefType.of(value)
        append("{\"key\":\"").append(key.escapeForJson()).append("\",\"type\":\"").append(type.wireName).append('"')
        var truncated = false
        if (!masked && value != null) {
            append(",\"value\":")
            truncated = appendValue(type, value, maxChars)
        }
        append(",\"bytes\":").append(bytes(value))
        if (masked) append(",\"masked\":true")
        if (truncated) append(",\"truncated\":true")
        append('}')
    }

    /**
     * The size of [value]: a string's UTF-8 bytes, the sum of those of a set's
     * strings, and the size of a number or a boolean in memory.
     */
    fun bytes(value: Any?): Long =
        when (value) {
            null -> 0L
            is String -> utf8Length(value)
            is Int -> INT_BYTES
            is Long -> LONG_BYTES
            is Float -> FLOAT_BYTES
            is Boolean -> BOOLEAN_BYTES
            is Set<*> -> value.sumOf { utf8Length(it.toString()) }
            else -> utf8Length(value.toString())
        }

    /**
     * Reads [raw], the `value` of an edit, as a value of [type]. A number or a
     * boolean can also come as a string, as a text field holds it; a float can
     * also be `"NaN"`, `"Infinity"`, or `"-Infinity"`, which JSON has no number
     * for. A string set is a JSON array of strings.
     *
     * @throws BadValueException when [raw] isn't a value of [type].
     */
    fun parse(type: PrefType, raw: Any): Any =
        when (type) {
            PrefType.STRING -> raw as? String ?: throw BadValueException("value must be a JSON string for type string")
            PrefType.INT ->
                wholeNumber(raw, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt()
                    ?: throw BadValueException("value must be a whole number from ${Int.MIN_VALUE} to ${Int.MAX_VALUE} for type int")
            PrefType.LONG ->
                wholeNumber(raw, Long.MIN_VALUE, Long.MAX_VALUE)
                    ?: throw BadValueException("value must be a whole number from ${Long.MIN_VALUE} to ${Long.MAX_VALUE} for type long")
            PrefType.FLOAT ->
                float(raw)
                    ?: throw BadValueException("value must be a number in the range of a float, or \"NaN\", \"Infinity\", or \"-Infinity\", for type float")
            PrefType.BOOLEAN ->
                when {
                    raw is Boolean -> raw
                    raw is String && raw.trim().equals("true", ignoreCase = true) -> true
                    raw is String && raw.trim().equals("false", ignoreCase = true) -> false
                    else -> throw BadValueException("value must be true or false for type boolean")
                }
            PrefType.STRING_SET -> stringSet(raw)
            PrefType.UNKNOWN -> throw BadValueException("type unknown can't be written")
        }

    // Returns whether it cut the value.
    private fun StringBuilder.appendValue(type: PrefType, value: Any, maxChars: Int): Boolean =
        when (type) {
            PrefType.INT, PrefType.LONG, PrefType.BOOLEAN -> {
                append(value)
                false
            }
            PrefType.FLOAT -> {
                val number = value as Float
                // JSON has no number for these, so they go as the strings that edit takes back.
                if (number.isFinite()) append(number) else append('"').append(number).append('"')
                false
            }
            PrefType.STRING_SET -> appendStringSet(value as Set<*>, maxChars)
            PrefType.STRING, PrefType.UNKNOWN -> appendCappedString(value.toString(), maxChars)
        }

    private fun StringBuilder.appendCappedString(text: String, maxChars: Int): Boolean {
        val end = cut(text, maxChars)
        append('"').append(text.substring(0, end).escapeForJson()).append('"')
        return end < text.length
    }

    // Sorted, so that the same set comes in the same order on every poll.
    private fun StringBuilder.appendStringSet(set: Set<*>, maxChars: Int): Boolean {
        append('[')
        var left = maxChars
        var truncated = false
        for ((index, element) in set.map { it.toString() }.sorted().withIndex()) {
            if (left <= 0) {
                truncated = true
                break
            }
            if (index > 0) append(',')
            val end = cut(element, left)
            append('"').append(element.substring(0, end).escapeForJson()).append('"')
            left -= end
            if (end < element.length) {
                truncated = true
                break
            }
        }
        append(']')
        return truncated
    }

    // The end of the first maxChars chars of text, before a surrogate pair rather than inside it.
    private fun cut(text: String, maxChars: Int): Int {
        if (text.length <= maxChars) return text.length
        val end = maxChars.coerceAtLeast(0)
        return if (end > 0 && text[end - 1].isHighSurrogate()) end - 1 else end
    }

    private fun wholeNumber(raw: Any, min: Long, max: Long): Long? {
        val text =
            when (raw) {
                is String -> raw.trim()
                is Number -> raw.toString()
                else -> return null
            }
        // Through BigDecimal, so that 5.0 and 1e3 are whole, and a long past a double's precision stays exact.
        val decimal = text.toBigDecimalOrNull() ?: return null
        if (decimal.signum() == 0) return 0L
        // The exact conversion writes out every digit, so a value such as 1e100000000 would take the
        // app's CPU and heap. A long has at most 19 digits before the point, and a whole number has
        // no more digits after it than the ones it was written with.
        if (decimal.precision() - decimal.scale() > LONG_DIGITS || decimal.scale() > text.length) return null
        val whole =
            try {
                decimal.toBigIntegerExact()
            } catch (_: ArithmeticException) {
                return null
            }
        return whole.takeIf { it >= BigInteger.valueOf(min) && it <= BigInteger.valueOf(max) }?.toLong()
    }

    private fun float(raw: Any): Float? =
        when (raw) {
            is String -> raw.trim().let { text -> NON_FINITE[text] ?: text.toFloatOrNull()?.takeIf { it.isFinite() } }
            // A number past the range of a float turns into an infinity.
            is Number -> raw.toFloat().takeIf { it.isFinite() }
            else -> null
        }

    private fun stringSet(raw: Any): Set<String> {
        val array = raw as? JSONArray ?: throw BadValueException("value must be a JSON array of strings for type string_set")
        val set = HashSet<String>(array.length())
        for (index in 0 until array.length()) {
            set += array.opt(index) as? String
                ?: throw BadValueException("value must be a JSON array of strings for type string_set: item $index is not a string")
        }
        return set
    }

    private fun utf8Length(text: String): Long {
        var bytes = 0L
        var index = 0
        while (index < text.length) {
            val char = text[index]
            bytes +=
                when {
                    char.code < 0x80 -> 1
                    char.code < 0x800 -> 2
                    char.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate() -> {
                        index++
                        4
                    }
                    // The encoder writes a lone surrogate as one '?'.
                    char.isSurrogate() -> 1
                    else -> 3
                }
            index++
        }
        return bytes
    }
}
