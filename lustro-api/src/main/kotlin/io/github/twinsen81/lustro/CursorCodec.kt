package io.github.twinsen81.lustro

import java.util.Base64

/**
 * Encodes and decodes the opaque cursor tokens used by the wire protocol —
 * the cursor envelope's `cursor` and the pagination envelope's `nextCursor`.
 *
 * A cursor wraps a server-side change sequence number. The encoding (URL-safe
 * Base64 of the decimal sequence) is an implementation detail: clients must
 * treat cursors as opaque and echo them back unchanged. On the server side,
 * [decode] returning `null` — absent, malformed, or foreign cursor — is the
 * signal to fall back to a full `reset` snapshot.
 *
 * Tabs serving snapshot-style observable lists rarely need this directly; use
 * [DebugResponse.cursorEnvelope], which applies the full envelope contract.
 */
public object CursorCodec {
    /** Encodes the change [sequence] as an opaque cursor token. */
    @JvmStatic
    public fun encode(sequence: Long): String =
        Base64.getUrlEncoder().encodeToString(sequence.toString().toByteArray(Charsets.UTF_8))

    /**
     * Decodes a cursor previously produced by [encode] back into its change
     * sequence, or `null` when [cursor] is `null` or not a valid token.
     */
    @JvmStatic
    public fun decode(cursor: String?): Long? {
        if (cursor == null) return null
        return try {
            String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8).toLongOrNull()
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
