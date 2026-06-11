package io.github.twinsen81.lustro.network

/**
 * A captured request or response body, as reported through [NetworkCaptureSink].
 *
 * Models a captured body: [text] is the already-decoded captured payload
 * (`null` for binary, one-shot, duplex, or otherwise not-captured bodies);
 * [truncated] marks that the full body exceeded the capture cap so [text] only
 * holds a prefix; [byteSize] is the FULL body size in bytes when known (which may
 * exceed `text`'s length, e.g. for multi-byte UTF-8 or when only a declared
 * Content-Length is available). This restores the truncation badge and true byte
 * count that a bytes-only SPI would lose.
 *
 * Deliberately NOT a `data class` so the public surface stays stable and
 * mirrorable by `:lustro-noop`.
 */
public class CapturedBody @JvmOverloads constructor(
    /** The decoded captured body, or `null` for binary/one-shot/not-captured bodies. */
    public val text: String?,
    /** `true` when the full body exceeded the capture cap and [text] is a prefix. */
    public val truncated: Boolean = false,
    /** The full body size in bytes when known; may exceed [text]'s length, or `null`. */
    public val byteSize: Long? = null,
)
