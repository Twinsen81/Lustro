package io.github.twinsen81.lustro.internal.network

/**
 * Masks the value of every sensitive key in arbitrary text. The default redactor
 * falls back to this for any captured body that isn't a single JSON value or a
 * form: NDJSON / concatenated JSON, SSE `data:` frames, XML, `text/plain`, and
 * JSON cut off at the capture cap. Only the value of a key accepted by
 * [isSensitiveKey] becomes [placeholder]; every other character is kept.
 *
 * Four shapes are masked in order, each pass reading the previous pass's output.
 * Each pass matches what the regex in its KDoc matches under `Regex.replace`
 * (byte-for-byte on ASCII text, which TextualRedactorTest pins), but in one
 * forward scan that never re-examines text it has already rejected. Redaction
 * runs on the app's own HTTP thread, and those regexes backtrack quadratically
 * on long identifier runs, unterminated strings, and `<`-heavy text.
 */
internal class TextualRedactor(
    private val isSensitiveKey: (String) -> Boolean,
    private val placeholder: String,
) {
    fun redact(text: String): String = maskFormPairs(maskXmlElements(maskAttributes(maskJsonPairs(text))))

    /**
     * JSON-ish `"<key>": "<value>"`, masking `<value>`:
     * `"((?:[^"\\]|\\.)*)"\s*:\s*"(?:[^"\\]|\\.)*"`.
     */
    private fun maskJsonPairs(text: String): String {
        val out = Rewriter(text)
        var pos = 0
        while (true) {
            val keyOpen = text.indexOf('"', pos)
            if (keyOpen < 0) break
            val keyClose = stringEnd(text, keyOpen + 1)
            if (keyClose == text.length || text[keyClose] != '"') {
                // Unterminated. Every quote before the stop is escaped, so a key
                // opening at one of them stops at the same place.
                pos = keyClose
                continue
            }
            val valueOpen = quoteAfterSeparator(text, keyClose + 1, ':')
            val valueClose = if (valueOpen < 0) text.length else stringEnd(text, valueOpen + 1)
            if (valueClose < text.length && text[valueClose] == '"') {
                if (isSensitiveKey(text.substring(keyOpen + 1, keyClose))) {
                    out.replace(valueOpen + 1, valueClose, placeholder)
                }
                pos = valueClose + 1
            } else {
                // A key opening at an escaped quote inside this key reaches the same
                // closing quote and fails the same way, so the next distinct
                // candidate is that closing quote.
                pos = keyClose
            }
        }
        return out.result()
    }

    /** Attribute `<key>="<value>"`, masking `<value>`: `([A-Za-z_][\w.\-:]*)\s*=\s*"[^"]*"`. */
    private fun maskAttributes(text: String): String {
        val out = Rewriter(text)
        var pos = 0
        while (true) {
            val nameStart = indexOfNameStart(text, pos)
            if (nameStart < 0) break
            val nameEnd = skipWhile(text, nameStart + 1, ::isNameChar)
            val valueOpen = quoteAfterSeparator(text, nameEnd, '=')
            if (valueOpen < 0) {
                // A name starting later in this run ends at the same place and
                // fails the same way.
                pos = nameEnd
                continue
            }
            val valueClose = text.indexOf('"', valueOpen + 1)
            // No quote left, so no later attribute can close either.
            if (valueClose < 0) break
            if (isSensitiveKey(text.substring(nameStart, nameEnd))) {
                out.replace(valueOpen + 1, valueClose, placeholder)
            }
            pos = valueClose + 1
        }
        return out.result()
    }

    /**
     * Leaf element `<key ...>text</key>`, masking `text`:
     * `(<([A-Za-z_][\w.\-:]*)\b[^>]*>)[^<]*</(\2)\s*>`. Like the regex, the close
     * tag may name a prefix of the open tag's name that ends on a word boundary
     * (`<ns:key>` closed by `</ns>`).
     */
    private fun maskXmlElements(text: String): String {
        val out = Rewriter(text)
        var pos = 0
        // An open tag's '>' and the close tag after it depend only on where the
        // tag's name ends, which only moves forward. Cache both so many '<' that
        // share one distant '>' ("<a <a <a ... >") don't rescan the same stretch.
        var tagEnd = -1
        var closeNameStart = 0
        var closeNameEnd = 0
        var elementEnd = -1 // -1 when no well-formed close tag follows tagEnd
        while (true) {
            val open = text.indexOf('<', pos)
            if (open < 0 || open + 1 == text.length) break
            if (!isNameStart(text[open + 1])) {
                pos = open + 1
                continue
            }
            val nameEnd = skipWhile(text, open + 2, ::isNameChar)
            if (tagEnd < nameEnd) {
                tagEnd = text.indexOf('>', nameEnd)
                // No '>' or no '<' after it means no later element can match either.
                if (tagEnd < 0) break
                val closeOpen = text.indexOf('<', tagEnd + 1)
                if (closeOpen < 0) break
                elementEnd = -1
                if (text.getOrNull(closeOpen + 1) == '/') {
                    closeNameStart = closeOpen + 2
                    closeNameEnd = skipWhile(text, closeNameStart, ::isNameChar)
                    val closeEnd = skipWhile(text, closeNameEnd, ::isSpace)
                    if (closeNameEnd > closeNameStart && text.getOrNull(closeEnd) == '>') elementEnd = closeEnd + 1
                }
            }
            val nameLength = closeNameEnd - closeNameStart
            val openNameEnd = open + 1 + nameLength
            val matches =
                elementEnd >= 0 &&
                    openNameEnd <= nameEnd &&
                    isWordBoundary(text, openNameEnd) &&
                    text.regionMatches(open + 1, text, closeNameStart, nameLength)
            if (matches) {
                val name = text.substring(closeNameStart, closeNameEnd)
                if (isSensitiveKey(name)) out.replace(tagEnd + 1, elementEnd, "$placeholder</$name>")
                pos = elementEnd
            } else {
                pos = open + 1
            }
        }
        return out.result()
    }

    /** Form/query-ish `<key>=<value>`, masking `<value>`: `\b([A-Za-z_][\w.\-]*)=(?!")([^&;\s]*)`. */
    private fun maskFormPairs(text: String): String {
        val out = Rewriter(text)
        var pos = 0
        while (pos < text.length) {
            if (!isKeyChar(text[pos])) {
                pos++
                continue
            }
            val runEnd = skipWhile(text, pos, ::isKeyChar)
            // Every key starting in this run ends at runEnd, so only the leftmost
            // start can match.
            val keyStart = firstKeyStart(text, pos, runEnd)
            if (keyStart >= 0 && text.getOrNull(runEnd) == '=' && text.getOrNull(runEnd + 1) != '"') {
                val valueEnd = skipWhile(text, runEnd + 1) { it != '&' && it != ';' && !isSpace(it) }
                if (isSensitiveKey(text.substring(keyStart, runEnd))) out.replace(runEnd + 1, valueEnd, placeholder)
                pos = valueEnd
            } else {
                pos = runEnd
            }
        }
        return out.result()
    }
}

/** Copies a text with some ranges replaced; ranges must arrive in order and not overlap. */
private class Rewriter(private val text: String) {
    private var out: StringBuilder? = null
    private var copied = 0

    fun replace(start: Int, end: Int, replacement: String) {
        val builder = out ?: StringBuilder(text.length + replacement.length).also { out = it }
        builder.append(text, copied, start).append(replacement)
        copied = end
    }

    fun result(): String = out?.append(text, copied, text.length)?.toString() ?: text
}

/**
 * Where the body of a quoted string starting at [from] stops: its closing quote,
 * or where it runs out unterminated (the end of the text, or a backslash that
 * can't escape the next char because that char is missing or a line break).
 */
private fun stringEnd(text: String, from: Int): Int {
    var i = from
    while (i < text.length) {
        when (text[i]) {
            '"' -> return i
            '\\' -> if (i + 1 < text.length && !isLineTerminator(text[i + 1])) i += 2 else return i
            else -> i++
        }
    }
    return text.length
}

/** Index of the opening quote of `\s*<separator>\s*"` at [from], or -1. */
private fun quoteAfterSeparator(text: String, from: Int, separator: Char): Int {
    var i = skipWhile(text, from, ::isSpace)
    if (i == text.length || text[i] != separator) return -1
    i = skipWhile(text, i + 1, ::isSpace)
    return if (i < text.length && text[i] == '"') i else -1
}

private fun indexOfNameStart(text: String, from: Int): Int {
    for (i in from until text.length) if (isNameStart(text[i])) return i
    return -1
}

private fun firstKeyStart(text: String, from: Int, to: Int): Int {
    for (i in from until to) if (isNameStart(text[i]) && isWordBoundary(text, i)) return i
    return -1
}

private inline fun skipWhile(text: String, from: Int, predicate: (Char) -> Boolean): Int {
    var i = from
    while (i < text.length && predicate(text[i])) i++
    return i
}

private fun isWordBoundary(text: String, index: Int): Boolean =
    (index > 0 && isWordChar(text[index - 1])) != (index < text.length && isWordChar(text[index]))

private fun isNameStart(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c == '_'

// `\w` and `\s` are Unicode-aware in Android's regex engine, so these are too.
private fun isWordChar(c: Char): Boolean =
    c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || (c >= '\u0080' && c.isLetterOrDigit())

private fun isSpace(c: Char): Boolean = c == ' ' || c in '\t'..'\r' || (c >= '\u0080' && Character.isSpaceChar(c))

private fun isNameChar(c: Char): Boolean = isWordChar(c) || c == '.' || c == '-' || c == ':'

private fun isKeyChar(c: Char): Boolean = isWordChar(c) || c == '.' || c == '-'

private fun isLineTerminator(c: Char): Boolean =
    c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029'
