package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.network.MockRule

/**
 * Internal implementation of the read-only [MockRule] api interface. [matches]
 * looks for the pattern anywhere in the URL: as a substring by default, or as a
 * regex when the pattern is prefixed with `regex:`. A regex that must match the
 * whole URL anchors itself with `^` and `$`.
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
    // Compile the regex once per rule rather than on every request. MockRuleCodec
    // rejects a pattern that doesn't compile, but a rule built in code skips it, so
    // an invalid pattern caches a null and [matches] treats it as a no-match
    // without retrying the failed compile. Only materialised for regex: patterns.
    private val compiledRegex: Regex? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!urlPattern.startsWith(REGEX_PREFIX)) {
            null
        } else {
            try {
                Regex(urlPattern.removePrefix(REGEX_PREFIX))
            } catch (_: Exception) {
                null
            }
        }
    }

    fun matches(url: String, requestMethod: String): Boolean {
        if (!enabled) return false
        if (method != null && !method.equals(requestMethod, ignoreCase = true)) return false
        return if (urlPattern.startsWith(REGEX_PREFIX)) {
            compiledRegex?.containsMatchIn(url) ?: false
        } else {
            url.contains(urlPattern)
        }
    }

    companion object {
        const val REGEX_PREFIX: String = "regex:"
    }
}
