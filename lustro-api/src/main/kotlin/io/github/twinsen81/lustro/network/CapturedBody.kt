package io.github.twinsen81.lustro.network

/**
 * A captured request or response body, as reported through [NetworkCaptureSink].
 *
 * At most one of [text] and [bytes] is set. [text] holds a body the [Redactor]
 * can read, decoded; [bytes] holds one it can't, such as an image, and is
 * stored as it arrived, so report [bytes] only for a type that is useful to
 * keep and seldom carries a secret. The built-in adapters keep image bodies
 * this way (SVG is text), and drop other non-text bodies. When both are set, the
 * built-in sink keeps [text] and drops [bytes]. Both are `null` for a body
 * that wasn't captured: a one-shot or duplex body, or a type neither covers.
 *
 * [truncated] marks that the full body exceeded the capture cap, so [text] or
 * [bytes] only holds a prefix; [byteSize] is the FULL body size in bytes when
 * known (which may exceed `text`'s length, e.g. for multi-byte UTF-8 or when
 * only a declared Content-Length is available). This restores the truncation
 * badge and true byte count that a bytes-only SPI would lose.
 *
 * Deliberately NOT a `data class` so the public surface stays stable and
 * mirrorable by `:lustro-noop`.
 */
public class CapturedBody @JvmOverloads constructor(
    /** The decoded captured body, or `null` for binary/one-shot/not-captured bodies. */
    public val text: String?,
    /** `true` when the full body exceeded the capture cap and [text] or [bytes] is a prefix. */
    public val truncated: Boolean = false,
    /** The full body size in bytes when known; may exceed [text]'s length, or `null`. */
    public val byteSize: Long? = null,
    /**
     * The captured bytes of a body the [Redactor] can't read, or `null`. Not
     * copied: the sink keeps this array, so don't change it after passing it.
     */
    public val bytes: ByteArray? = null,
)
