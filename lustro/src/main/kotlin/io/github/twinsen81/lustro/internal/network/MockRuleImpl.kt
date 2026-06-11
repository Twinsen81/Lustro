package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.network.MockRule

/**
 * Internal implementation of the read-only [MockRule] api interface. [matches]
 * does a substring match by default, or a regex when the pattern is prefixed with
 * `regex:`.
 */
internal data class MockRuleImpl(
    override val id: String,
    override val enabled: Boolean = true,
    override val name: String,
    override val urlPattern: String,
    override val method: String? = null,
    override val statusCode: Int = 200,
    override val responseHeaders: Headers = Headers.EMPTY,
    override val responseBody: String = "",
    override val hitCount: Int = 0,
) : MockRule {
    // Compile the regex once per rule rather than on every request. An invalid
    // pattern caches a null so [matches] treats it as a no-match without retrying
    // the failed compile. Only materialised for regex: patterns.
    private val compiledRegex: Regex? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!urlPattern.startsWith("regex:")) {
            null
        } else {
            try {
                Regex(urlPattern.removePrefix("regex:"))
            } catch (_: Exception) {
                null
            }
        }
    }

    fun matches(url: String, requestMethod: String): Boolean {
        if (!enabled) return false
        if (method != null && !method.equals(requestMethod, ignoreCase = true)) return false
        return if (urlPattern.startsWith("regex:")) {
            compiledRegex?.let { url.matches(it) } ?: false
        } else {
            url.contains(urlPattern)
        }
    }
}
