package io.github.twinsen81.lustro.network

/**
 * A captured request or response body, as reported through [NetworkCaptureSink].
 *
 * At most one of [text] and [bytes] is set. [text] holds a body the [Redactor]
 * can read, decoded; [bytes] holds one it can't, such as an image, and is
 * stored as it arrived, so report [bytes] only for a type that is useful to
 * keep and seldom carries a secret. The built-in adapters keep image bodies
 * this way (SVG is text), and drop other non-text bodies. When both are set, the
 * built-in sink keeps [text] and drops [bytes], and it treats empty [bytes] as
 * no body. Both are `null` for a body that wasn't captured: a one-shot or
 * duplex body, or a type neither covers.
 *
 * [text] and [bytes] hold the body with its content coding undone: the
 * built-in adapters inflate a `gzip` or `deflate` body first, and report one in
 * a coding they can't undo, such as `br`, with both `null`. [truncated] marks
 * that this decoded body exceeded the capture cap, so [text] or [bytes] only
 * holds a prefix of it.
 *
 * [byteSize] is the size of the body as the adapter received it, before any
 * decoding, when known: from its `Content-Length`, or as counted. For a body the
 * adapter inflated it is the compressed size, so it can be smaller than [text];
 * for a truncated body it is the full size, so it can be larger. The built-in
 * sink counts a body toward its capture budget as the larger of [byteSize] and
 * what it keeps.
 *
 * Deliberately NOT a `data class` so the public surface stays stable and
 * mirrorable by `:lustro-noop`.
 */
public class CapturedBody @JvmOverloads constructor(
    /** The decoded captured body, or `null` for binary/one-shot/not-captured bodies. */
    public val text: String?,
    /** `true` when the decoded body exceeded the capture cap and [text] or [bytes] is a prefix of it. */
    public val truncated: Boolean = false,
    /** The body's size in bytes before any decoding, such as its compressed size, or `null` when not known. */
    public val byteSize: Long? = null,
    /**
     * The captured bytes of a body the [Redactor] can't read, or `null`. Not
     * copied: the sink keeps this array, so don't change it after passing it.
     */
    public val bytes: ByteArray? = null,
)
