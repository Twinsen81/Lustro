package io.github.twinsen81.lustro.sample

import okhttp3.Request

/** The header the sample sets on a request it wants left out of the debug console. */
internal const val NO_CAPTURE_HEADER: String = "X-No-Capture"

internal data class DemoRequestSpec(
    val label: String,
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
) {
    fun buildRequest(): Request =
        Request.Builder()
            .url(url)
            .method(method, null)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
}

