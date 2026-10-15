package io.github.twinsen81.lustro

import kotlin.io.encoding.Base64
import kotlin.random.Random

/**
 * Encodes and decodes the opaque cursor tokens used by the wire protocol —
 * the cursor envelope's `cursor` and the pagination envelope's `nextCursor`.
 *
 * A cursor wraps a server-side change sequence number, optionally together
 * with the epoch of the store that issued it. The encoding (URL-safe Base64)
 * is an implementation detail: clients must treat cursors as opaque and echo
 * them back unchanged. On the server side, [decode] returning `null` —
 * absent, malformed, or foreign cursor — is the signal to fall back to a full
 * `reset` snapshot.
 *
 * An epoch is a random value a store picks when it is created. A store's
 * sequence starts over with the store, for example when the app process
 * restarts, but clients keep polling with the cursors they hold. Decoding
 * with the epoch rejects those stale cursors instead of mistaking one for
 * the new store's current sequence.
 *
 * Tabs serving snapshot-style observable lists rarely need this directly; use
 * [DebugResponse.cursorEnvelope], which applies the full envelope contract.
 */
public object CursorCodec {
    // Stands in for the epoch of tabs that don't pass one to cursorEnvelope, so
    // their cursors still turn foreign when the process restarts.
    internal val processEpoch: Long = Random.nextLong()

    /** Encodes the change [sequence] as an opaque cursor token. */
    @JvmStatic
    public fun encode(sequence: Long): String = encodeText(sequence.toString())

    /**
     * Decodes a cursor previously produced by [encode] back into its change
     * sequence, or `null` when [cursor] is `null` or not a valid token.
     */
    @JvmStatic
    public fun decode(cursor: String?): Long? = decodeText(cursor)?.toLongOrNull()

    /**
     * Encodes the change [sequence] of the store identified by [epoch] as an
     * opaque cursor token.
     */
    @JvmStatic
    public fun encode(sequence: Long, epoch: Long): String = encodeText("$epoch$EPOCH_SEPARATOR$sequence")

    /**
     * Decodes a cursor previously produced by [encode] with the same [epoch]
     * back into its change sequence, or `null` when [cursor] is `null`, not a
     * valid token, or issued under another epoch or none.
     */
    @JvmStatic
    public fun decode(cursor: String?, epoch: Long): Long? {
        val text = decodeText(cursor) ?: return null
        val separator = text.indexOf(EPOCH_SEPARATOR)
        if (separator < 0 || text.substring(0, separator).toLongOrNull() != epoch) return null
        return text.substring(separator + 1).toLongOrNull()
    }

    private fun encodeText(text: String): String = CODEC.encode(text.toByteArray(Charsets.UTF_8))

    private fun decodeText(cursor: String?): String? {
        if (cursor == null) return null
        return try {
            String(CODEC.decode(cursor), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private const val EPOCH_SEPARATOR = ':'

    // The Kotlin standard library's codec runs on every API level, where
    // java.util.Base64 needs API 26. It accepts a cursor whose padding a client
    // dropped, for example by trimming `=` from a query value.
    private val CODEC: Base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
}
