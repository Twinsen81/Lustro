package io.github.twinsen81.lustro.sample

import okhttp3.Request

internal data class DemoRequestSpec(
    val label: String,
    val method: String,
    val url: String,
) {
    fun buildRequest(): Request =
        Request.Builder()
            .url(url)
            .method(method, null)
            .build()
}

