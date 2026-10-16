package io.github.twinsen81.lustro.internal.network

import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * A search as the Network tab's search fields and the `search` and `regex`
 * parameters give it: text that a field contains, ignoring case, or with
 * [regex] a Java regular expression found in a field, ignoring case.
 *
 * A transaction's fields are its URL, its final URL, its method, each request
 * and response header as the line `Name: value`, and its text bodies, all as
 * stored after redaction.
 */
internal class SearchQuery private constructor(
    val text: String,
    val regex: Boolean,
    private val find: (String) -> Boolean,
) {
    fun matches(tx: NetworkTransaction, budget: SearchBudget): Boolean =
        found(tx.url, budget) ||
            found(tx.finalUrl, budget) ||
            found(tx.method, budget) ||
            headersMatch(tx.requestHeaders, budget) ||
            headersMatch(tx.responseHeaders, budget) ||
            found(tx.requestBody, budget) ||
            found(tx.responseBody, budget)

    /** Whether [text] matches, for a log whose entries have only their text. Null text never does. */
    fun matchesText(text: String?, budget: SearchBudget): Boolean = found(text, budget)

    private fun found(text: String?, budget: SearchBudget): Boolean {
        budget.check()
        return text != null && find(text)
    }

    // A header is the line "Name: value", so "cache-control: no" finds it.
    private fun headersMatch(headers: Map<String, String>?, budget: SearchBudget): Boolean =
        headers?.entries?.any { (name, value) -> found("$name: $value", budget) } == true

    companion object {
        /**
         * The search of a request's `search` and `regex` parameters, or null when
         * `search` is blank, which leaves nothing out.
         *
         * @throws SearchQueryException when `regex` is not `true` or `false`, or the regex doesn't compile.
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

        /** @throws SearchQueryException when [regex] is true and [text] doesn't compile. */
        fun parse(text: String, regex: Boolean = false): SearchQuery {
            if (!regex) {
                val needle = text.foldCase()
                return SearchQuery(text, regex = false) { it.containsFolded(needle) }
            }
            val pattern =
                try {
                    Pattern.compile(text, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
                } catch (e: PatternSyntaxException) {
                    val where = if (e.index >= 0) " at index ${e.index}" else ""
                    throw SearchQueryException("The regex is not valid: ${e.description}$where")
                }
            return SearchQuery(text, regex = true) { pattern.matcher(it).find() }
        }
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
