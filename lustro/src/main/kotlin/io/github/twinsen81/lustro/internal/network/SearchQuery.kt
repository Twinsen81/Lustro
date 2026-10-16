package io.github.twinsen81.lustro.internal.network

import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * A search as the Network tab's search field and the `search` parameters take it.
 *
 * Terms separated by spaces must all match. Terms joined by `|` or `OR` match when one of
 * them does, and that binds closer than the spaces: `a b | c` is a and (b or c). `-term`
 * or `NOT term` matches where the term doesn't. A term is a word, which ignores case,
 * a `"quoted phrase"` with spaces, or a regular expression: `/pattern/` or `regex:pattern`.
 * A term can name the one field it looks at: `url:`, `method:`, `status:`, `header:`, or
 * `body:`.
 *
 * Text that uses none of this, such as `/api/v1/users` or `"id":42`, is a word, as it was
 * before: a quote, a slash, or a prefix is syntax only where it opens a phrase, a regex,
 * or a field.
 *
 * The same rules are in `shared.js`, which marks the matches in the console. The
 * test fixture `search-queries.json` keeps the two in step.
 */
internal class SearchQuery private constructor(
    /** The query as given. A transaction remembers its result for the last one. */
    val text: String,
    private val groups: List<List<SearchTerm>>,
) {
    /** A query of operators alone, such as `|`, has nothing to look for and matches everything. */
    val isEmpty: Boolean
        get() = groups.isEmpty()

    fun matches(tx: NetworkTransaction, budget: SearchBudget): Boolean =
        groups.all { group -> group.any { it.matches(tx, budget) } }

    /** Whether [text] matches, for a log whose entries have only their text. Null text never does. */
    fun matchesText(text: String?, budget: SearchBudget): Boolean =
        text != null && groups.all { group -> group.any { it.matchesText(text, budget) } }

    /** The parsed terms in one line, for tests: `[-]field:"word"` or `/regex/`, groups joined by ` AND `. */
    fun describe(): String =
        groups.joinToString(" AND ") { group ->
            val terms = group.joinToString(" OR ") { it.describe() }
            if (group.size > 1) "($terms)" else terms
        }

    companion object {
        /**
         * Parses [text]. With [fields] false, as for a WebSocket message that has
         * only its text, a field prefix such as `url:` is text like any other.
         *
         * @throws SearchQueryException when a regex doesn't compile or a status isn't one.
         */
        fun parse(text: String, fields: Boolean = true): SearchQuery = SearchQuery(text, Parser(text, fields).parse())
    }
}

/** A query that can't run, with a message for the person who wrote it. */
internal class SearchQueryException(message: String) : IllegalArgumentException(message)

/** Thrown when a search passes its [SearchBudget], or its thread is interrupted. */
internal class SearchTimeoutException(message: String) : RuntimeException(message)

/**
 * How long a search may take. Matching checks it before each field it looks at,
 * so a regex that backtracks over many bodies stops instead of holding a request
 * for minutes. One evaluation can't be stopped in the middle: Android's regex
 * engine runs natively and doesn't check for interruption.
 */
internal class SearchBudget(private val timeoutMs: Long) {
    private val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)

    fun check() {
        if (System.nanoTime() - deadline >= 0 || Thread.currentThread().isInterrupted) {
            throw SearchTimeoutException(
                "The search took longer than ${timeoutMs / MILLIS_PER_SECOND} s and stopped. " +
                    "A regex such as /(a+)+$/ or /.*a.*b/ can take very long on large bodies: make it more specific.",
            )
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 5_000

        private const val MILLIS_PER_SECOND = 1000
    }
}

/** The part of a transaction a term looks at. [ANY] is every part except the status. */
internal enum class SearchField(val prefix: String) {
    ANY(""),
    URL("url"),
    METHOD("method"),
    STATUS("status"),
    HEADER("header"),
    BODY("body"),
}

private class SearchTerm(
    private val negated: Boolean,
    private val field: SearchField,
    private val matcher: TextMatcher,
) {
    fun matches(tx: NetworkTransaction, budget: SearchBudget): Boolean = found(tx, budget) != negated

    fun matchesText(text: String, budget: SearchBudget): Boolean {
        budget.check()
        return matcher.find(text) != negated
    }

    private fun found(tx: NetworkTransaction, budget: SearchBudget): Boolean {
        val find = { text: String? ->
            budget.check()
            text != null && matcher.find(text)
        }
        val whole = { text: String? ->
            budget.check()
            text != null && matcher.matchesWhole(text)
        }
        val url = { find(tx.url) || find(tx.finalUrl) }
        val headers = { headersMatch(tx.requestHeaders, find) || headersMatch(tx.responseHeaders, find) }
        val bodies = { find(tx.requestBody) || find(tx.responseBody) }
        return when (field) {
            SearchField.ANY -> url() || find(tx.method) || headers() || bodies()
            SearchField.URL -> url()
            SearchField.METHOD -> whole(tx.method)
            SearchField.STATUS -> whole(tx.statusCode?.toString())
            SearchField.HEADER -> headers()
            SearchField.BODY -> bodies()
        }
    }

    // A header is the line "Name: value", so a phrase such as "cache-control: no" finds it.
    private fun headersMatch(headers: Map<String, String>?, find: (String) -> Boolean): Boolean =
        headers?.entries?.any { (name, value) -> find("$name: $value") } == true

    fun describe(): String =
        buildString {
            if (negated) append('-')
            if (field != SearchField.ANY) append(field.prefix).append(':')
            append(matcher.describe())
        }
}

private sealed interface TextMatcher {
    fun find(text: String): Boolean

    /** For `method:` and `status:`, where a word names the whole value. A regex still only has to be found. */
    fun matchesWhole(text: String): Boolean

    fun describe(): String
}

private class Literal(val value: String) : TextMatcher {
    private val folded = value.foldCase()

    override fun find(text: String): Boolean = text.containsFolded(folded)

    override fun matchesWhole(text: String): Boolean = text.length == folded.length && text.containsFolded(folded)

    override fun describe(): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

private class RegexMatcher(private val source: String) : TextMatcher {
    private val pattern =
        try {
            Pattern.compile(source, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        } catch (e: PatternSyntaxException) {
            val where = if (e.index >= 0) " at index ${e.index}" else ""
            throw SearchQueryException("The regex /$source/ is not valid: ${e.description}$where")
        }

    override fun find(text: String): Boolean = pattern.matcher(text).find()

    override fun matchesWhole(text: String): Boolean = find(text)

    override fun describe(): String = "/$source/"
}

/** A status code such as `404`, or a class such as `4xx`, where each `x` is any digit. */
private class StatusCodeMatcher(private val value: String) : TextMatcher {
    private val folded = value.lowercase()

    override fun find(text: String): Boolean = matchesWhole(text)

    override fun matchesWhole(text: String): Boolean =
        text.length == folded.length && folded.indices.all { folded[it] == 'x' || folded[it] == text[it] }

    override fun describe(): String = "\"$value\""
}

private class Parser(private val text: String, private val fields: Boolean) {
    private var pos = 0
    private val groups = mutableListOf<MutableList<SearchTerm>>()

    // A `|` or `OR` came before the next term, so that term joins the last group.
    private var joinNext = false

    fun parse(): List<List<SearchTerm>> {
        while (true) {
            skipSpaces()
            if (pos >= text.length) break
            if (text[pos] == '|') {
                pos++
                joinNext = true
                continue
            }
            when (wordAt(pos)) {
                "OR" -> {
                    pos += 2
                    joinNext = true
                    continue
                }
                "AND" -> {
                    pos += 3
                    continue
                }
            }
            val term = term() ?: continue
            if (joinNext && groups.isNotEmpty()) groups.last().add(term) else groups.add(mutableListOf(term))
            joinNext = false
        }
        return groups
    }

    // Null for an operator with no term after it, such as a NOT at the end.
    private fun term(): SearchTerm? {
        var negated = false
        if (wordAt(pos) == "NOT") {
            pos += 3
            skipSpaces()
            if (!startsTerm(pos)) return null
            negated = true
        }
        if (text[pos] == '-' && startsTerm(pos + 1)) {
            pos++
            negated = true
        }
        val field = fieldPrefix()
        return SearchTerm(negated, field, value(field))
    }

    private fun fieldPrefix(): SearchField {
        if (!fields) return SearchField.ANY
        for (field in SearchField.entries) {
            if (field == SearchField.ANY) continue
            val end = pos + field.prefix.length
            if (text.regionMatches(pos, field.prefix, 0, field.prefix.length, ignoreCase = true) &&
                end < text.length && text[end] == ':' && startsTerm(end + 1)
            ) {
                pos = end + 1
                return field
            }
        }
        return SearchField.ANY
    }

    private fun value(field: SearchField): TextMatcher {
        val matcher =
            when {
                text[pos] == '"' -> quoted()?.let { Literal(it) }
                text[pos] == '/' -> slashRegex()?.let { RegexMatcher(it) }
                text.regionMatches(pos, REGEX_PREFIX, 0, REGEX_PREFIX.length, ignoreCase = true) &&
                    pos + REGEX_PREFIX.length < text.length && !text[pos + REGEX_PREFIX.length].isWhitespace() -> {
                    pos += REGEX_PREFIX.length
                    RegexMatcher(if (text[pos] == '"') quotedToEnd() else tokenToSpace())
                }
                else -> null
            } ?: Literal(word())
        if (field == SearchField.STATUS && matcher is Literal) {
            val value = matcher.value
            if (!STATUS_CODE.matches(value)) {
                throw SearchQueryException(
                    "status: takes a code such as 404 or a class such as 4xx, not \"$value\". " +
                        "To search for the text, put all of it in quotes: \"status:$value\"",
                )
            }
            return StatusCodeMatcher(value)
        }
        return matcher
    }

    // A quote that opens a phrase, or null when it is text, as in "id":42, where the
    // closing quote isn't followed by a space. A quote that is never closed runs to the end.
    private fun quoted(): String? {
        val close = closingQuote(pos + 1)
        if (close < 0) {
            return if (pos + 1 < text.length) quotedToEnd() else null
        }
        if (close == pos + 1 || !endsTerm(close + 1)) return null
        val phrase = unescape(text.substring(pos + 1, close))
        pos = close + 1
        return phrase
    }

    private fun quotedToEnd(): String {
        val close = closingQuote(pos + 1)
        val end = if (close < 0) text.length else close
        val value = unescape(text.substring(pos + 1, end))
        pos = if (close < 0) text.length else close + 1
        return value
    }

    // From a slash to the next one, which must end the term, is a regex, and it can
    // have spaces. A slash between them must be escaped as \/, so a path such as
    // /api/v1/ stays text: its second slash doesn't end a term.
    private fun slashRegex(): String? {
        var close = pos + 1
        while (close < text.length && text[close] != '/') close += if (text[close] == '\\') 2 else 1
        if (close >= text.length || close == pos + 1 || !endsTerm(close + 1)) return null
        val source = text.substring(pos + 1, close)
        pos = close + 1
        return source
    }

    private fun tokenToSpace(): String {
        val end = tokenEnd(pos)
        val token = text.substring(pos, end)
        pos = end
        return token
    }

    private fun word(): String {
        val start = pos
        while (pos < text.length && !text[pos].isWhitespace() && text[pos] != '|') pos++
        // term() starts a word only on a character that isn't a space or |, so this
        // never runs; it keeps a broken caller from looping forever.
        if (pos == start) pos++
        return text.substring(start, pos)
    }

    private fun closingQuote(from: Int): Int {
        var i = from
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2
                '"' -> return i
                else -> i++
            }
        }
        return -1
    }

    private fun unescape(value: String): String = value.replace("\\\"", "\"")

    private fun wordAt(at: Int): String {
        var end = at
        while (end < text.length && !text[end].isWhitespace() && text[end] != '|') end++
        return text.substring(at, end)
    }

    private fun tokenEnd(from: Int): Int {
        var end = from
        while (end < text.length && !text[end].isWhitespace()) end++
        return end
    }

    private fun startsTerm(at: Int): Boolean = at < text.length && !text[at].isWhitespace() && text[at] != '|'

    private fun endsTerm(at: Int): Boolean = at >= text.length || text[at].isWhitespace() || text[at] == '|'

    private fun skipSpaces() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    private companion object {
        const val REGEX_PREFIX = "regex:"
        val STATUS_CODE = Regex("[0-9xX]{3}")
    }
}
