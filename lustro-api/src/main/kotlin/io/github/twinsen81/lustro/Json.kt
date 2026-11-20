package io.github.twinsen81.lustro

import java.math.BigDecimal

/**
 * A small JSON reader and writer for the declared routes: this module has no JSON library at
 * run time. An object is a [LinkedHashMap] in the order of its members, an array a [List], a
 * number a [JsonNumber] that keeps its text, and `null` is [JsonNull], so that a member with
 * the value `null` differs from a missing one.
 */
internal object Json {
    private const val MAX_DEPTH = 256

    fun parse(text: String): Any = Reader(text).document()

    fun write(value: Any?, pretty: Boolean): String = buildString { append(value, if (pretty) 0 else -1) }

    private fun StringBuilder.append(value: Any?, indent: Int) {
        when (value) {
            null, JsonNull -> append("null")
            is String -> append('"').append(value.escapeForJson()).append('"')
            is Boolean, is Int, is Long, is JsonNumber -> append(value.toString())
            is Map<*, *> -> container('{', '}', value.entries, indent) { entry, inner ->
                append('"').append((entry.key as String).escapeForJson()).append(if (indent >= 0) "\": " else "\":")
                append(entry.value, inner)
            }
            is List<*> -> container('[', ']', value, indent) { item, inner -> append(item, inner) }
            else -> error("Not a JSON value: ${value.javaClass.name}")
        }
    }

    private inline fun <T> StringBuilder.container(
        open: Char,
        close: Char,
        items: Collection<T>,
        indent: Int,
        appendItem: StringBuilder.(T, Int) -> Unit,
    ) {
        append(open)
        if (items.isEmpty()) {
            append(close)
            return
        }
        val inner = if (indent >= 0) indent + 2 else -1
        items.forEachIndexed { index, item ->
            if (index > 0) append(',')
            if (inner >= 0) append('\n').append(" ".repeat(inner))
            appendItem(item, inner)
        }
        if (indent >= 0) append('\n').append(" ".repeat(indent))
        append(close)
    }

    private class Reader(private val text: String) {
        private var pos = 0

        fun document(): Any {
            val value = value(0)
            skipSpace()
            if (pos < text.length) fail("unexpected text after the value")
            return value
        }

        private fun value(depth: Int): Any {
            if (depth > MAX_DEPTH) fail("nested more than $MAX_DEPTH levels deep")
            skipSpace()
            if (pos >= text.length) fail("unexpected end of the text")
            return when (val c = text[pos]) {
                '{' -> obj(depth)
                '[' -> array(depth)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", JsonNull)
                else -> if (c == '-' || c in '0'..'9') number() else fail("unexpected character '$c'")
            }
        }

        private fun obj(depth: Int): Map<String, Any> {
            pos++
            val members = LinkedHashMap<String, Any>()
            skipSpace()
            if (peek() == '}') {
                pos++
                return members
            }
            while (true) {
                skipSpace()
                if (peek() != '"') fail("expected a member name")
                val name = string()
                skipSpace()
                expect(':')
                members[name] = value(depth + 1)
                skipSpace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return members
                    }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun array(depth: Int): List<Any> {
            pos++
            val items = ArrayList<Any>()
            skipSpace()
            if (peek() == ']') {
                pos++
                return items
            }
            while (true) {
                items += value(depth + 1)
                skipSpace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return items
                    }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun string(): String {
            pos++
            val out = StringBuilder()
            while (true) {
                if (pos >= text.length) fail("unterminated string")
                val c = text[pos]
                if (c < ' ') fail("control character in a string")
                pos++
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> out.append(escape())
                    else -> out.append(c)
                }
            }
        }

        private fun escape(): Char {
            if (pos >= text.length) fail("unterminated string")
            return when (val c = text[pos++]) {
                '"', '\\', '/' -> c
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (pos + 4 > text.length) fail("short \\u escape")
                    val digits = text.substring(pos, pos + 4)
                    if (!digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) fail("bad \\u escape")
                    val code = digits.toInt(16)
                    pos += 4
                    code.toChar()
                }
                else -> fail("bad escape \\$c")
            }
        }

        private fun number(): JsonNumber {
            val start = pos
            if (peek() == '-') pos++
            if (peek() == '0') {
                pos++
            } else if (!digits()) {
                fail("bad number")
            }
            if (peek() == '.') {
                pos++
                if (!digits()) fail("bad number")
            }
            if (peek() == 'e' || peek() == 'E') {
                pos++
                if (peek() == '+' || peek() == '-') pos++
                if (!digits()) fail("bad number")
            }
            return JsonNumber(text.substring(start, pos))
        }

        private fun digits(): Boolean {
            val start = pos
            while (pos < text.length && text[pos] in '0'..'9') pos++
            return pos > start
        }

        private fun literal(word: String, value: Any): Any {
            if (!text.startsWith(word, pos)) fail("unexpected character '${text[pos]}'")
            pos += word.length
            return value
        }

        private fun expect(c: Char) {
            if (peek() != c) fail("expected '$c'")
            pos++
        }

        private fun peek(): Char? = if (pos < text.length) text[pos] else null

        private fun skipSpace() {
            while (pos < text.length && text[pos].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) pos++
        }

        private fun fail(message: String): Nothing = throw JsonException("$message at character ${pos + 1}")
    }
}

internal object JsonNull

/** A JSON number, as the text that it was written with, so that no precision is lost. */
internal class JsonNumber(private val text: String) {
    /** The value when it is a whole number in the range of a [Long], else `null`. */
    fun toLongOrNull(): Long? {
        val number =
            try {
                BigDecimal(text)
            } catch (_: NumberFormatException) {
                return null
            }
        // Never an exact conversion of a number such as 1e999999999 or 1.5e-999999999: it would compute every digit.
        val stripped = number.stripTrailingZeros()
        if (stripped.scale() > 0 || stripped.precision() - stripped.scale() > MAX_LONG_DIGITS) return null
        val whole = stripped.toBigInteger()
        return if (whole.bitLength() < Long.SIZE_BITS) whole.toLong() else null
    }

    override fun toString(): String = text

    override fun equals(other: Any?): Boolean = other is JsonNumber && other.text == text

    override fun hashCode(): Int = text.hashCode()

    private companion object {
        private const val MAX_LONG_DIGITS = 19
    }
}

internal class JsonException(message: String) : Exception(message)
