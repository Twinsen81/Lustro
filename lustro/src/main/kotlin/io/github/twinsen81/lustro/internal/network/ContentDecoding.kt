package io.github.twinsen81.lustro.internal.network

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

internal const val CONTENT_ENCODING: String = "Content-Encoding"

/** A captured body with its content coding undone: at most the capture cap of it. */
internal class DecodedBody(
    private val buffer: ByteArray,
    private val size: Int,
    /** Whether the body goes on past what this holds. */
    val truncated: Boolean,
) {
    fun text(charset: Charset): String = String(buffer, 0, size, charset)

    fun bytes(): ByteArray = if (size == buffer.size) buffer else buffer.copyOf(size)
}

/**
 * Undoes the content coding of a body capture read off the wire. [raw] is what
 * capture kept of it, and [rawTruncated] says whether the body goes on past it.
 *
 * A body in `gzip`, `x-gzip`, or `deflate`, or one that names no coding but
 * starts with the gzip magic bytes, is inflated into at most [maxBodySize] plus
 * one byte. The cap on the output is what keeps a small body that inflates to
 * far more, a decompression bomb, from filling memory, and the extra byte tells
 * a body of exactly the cap from a longer one. A body that doesn't parse in its
 * coding is taken as it arrived.
 *
 * Returns null for a coding capture can't undo, such as `br` or `zstd`: those
 * need a decoder that isn't part of the platform.
 */
internal fun decodeBody(
    raw: ByteArray,
    rawTruncated: Boolean,
    contentEncoding: List<String>,
    maxBodySize: Long,
): DecodedBody? {
    val asArrived = { DecodedBody(raw, minOf(raw.size.toLong(), maxBodySize).toInt(), rawTruncated) }
    // No body, as in the reply to a HEAD, carries the coding header but nothing to decode.
    if (raw.isEmpty()) return asArrived()
    return when (codingOf(contentEncoding)) {
        ContentCoding.UNSUPPORTED -> null
        ContentCoding.GZIP -> inflate(raw, maxBodySize, inflater = null) ?: asArrived()
        ContentCoding.DEFLATE -> inflate(raw, maxBodySize, Inflater(!raw.hasZlibHeader())) ?: asArrived()
        // A body compressed by code that didn't say so, such as a gzip request
        // body sent without the header. Text never starts with these bytes.
        ContentCoding.IDENTITY -> (if (raw.hasGzipMagic()) inflate(raw, maxBodySize, inflater = null) else null) ?: asArrived()
    }
}

private enum class ContentCoding { IDENTITY, GZIP, DEFLATE, UNSUPPORTED }

private fun codingOf(contentEncoding: List<String>): ContentCoding {
    val codings =
        contentEncoding
            .flatMap { it.split(',') }
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() && it != "identity" }
    return when {
        codings.isEmpty() -> ContentCoding.IDENTITY
        // Codings applied on top of each other, which nothing sends in practice.
        codings.size > 1 -> ContentCoding.UNSUPPORTED
        codings[0] == "gzip" || codings[0] == "x-gzip" -> ContentCoding.GZIP
        codings[0] == "deflate" -> ContentCoding.DEFLATE
        else -> ContentCoding.UNSUPPORTED
    }
}

/**
 * Inflates gzip when [inflater] is null, deflate with it otherwise. Null when
 * [raw] isn't in that format: the gzip header doesn't parse, the data is
 * malformed, or it ends before it inflates to anything.
 */
private fun inflate(raw: ByteArray, maxBodySize: Long, inflater: Inflater?): DecodedBody? {
    try {
        val stream =
            try {
                if (inflater == null) GZIPInputStream(ByteArrayInputStream(raw)) else InflaterInputStream(ByteArrayInputStream(raw), inflater)
            } catch (_: IOException) {
                return null
            }
        return stream.use { readCapped(it, maxBodySize) }
    } finally {
        // A stream closes only the Inflater it made itself.
        inflater?.end()
    }
}

private fun readCapped(stream: InputStream, maxBodySize: Long): DecodedBody? {
    val limit = minOf(maxBodySize + 1, MAX_ARRAY_SIZE).toInt()
    var out = ByteArray(minOf(limit, INITIAL_OUTPUT_SIZE))
    var size = 0
    var ended = false
    try {
        while (size < limit) {
            if (size == out.size) out = out.copyOf(minOf(out.size * 2L, limit.toLong()).toInt())
            val read = stream.read(out, size, out.size - size)
            if (read == -1) {
                ended = true
                break
            }
            size += read
        }
    } catch (_: EOFException) {
        // Capture kept only part of the compressed body: what it inflated to is a prefix.
        if (size == 0) return null
    } catch (_: IOException) {
        // Not in this coding after all, as when text is read as raw deflate data,
        // which has no header to check first: what it inflated to is noise.
        return null
    }
    return DecodedBody(out, minOf(size.toLong(), maxBodySize).toInt(), truncated = !ended || size > maxBodySize)
}

private fun ByteArray.hasGzipMagic(): Boolean = size >= 2 && this[0] == 0x1f.toByte() && this[1] == 0x8b.toByte()

// RFC 1950: the deflate method in the low nibble, and a check value that makes
// the first two bytes a multiple of 31. HTTP deflate should have this wrapper,
// but some servers send the raw deflate data without it.
private fun ByteArray.hasZlibHeader(): Boolean {
    if (size < 2) return false
    val cmf = this[0].toInt() and 0xff
    val flg = this[1].toInt() and 0xff
    return cmf and 0x0f == 8 && (cmf shl 8 or flg) % 31 == 0
}

private const val INITIAL_OUTPUT_SIZE = 8 * 1024

// The JVM can't allocate an array of Int.MAX_VALUE elements.
private const val MAX_ARRAY_SIZE = Int.MAX_VALUE - 8L
