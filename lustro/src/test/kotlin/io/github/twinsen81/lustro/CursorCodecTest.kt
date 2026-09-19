package io.github.twinsen81.lustro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Tests for [CursorCodec] — the opaque wire-protocol cursor token codec. */
class CursorCodecTest {
    @Test
    fun `encode-decode round-trips sequences`() {
        for (seq in listOf(0L, 1L, 42L, 999_999L, Long.MAX_VALUE)) {
            assertEquals(seq, CursorCodec.decode(CursorCodec.encode(seq)))
        }
    }

    @Test
    fun `decode returns null for null cursor`() {
        assertNull(CursorCodec.decode(null))
    }

    @Test
    fun `decode returns null for invalid base64`() {
        assertNull(CursorCodec.decode("not base64!!"))
    }

    @Test
    fun `decode returns null for a non-numeric payload`() {
        // "abc" base64url-encoded.
        assertNull(CursorCodec.decode("YWJj"))
    }

    @Test
    fun `token format is padded url-safe base64 of the decimal sequence`() {
        // Guards against accidental encoding changes (e.g. dropping padding or
        // switching alphabets), which would spuriously reset live pollers.
        assertEquals("NDI=", CursorCodec.encode(42))
        assertEquals(42L, CursorCodec.decode("NDI="))
    }

    @Test
    fun `epoch cursors round-trip under the same epoch`() {
        for (epoch in listOf(0L, 7L, -7L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            for (seq in listOf(0L, 42L, Long.MAX_VALUE)) {
                assertEquals(seq, CursorCodec.decode(CursorCodec.encode(seq, epoch), epoch))
            }
        }
    }

    @Test
    fun `decode with an epoch rejects cursors from another epoch`() {
        assertNull(CursorCodec.decode(CursorCodec.encode(42, epoch = 1), epoch = 2))
    }

    @Test
    fun `decode with an epoch rejects epoch-less cursors`() {
        assertNull(CursorCodec.decode(CursorCodec.encode(42), epoch = 42))
    }

    @Test
    fun `epoch-less decode rejects epoch cursors`() {
        assertNull(CursorCodec.decode(CursorCodec.encode(42, epoch = 1)))
    }

    @Test
    fun `decode with an epoch returns null for null or invalid cursors`() {
        assertNull(CursorCodec.decode(null, epoch = 1))
        assertNull(CursorCodec.decode("not base64!!", epoch = 1))
        // "1:abc" base64url-encoded: right epoch, non-numeric sequence.
        assertNull(CursorCodec.decode("MTphYmM=", epoch = 1))
    }
}
