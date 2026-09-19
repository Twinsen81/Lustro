package io.github.twinsen81.lustro.internal.network

/**
 * Masks the value of every sensitive key in arbitrary text. The default redactor
 * falls back to this for any captured body that isn't a single JSON value or a
 * form: NDJSON / concatenated JSON, SSE `data:` frames, XML, `text/plain`, and
 * JSON cut off at the capture cap. Only the value of a key accepted by
 * [isSensitiveKey] becomes [placeholder], quoted where the value is a JSON number,
 * literal, object, or array; every other character is kept.
 *
 * The text can end partway through a value: a body is cut off at the capture cap,
 * a stream is redacted each time more of it arrives, and a client can close a body
 * early. Whatever part of a secret arrived is still secret, so a value that the end
 * of the text cuts off is masked through to the end.
 *
 * Four shapes are masked in order, each pass reading the previous pass's output.
 * Each pass follows the regex in its KDoc under `Regex.replace`, extended where a
 * regex can't follow nesting: a JSON object or array runs to its matching
 * bracket, and an XML element with children to its matching close tag.
 * TextualRedactorTest pins each pass byte-for-byte on ASCII text to a reference
 * built from those regexes. The passes themselves are forward scans that read
 * each character a bounded number of times. Redaction runs on the app's own HTTP
 * thread, and those regexes backtrack quadratically on long identifier runs,
 * unterminated strings, and `<`-heavy text.
 */
internal class TextualRedactor(
    private val isSensitiveKey: (String) -> Boolean,
    private val placeholder: String,
) {
    private val quotedPlaceholder = "\"$placeholder\""

    fun redact(text: String): String = maskFormPairs(maskXmlElements(maskAttributes(maskJsonPairs(text))))

    /**
     * JSON-ish `"<key>": <value>`. A string value is masked between its quotes, even
     * when the text ends inside it:
     * `"((?:[^"\\]|\\.)*)"\s*:\s*"(?:[^"\\]|\\.)*(?:"|\\?\z)`.
     * Any other value becomes a quoted placeholder, as the structured JSON path
     * masks it: a scalar `[^\s,}\]"]+` (a number, `true`, `false`, or `null`), or an
     * object or array through its matching bracket, or through to the end of the
     * text when that cuts it off. The scan goes on inside an object or array that
     * isn't masked, so the sensitive keys nested in it still are, and from the
     * opening quote of a string value that isn't masked.
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
            val valueStart = afterSeparator(text, keyClose + 1, ':')
            val next = if (valueStart < 0) -1 else maskJsonValue(text, out, keyOpen + 1, keyClose, valueStart)
            // Without a value, a key opening at an escaped quote inside this key
            // reaches the same closing quote and fails the same way, so the next
            // distinct candidate is that closing quote.
            pos = if (next < 0) keyClose else next
        }
        return out.result()
    }

    /**
     * Masks the value at [start] when the key from [keyStart] until [keyEnd] is
     * sensitive. Returns where the scan goes on, or -1 when no value starts there.
     */
    private fun maskJsonValue(text: String, out: Rewriter, keyStart: Int, keyEnd: Int, start: Int): Int {
        if (start == text.length) return -1
        val first = text[start]
        return when {
            first == '"' -> {
                val end = quotedValueEnd(text, start + 1)
                when {
                    end < 0 -> -1
                    isSensitiveKey(text.substring(keyStart, keyEnd)) -> {
                        out.replace(start + 1, end, placeholder)
                        // Past the closing quote, or past the end of a value that was cut off.
                        end + 1
                    }
                    // A key and value that aren't masked can be misread from the gaps
                    // between strings: `,` and `],` in `["x",":"],"token":"s"`. Going on
                    // past that value would skip the quote that opens "token".
                    else -> start
                }
            }
            first == '{' || first == '[' ->
                if (isSensitiveKey(text.substring(keyStart, keyEnd))) {
                    containerEnd(text, start).also { out.replace(start, it, quotedPlaceholder) }
                } else {
                    start + 1
                }
            isValueDelimiter(first) -> -1
            else ->
                skipWhile(text, start + 1) { !isValueDelimiter(it) }.also { end ->
                    if (isSensitiveKey(text.substring(keyStart, keyEnd))) out.replace(start, end, quotedPlaceholder)
                }
        }
    }

    /**
     * Attribute `<key>="<value>"`, masking `<value>` even when the text ends inside it:
     * `([A-Za-z_][\w.\-:]*)\s*=\s*"[^"]*(?:"|\z)`.
     */
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
            val valueEnd = text.indexOf('"', valueOpen + 1).let { if (it < 0) text.length else it }
            if (isSensitiveKey(text.substring(nameStart, nameEnd))) {
                out.replace(valueOpen + 1, valueEnd, placeholder)
            }
            pos = valueEnd + 1
        }
        return out.result()
    }

    /**
     * Element `<key ...>content</key>`, masking `content`. A leaf's text is masked
     * even when the text ends inside it or its close tag:
     * `(<([A-Za-z_][\w.\-:]*)\b[^>]*>)[^<]*(?:</(\2)\s*>|(?:<(?:/[\w.\-:]*\s*)?)?\z)`.
     * Like the regex, the close tag may name a prefix of the open tag's name that
     * ends on a word boundary (`<ns:key>` closed by `</ns>`).
     *
     * Where that doesn't match, an open tag `<([A-Za-z_][\w.\-:]*)[^>]*>` that
     * doesn't end in `/>` has everything up to its close tag masked: the next
     * `</key>` with exactly its name that isn't closing a nested `<key>`, or the end
     * of the text when none follows.
     */
    private fun maskXmlElements(text: String): String {
        val out = Rewriter(text)
        var pos = 0
        // An open tag's '>' and what follows it depend only on where the tag's
        // name ends, which only moves forward. Cache them so many '<' that share
        // one distant '>' ("<a <a <a ... >") don't rescan the same stretch.
        var tagEnd = -1
        var textEnd = 0 // the '<' after tagEnd, or the end of the text
        var cutOff = false // the text ends at textEnd, or partway into a close tag that starts there
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
                // No '>' means no later element can match either.
                if (tagEnd < 0) break
                textEnd = text.indexOf('<', tagEnd + 1).let { if (it < 0) text.length else it }
                cutOff = textEnd >= text.length - 1
                elementEnd = -1
                if (text.getOrNull(textEnd + 1) == '/') {
                    closeNameStart = textEnd + 2
                    closeNameEnd = skipWhile(text, closeNameStart, ::isNameChar)
                    val closeEnd = skipWhile(text, closeNameEnd, ::isSpace)
                    if (closeEnd == text.length) {
                        cutOff = true
                    } else if (closeNameEnd > closeNameStart && text[closeEnd] == '>') {
                        elementEnd = closeEnd + 1
                    }
                }
            }
            if (cutOff) {
                // No close tag to match, so the name is the longest one the regex
                // accepts: the open tag's name up to its last word character.
                var wordEnd = nameEnd
                while (!isWordChar(text[wordEnd - 1])) wordEnd--
                if (isSensitiveKey(text.substring(open + 1, wordEnd))) {
                    out.replace(tagEnd + 1, textEnd, placeholder)
                }
                break
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
            } else if (text[tagEnd - 1] != '/' && isSensitiveKey(text.substring(open + 1, nameEnd))) {
                val close = closeTagStart(text, open + 1, nameEnd, tagEnd + 1)
                if (close < 0) {
                    out.replace(tagEnd + 1, text.length, placeholder)
                    break
                }
                out.replace(tagEnd + 1, close, placeholder)
                // Past the close tag: "</", the name, spaces, and '>'.
                pos = skipWhile(text, close + 2 + (nameEnd - open - 1), ::isSpace) + 1
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

/**
 * Where a quoted value whose body starts at [from] ends: its closing quote, or the
 * end of the text when that cuts the value off (even right after a backslash).
 * -1 when a backslash before a line break stops it.
 */
private fun quotedValueEnd(text: String, from: Int): Int {
    val stop = stringEnd(text, from)
    return when {
        stop == text.length || text[stop] == '"' -> stop
        stop == text.length - 1 -> text.length
        else -> -1
    }
}

/** Index just past `\s*<separator>\s*` at [from], or -1. */
private fun afterSeparator(text: String, from: Int, separator: Char): Int {
    val i = skipWhile(text, from, ::isSpace)
    return if (i < text.length && text[i] == separator) skipWhile(text, i + 1, ::isSpace) else -1
}

/** Index of the opening quote of `\s*<separator>\s*"` at [from], or -1. */
private fun quoteAfterSeparator(text: String, from: Int, separator: Char): Int {
    val i = afterSeparator(text, from, separator)
    return if (i >= 0 && i < text.length && text[i] == '"') i else -1
}

/**
 * Index just past the bracket that closes the JSON object or array opening at
 * [from], or the end of the text when that cuts it off. Brackets in strings
 * don't count.
 */
private fun containerEnd(text: String, from: Int): Int {
    var depth = 0
    var i = from
    while (i < text.length) {
        when (text[i]) {
            '{', '[' -> depth++
            '}', ']' -> if (--depth == 0) return i + 1
            '"' -> {
                i++
                while (i < text.length && text[i] != '"') i += if (text[i] == '\\') 2 else 1
            }
        }
        i++
    }
    return text.length
}

/**
 * Index of the `<` of the tag closing the element named from [nameStart] until
 * [nameEnd], whose content starts at [from], or -1 when the text ends first.
 * Nested elements of the same name open and close in between.
 */
private fun closeTagStart(text: String, nameStart: Int, nameEnd: Int, from: Int): Int {
    val nameLength = nameEnd - nameStart
    var depth = 1
    var i = from
    while (true) {
        val open = text.indexOf('<', i)
        if (open < 0) return -1
        val closing = text.getOrNull(open + 1) == '/'
        val start = if (closing) open + 2 else open + 1
        val end = start + nameLength
        i = open + 1
        if (!text.regionMatches(start, text, nameStart, nameLength) || (end < text.length && isNameChar(text[end]))) {
            continue
        }
        if (closing) {
            val tagEnd = skipWhile(text, end, ::isSpace)
            if (tagEnd < text.length && text[tagEnd] == '>' && --depth == 0) return open
        } else {
            val tagEnd = text.indexOf('>', end)
            if (tagEnd < 0) return -1
            if (text[tagEnd - 1] != '/') depth++
            i = tagEnd + 1
        }
    }
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

private fun isValueDelimiter(c: Char): Boolean = isSpace(c) || c == ',' || c == '}' || c == ']' || c == '"'

private fun isLineTerminator(c: Char): Boolean =
    c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029'
