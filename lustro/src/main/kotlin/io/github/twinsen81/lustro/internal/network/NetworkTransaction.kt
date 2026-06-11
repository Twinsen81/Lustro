package io.github.twinsen81.lustro.internal.network

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
    // Request
    val method: String,
    val url: String,
    val requestHeaders: Map<String, String> = emptyMap(),
    val requestBody: String? = null,
    val requestBodyTruncated: Boolean = false,
    val requestContentType: String? = null,
    val requestBodyBytes: Long? = null,
    // Response
    val statusCode: Int? = null,
    val responseHeaders: Map<String, String>? = null,
    val responseBody: String? = null,
    val responseBodyTruncated: Boolean = false,
    val responseContentType: String? = null,
    val responseBodyBytes: Long? = null,
    val responseComplete: Boolean = false,
    // Meta
    val isMocked: Boolean = false,
    val error: String? = null,
)
