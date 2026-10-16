package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * A search as the Network tab's search fields and the `search` and `regex`
 * parameters give it.
 *
 * Plain, the text is found as typed, ignoring case, in one of a transaction's
 * fields. With [regex], each word of the text, up to a space, is a Java regular
 * expression found ignoring case: every word must be found, each in any field,
 * and a word that starts with `-` must be found in none. A regex writes a space
 * as `\s` or `\x20`, and a leading minus as `[-]`.
 *
 * A transaction's fields are its URL, its final URL, its method, each request
 * and response header as the line `Name: value`, and its text bodies, all as
 * stored after redaction.
 */
internal class SearchQuery private constructor(
    val text: String,
    val regex: Boolean,
    private val terms: List<Term>,
) {
    fun matches(tx: NetworkTransaction, budget: SearchBudget): Boolean =
        terms.all { term -> inAnyField(tx, term, budget) != term.negated }

    /** Whether [text] matches, for a log whose entries have only their text. Null text never does. */
    fun matchesText(text: String?, budget: SearchBudget): Boolean =
        text != null && terms.all { term -> found(text, term, budget) != term.negated }

    private fun inAnyField(tx: NetworkTransaction, term: Term, budget: SearchBudget): Boolean =
        found(tx.url, term, budget) ||
            found(tx.finalUrl, term, budget) ||
            found(tx.method, term, budget) ||
            headersMatch(tx.requestHeaders, term, budget) ||
            headersMatch(tx.responseHeaders, term, budget) ||
            found(tx.requestBody, term, budget) ||
            found(tx.responseBody, term, budget)

    private fun found(text: String?, term: Term, budget: SearchBudget): Boolean {
        budget.check()
        return text != null && term.find(text)
    }

    // A header is the line "Name: value", so "cache-control: no" finds it.
    private fun headersMatch(headers: Map<String, String>?, term: Term, budget: SearchBudget): Boolean =
        headers?.entries?.any { (name, value) -> found("$name: $value", term, budget) } == true

    private class Term(val negated: Boolean, val find: (String) -> Boolean)

    companion object {
        /**
         * The search of a request's `search` and `regex` parameters, or null when
         * `search` is blank, which leaves nothing out.
         *
         * @throws SearchQueryException when `regex` is not `true` or `false`, or a regex doesn't compile.
         */
        fun fromParams(search: String?, regex: String?): SearchQuery? {
            val isRegex =
                when (regex) {
                    null, "", "false" -> false
                    "true" -> true
                    else -> throw SearchQueryException("regex must be true or false", field = "regex")
                }
            return search?.takeIf { it.isNotBlank() }?.let { parse(it, isRegex) }
        }

        /** @throws SearchQueryException when [regex] is true and a word doesn't compile. */
        fun parse(text: String, regex: Boolean = false): SearchQuery {
            if (!regex) {
                val needle = text.foldCase()
                return SearchQuery(text, regex = false, listOf(Term(negated = false) { it.containsFolded(needle) }))
            }
            val terms =
                text.trim().split(WHITESPACE).filter { it.isNotEmpty() }.map { word ->
                    // A minus on its own is the character.
                    val negated = word.length > 1 && word.startsWith('-')
                    val pattern = compile(if (negated) word.substring(1) else word)
                    Term(negated) { pattern.matcher(it).find() }
                }
            return SearchQuery(text, regex = true, terms)
        }

        private fun compile(source: String): Pattern =
            try {
                Pattern.compile(source, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
            } catch (e: PatternSyntaxException) {
                val where = if (e.index >= 0) " at index ${e.index}" else ""
                throw SearchQueryException("The regex \"$source\" is not valid: ${e.description}$where")
            }

        private val WHITESPACE = Regex("\\s+")
    }
}

/**
 * Answers [request] with [respond], given the search of its `search` and
 * `regex` parameters, or with a 400 on the parameter at fault when the search
 * can't run or passes its time.
 */
internal inline fun respondWithSearch(request: DebugRequest, respond: (SearchQuery?) -> DebugResponse): DebugResponse {
    val search =
        try {
            SearchQuery.fromParams(request.queryParam("search"), request.queryParam("regex"))
        } catch (e: SearchQueryException) {
            return DebugResponse.error(e.message.orEmpty(), field = e.field)
        }
    return try {
        respond(search)
    } catch (e: SearchTimeoutException) {
        DebugResponse.error(e.message.orEmpty(), field = "search")
    }
}

/** A search that can't run, with a message for the person who wrote it and the parameter at fault. */
internal class SearchQueryException(message: String, val field: String = "search") : IllegalArgumentException(message)

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
                    "A regex such as (a+)+$ or .*a.*b can take very long on large bodies: make it more specific.",
            )
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 5_000

        private const val MILLIS_PER_SECOND = 1000
    }
}
