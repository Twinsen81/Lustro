package io.github.twinsen81.lustro.internal.network

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for [decodeBody], which undoes the content coding of a captured body. */
class ContentDecodingTest {
    private val json = """{"id":7,"items":["a","b","c"],"note":"héllo"}"""

    @Test
    fun `gzip and x-gzip bodies are inflated`() {
        for (coding in listOf("gzip", "x-gzip", "GZIP", "identity, gzip")) {
            val decoded = decode(gzip(json), coding)!!

            assertEquals(coding, json, decoded.text(Charsets.UTF_8))
            assertFalse(coding, decoded.truncated)
        }
    }

    @Test
    fun `a deflate body is inflated, with or without the zlib wrapper`() {
        for (nowrap in listOf(false, true)) {
            val decoded = decode(deflate(json, nowrap), "deflate")!!

            assertEquals("nowrap=$nowrap", json, decoded.text(Charsets.UTF_8))
            assertFalse("nowrap=$nowrap", decoded.truncated)
        }
    }

    @Test
    fun `a gzip body that names no coding is found by its magic bytes`() {
        val decoded = decode(gzip(json), coding = null)!!

        assertEquals(json, decoded.text(Charsets.UTF_8))
    }

    @Test
    fun `a body that inflates past the cap keeps the cap and is flagged`() {
        // 16 MB of zeros compresses to about 16 KB: the output cap, not the input, has to stop it.
        val bomb = gzip(ByteArray(16 * 1024 * 1024))

        val decoded = decode(bomb, "gzip", maxBodySize = 1024)!!

        assertTrue(decoded.truncated)
        assertEquals(1024, decoded.bytes().size)
    }

    @Test
    fun `a body that inflates to exactly the cap is not flagged`() {
        val text = "x".repeat(1024)

        val decoded = decode(gzip(text), "gzip", maxBodySize = 1024)!!

        assertFalse(decoded.truncated)
        assertEquals(text, decoded.text(Charsets.UTF_8))
    }

    @Test
    fun `a compressed body cut at the cap keeps what it inflates to, flagged`() {
        val text = (1..5_000).joinToString(",") { "\"item-$it\"" }
        val compressed = gzip(text)
        val kept = compressed.copyOf(compressed.size / 2)

        val decoded = decodeBody(kept, rawTruncated = true, listOf("gzip"), maxBodySize = 1_000_000)!!

        assertTrue(decoded.truncated)
        val prefix = decoded.text(Charsets.UTF_8)
        assertTrue(prefix.isNotEmpty())
        assertTrue(text.startsWith(prefix))
    }

    @Test
    fun `a body that isn't in the coding it names is taken as it arrived`() {
        for (coding in listOf("gzip", "deflate")) {
            val decoded = decode(json.toByteArray(), coding)!!

            assertEquals(coding, json, decoded.text(Charsets.UTF_8))
            assertFalse(coding, decoded.truncated)
        }
    }

    @Test
    fun `a coding capture can't undo gives no body`() {
        for (coding in listOf("br", "zstd", "compress", "gzip, br", "gzip, gzip")) {
            assertNull(coding, decode(gzip(json), coding))
        }
    }

    @Test
    fun `an empty body is empty whatever its coding`() {
        for (coding in listOf("gzip", "deflate", "br")) {
            val decoded = decode(ByteArray(0), coding)!!

            assertEquals(coding, "", decoded.text(Charsets.UTF_8))
            assertFalse(coding, decoded.truncated)
        }
    }

    @Test
    fun `a body without a coding is kept up to the cap, as it arrived`() {
        val decoded = decodeBody("abcdef".toByteArray(), rawTruncated = true, emptyList(), maxBodySize = 4)!!

        assertEquals("abcd", decoded.text(Charsets.UTF_8))
        assertTrue(decoded.truncated)
    }

    private fun decode(raw: ByteArray, coding: String?, maxBodySize: Long = 64 * 1024): DecodedBody? =
        decodeBody(raw, rawTruncated = false, listOfNotNull(coding), maxBodySize)
}

internal fun gzip(text: String): ByteArray = gzip(text.toByteArray())

internal fun gzip(bytes: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    GZIPOutputStream(out).use { it.write(bytes) }
    return out.toByteArray()
}

internal fun deflate(text: String, nowrap: Boolean): ByteArray {
    val out = ByteArrayOutputStream()
    val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, nowrap)
    try {
        DeflaterOutputStream(out, deflater).use { it.write(text.toByteArray()) }
    } finally {
        deflater.end()
    }
    return out.toByteArray()
}
