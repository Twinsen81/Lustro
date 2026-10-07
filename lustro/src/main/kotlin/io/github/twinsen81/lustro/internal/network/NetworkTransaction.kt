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
    // Epoch milliseconds, like completedAt.
    val startedAt: Long,
    // Which request started first. A capture taken on the calling thread can be stored
    // before an earlier one that is still waiting for the capture thread, so where the
    // order requests started in matters, it can't be read off the list.
    val startOrder: Long = 0,
    // Set once the response is complete or the request failed.
    val completedAt: Long? = null,
    val durationMs: Long? = null,
    // How long the global throttle held the request before it was sent, or null
    // when it wasn't throttled. Not part of durationMs.
    val throttledMs: Long? = null,
    val categories: List<String> = emptyList(),
    val method: String,
    val url: String,
    val requestHeaders: Map<String, String> = emptyMap(),
    val requestBody: String? = null,
    // Set only when requestBody isn't, and stored as it arrived. Never changed
    // after capture, so copies of this snapshot can share the array.
    val requestBinaryBody: ByteArray? = null,
    val requestBodyTruncated: Boolean = false,
    val requestContentType: String? = null,
    val requestBodyBytes: Long? = null,
    val protocol: String? = null,
    val statusCode: Int? = null,
    val responseHeaders: Map<String, String>? = null,
    val responseBody: String? = null,
    // As requestBinaryBody.
    val responseBinaryBody: ByteArray? = null,
    val responseBodyTruncated: Boolean = false,
    val responseContentType: String? = null,
    val responseBodyBytes: Long? = null,
    val responseComplete: Boolean = false,
    val isMocked: Boolean = false,
    // Which adapter captured the request, and so whether a mock rule can answer it.
    val source: CaptureSource = CaptureSource.OKHTTP,
    val error: String? = null,
    // The URL the response came from when it isn't url, as after a redirect. Redacted.
    val finalUrl: String? = null,
    // The redirects and auth challenges the client followed up on, oldest first. Redacted.
    val priorResponses: List<PriorResponse> = emptyList(),
) {
    // Polls repeat the same search over a list that mostly hasn't changed, so
    // each snapshot remembers its last result. The store replaces a snapshot
    // through copy() when it changes, and the copy starts without one.
    private val lastSearch = AtomicReference<SearchResult?>()

    /**
     * Whether the URL, the final URL, the method, or either body contains [needle] ignoring
     * case. [needle] must already be folded with [foldCase].
     */
    fun matchesSearch(needle: String): Boolean {
        lastSearch.get()?.let { if (it.needle == needle) return it.matched }
        val matched =
            url.containsFolded(needle) ||
                finalUrl?.containsFolded(needle) == true ||
                method.containsFolded(needle) ||
                requestBody?.containsFolded(needle) == true ||
                responseBody?.containsFolded(needle) == true
        lastSearch.set(SearchResult(needle, matched))
        return matched
    }

    private class SearchResult(val needle: String, val matched: Boolean)
}

/** A response the client answered with another request, as [NetworkTransaction.priorResponses] lists it. */
internal data class PriorResponse(val url: String, val statusCode: Int)

/** The capture adapter that recorded a transaction, by the name the wire protocol gives it. */
internal enum class CaptureSource(val wireName: String) {
    /** Lustro's OkHttp interceptor. Mock rules and the throttle apply to its requests. */
    OKHTTP("okhttp"),

    /** Platform `HttpURLConnection` capture, which only records: no mock rule or throttle applies. */
    PLATFORM("platform"),

    /** The app's own adapter, through `Lustro.networkCaptureSink()`. A mock rule applies only if the adapter asks for it. */
    APP("app"),
}

/** Lowercases each char on its own, the folding [NetworkTransaction.matchesSearch] expects. */
internal fun String.foldCase(): String = String(CharArray(length) { this[it].foldCase() })

// Folds as it scans instead of lowercasing a copy of every body it searches.
// contains(ignoreCase = true) avoids the copy too, but it calls regionMatches at
// every offset and measured slower than the copy on Android.
internal fun String.containsFolded(needle: String): Boolean {
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
