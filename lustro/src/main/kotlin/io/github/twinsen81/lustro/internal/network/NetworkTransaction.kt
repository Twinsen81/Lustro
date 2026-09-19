package io.github.twinsen81.lustro.internal.network

import java.util.concurrent.atomic.AtomicReference

/**
 * Snapshot of a single HTTP request/response pair captured by the network
 * interceptor or platform capture. The fixed category enum is replaced with
 * classifier-produced string [categories]. Initially created with request-only
 * fields; response fields are filled in asynchronously via the store.
 */
internal data class NetworkTransaction(
    val id: String,
    val timestamp: Long,
    val durationMs: Long? = null,
    val categories: List<String> = emptyList(),
    val method: String,
    val url: String,
    val requestHeaders: Map<String, String> = emptyMap(),
    val requestBody: String? = null,
    val requestBodyTruncated: Boolean = false,
    val requestContentType: String? = null,
    val requestBodyBytes: Long? = null,
    val statusCode: Int? = null,
    val responseHeaders: Map<String, String>? = null,
    val responseBody: String? = null,
    val responseBodyTruncated: Boolean = false,
    val responseContentType: String? = null,
    val responseBodyBytes: Long? = null,
    val responseComplete: Boolean = false,
    val isMocked: Boolean = false,
    val error: String? = null,
) {
    // Polls repeat the same search over a list that mostly hasn't changed, so
    // each snapshot remembers its last result. The store replaces a snapshot
    // through copy() when it changes, and the copy starts without one.
    private val lastSearch = AtomicReference<SearchResult?>()

    /**
     * Whether the URL, method, or either body contains [needle] ignoring
     * case. [needle] must already be folded with [foldCase].
     */
    fun matchesSearch(needle: String): Boolean {
        lastSearch.get()?.let { if (it.needle == needle) return it.matched }
        val matched =
            url.containsFolded(needle) ||
                method.containsFolded(needle) ||
                requestBody?.containsFolded(needle) == true ||
                responseBody?.containsFolded(needle) == true
        lastSearch.set(SearchResult(needle, matched))
        return matched
    }

    private class SearchResult(val needle: String, val matched: Boolean)
}

/** Lowercases each char on its own, the folding [NetworkTransaction.matchesSearch] expects. */
internal fun String.foldCase(): String = String(CharArray(length) { this[it].foldCase() })

// Folds as it scans instead of lowercasing a copy of every body it searches.
// contains(ignoreCase = true) avoids the copy too, but it calls regionMatches at
// every offset and measured slower than the copy on Android.
private fun String.containsFolded(needle: String): Boolean {
    if (needle.isEmpty()) return true
    val first = needle[0]
    for (start in 0..length - needle.length) {
        if (this[start].foldCase() != first) continue
        var matched = 1
        while (matched < needle.length && this[start + matched].foldCase() == needle[matched]) matched++
        if (matched == needle.length) return true
    }
    return false
}

private fun Char.foldCase(): Char =
    when {
        this in 'A'..'Z' -> this + ('a' - 'A')
        this < '\u0080' -> this
        else -> lowercaseChar()
    }
