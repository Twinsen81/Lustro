package io.github.twinsen81.lustro.network

/**
 * A response the HTTP client answered with another request before the final
 * response: a redirect it followed, or an authentication challenge it
 * answered. Reported in [CapturedResponse.priorResponses].
 *
 * Deliberately NOT a `data class` so the public surface stays stable.
 */
public class CapturedPriorResponse(
    /** The URL of the request this response answered, before redaction. */
    public val url: String,
    /** The status code of this response, such as `301` or `401`. */
    public val statusCode: Int,
)
