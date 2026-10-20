package io.github.twinsen81.lustro.sample

import okhttp3.Request

/** The header the sample sets on a request it wants left out of the debug console. */
internal const val NO_CAPTURE_HEADER: String = "X-No-Capture"

/**
 * [label] names the action for the `request` launch extra; [chip] is the shorter
 * text on screen, and [detail] says what the request shows in the console.
 */
internal data class DemoRequestSpec(
    val label: String,
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val chip: String = label,
    val detail: String? = null,
) {
    fun buildRequest(): Request =
        Request.Builder()
            .url(url)
            .method(method, null)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
}

