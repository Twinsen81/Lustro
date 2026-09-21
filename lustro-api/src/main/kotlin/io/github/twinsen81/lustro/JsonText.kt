package io.github.twinsen81.lustro

/**
 * Escapes this string for safe inclusion inside a JSON string literal.
 *
 * Backslash, double-quote, and the standard control characters
 * (`\n`, `\r`, `\t`, `\b`, `\f`) are emitted as their JSON escape sequences;
 * any remaining control character below `0x20` is emitted as a `\\uXXXX`
 * escape. All other characters, including multi-byte Unicode, pass through
 * unchanged.
 */
public fun String.escapeForJson(): String =
    buildString(length) {
        for (ch in this@escapeForJson) {
            when (ch) {
                '\\' -> {
                    append("\\\\")
                }

                '"' -> {
                    append("\\\"")
                }

                '\n' -> {
                    append("\\n")
                }

                '\r' -> {
                    append("\\r")
                }

                '\t' -> {
                    append("\\t")
                }

                '\b' -> {
                    append("\\b")
                }

                '' -> {
                    append("\\f")
                }

                else -> {
                    @Suppress("MagicNumber")
                    if (ch.code < 0x20) {
                        append("\\u%04x".format(ch.code))
                    } else {
                        append(ch)
                    }
                }
            }
        }
    }

/**
 * Escapes this string for safe inclusion in HTML text *and* inside a quoted
 * attribute value, by replacing `&`, `<`, `>`, `"`, and `'` with their entity
 * references.
 *
 * Both quote characters are escaped so that `<input value="${x.escapeHtml()}">`
 * cannot be broken out of, whichever quote style the attribute uses. This is not
 * a substitute for escaping in a URL, a `style`, or a `<script>` block, none of
 * which are HTML contexts.
 */
public fun String.escapeHtml(): String =
    this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
