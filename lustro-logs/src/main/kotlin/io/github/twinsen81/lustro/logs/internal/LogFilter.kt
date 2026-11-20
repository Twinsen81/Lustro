package io.github.twinsen81.lustro.logs.internal

import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * The filters of `GET entries` and `GET text`: the lowest level, a regex found
 * in the tag, a text the message contains (ignoring case), a regex found in
 * the message, and a time range in epoch milliseconds, both ends included.
 */
internal class LogFilter(
    private val minRank: Int,
    private val tag: Pattern?,
    private val text: String?,
    private val message: Pattern?,
    private val from: Long = Long.MIN_VALUE,
    private val to: Long = Long.MAX_VALUE,
) {
    /** The parameter to blame when the filters run out of time: the regex, when only one is set. */
    val regexField: String?
        get() =
            when {
                tag != null && message != null -> null
                message != null -> "mq"
                else -> "tag"
            }

    /**
     * Whether [entry] passes. Throws [FilterTimeoutException] once [budget]
     * runs out, because a regex can take very long, and Android's regex engine
     * runs natively and can't be stopped in the middle of one match.
     */
    fun matches(entry: LogEntry, budget: FilterBudget): Boolean {
        if (LogLevels.rank(entry.level) < minRank) return false
        // A folded line stands for its repeats too, from at to lastAt.
        if (entry.lastAt < from || entry.at > to) return false
        if (text != null && !entry.message.contains(text, ignoreCase = true)) return false
        if (tag == null && message == null) return true
        budget.check()
        if (tag != null && !tag.matcher(entry.tag).find()) return false
        return message == null || message.matcher(entry.message).find()
    }

    companion object {
        const val MAX_REGEX_LENGTH: Int = 200

        /**
         * Reads the filters from the values of the query parameters, which the route
         * declarations have checked: an empty [level], [text], or regex is no filter.
         *
         * @throws BadParameterException for a level that is not one of V, D, I, W, E,
         *   and F, a regex that doesn't compile, or a [from] after [to].
         */
        fun from(
            level: String?,
            tag: String?,
            text: String?,
            message: String?,
            from: Long = Long.MIN_VALUE,
            to: Long = Long.MAX_VALUE,
        ): LogFilter {
            val given = level?.takeIf { it.isNotEmpty() }
            val rank = given?.singleOrNull()?.let { LogLevels.rank(it.uppercaseChar()) } ?: -1
            if (given != null && rank < 0) throw BadParameterException("level must be one of V, D, I, W, E, and F", "level")
            if (from > to) throw BadParameterException("from must not be after to", "from")
            return LogFilter(
                minRank = rank.coerceAtLeast(0),
                tag = regex(tag, "tag"),
                text = text?.takeIf { it.isNotEmpty() },
                message = regex(message, "mq"),
                from = from,
                to = to,
            )
        }

        private fun regex(source: String?, name: String): Pattern? {
            if (source.isNullOrEmpty()) return null
            return try {
                Pattern.compile(source)
            } catch (e: PatternSyntaxException) {
                val where = if (e.index >= 0) " at index ${e.index}" else ""
                throw BadParameterException("The regex \"$source\" is not valid: ${e.description}$where", name)
            }
        }
    }
}

/** A parameter that a route can't use, with the parameter at fault. */
internal class BadParameterException(message: String, val field: String) : IllegalArgumentException(message)

/** Thrown when filtering passes its [FilterBudget], or its thread is interrupted. */
internal class FilterTimeoutException(message: String) : RuntimeException(message)

/** How long the filters of one response may take. */
internal class FilterBudget(private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
    private val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)

    fun check() {
        if (System.nanoTime() - deadline >= 0 || Thread.currentThread().isInterrupted) {
            throw FilterTimeoutException(
                "The filters took longer than ${timeoutMs / MILLIS_PER_SECOND} s and stopped. " +
                    "A regex such as (a+)+$ can take very long: make tag or mq more specific.",
            )
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 5_000

        private const val MILLIS_PER_SECOND = 1000
    }
}
