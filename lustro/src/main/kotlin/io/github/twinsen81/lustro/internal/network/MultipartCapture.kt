package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.network.CapturedBody
import okhttp3.MultipartBody
import okio.Buffer
import okio.buffer

/**
 * Captures a multipart request body as text the store and the Redactor can
 * read: the parts in order, each with its headers, as they go on the wire.
 * The content of a text part is kept. A part that isn't text, such as a file,
 * is not read: one line stands in for its content and gives its type and size,
 * because its bytes would not fit in a text body and could be large.
 *
 * The text is cut at the capture cap like any other body. Its size is the
 * size the whole body declares on the wire.
 */
internal object MultipartCapture {
    /** The start of the line that stands in for a part whose content isn't kept. */
    const val OMITTED_PART_PREFIX: String = "[Lustro did not store this part: "

    private const val CRLF = "\r\n"

    fun capture(body: MultipartBody, maxBodySize: Long): CapturedBody {
        val declaredSize = body.contentLength().takeIf { it >= 0 }
        val dashBoundary = "--" + body.boundary
        val out = StringBuilder()
        var cut = false
        for (part in body.parts) {
            out.append(dashBoundary).append(CRLF)
            part.headers?.forEach { (name, value) -> out.append(name).append(": ").append(value).append(CRLF) }
            val partBody = part.body
            val contentType = partBody.contentType()
            if (contentType != null) out.append("Content-Type: ").append(contentType).append(CRLF)
            val partSize = partBody.contentLength().takeIf { it >= 0 }
            if (partSize != null) out.append("Content-Length: ").append(partSize).append(CRLF)
            out.append(CRLF)
            val text =
                if (isText(contentType, part.headers) && !partBody.isOneShot() && !partBody.isDuplex()) {
                    readText(partBody, contentType, maxBodySize - out.length)
                } else {
                    null
                }
            if (text == null) {
                out.append(omittedLine(contentType, partSize))
            } else {
                out.append(text.content)
                cut = text.cut
            }
            if (cut || out.length > maxBodySize) break
            out.append(CRLF)
        }
        if (!cut && out.length <= maxBodySize) out.append(dashBoundary).append("--").append(CRLF)
        val truncated = cut || out.length > maxBodySize
        val stored = if (out.length > maxBodySize) out.substring(0, maxBodySize.toInt()) else out.toString()
        return CapturedBody(text = stored, truncated = truncated, byteSize = declaredSize)
    }

    // A part with no type is text when it is a plain form field. A file with no
    // type, as from File.asRequestBody(), is not read: it can be large, and its
    // bytes are not text.
    private fun isText(contentType: okhttp3.MediaType?, headers: okhttp3.Headers?): Boolean =
        if (contentType == null) {
            headers?.get("Content-Disposition")?.contains("filename", ignoreCase = true) != true
        } else {
            contentType.isTextLike()
        }

    private class PartText(val content: String, val cut: Boolean)

    // Keeps at most [room] bytes of the part. Null when it can't be read.
    private fun readText(partBody: okhttp3.RequestBody, contentType: okhttp3.MediaType?, room: Long): PartText? {
        val buffer = Buffer()
        return try {
            val capping = CappedSink(buffer, room.coerceAtLeast(0L))
            capping.buffer().use { partBody.writeTo(it) }
            PartText(buffer.readString(contentType.resolvedCharset()), capping.dropped)
        } catch (_: Exception) {
            null
        } finally {
            buffer.close()
        }
    }

    private fun omittedLine(contentType: okhttp3.MediaType?, size: Long?): String {
        val type = contentType?.let { "${it.type}/${it.subtype}" } ?: "content"
        val sizeText = if (size != null) "$size bytes" else "size unknown"
        return "$OMITTED_PART_PREFIX$type, $sizeText]"
    }

    // Copies at most [cap] bytes into [target] and drops the rest, noting that it did.
    private class CappedSink(private val target: Buffer, private val cap: Long) : okio.Sink {
        var dropped: Boolean = false
            private set

        override fun write(source: Buffer, byteCount: Long) {
            val keep = minOf((cap - target.size).coerceAtLeast(0L), byteCount)
            if (keep > 0) target.write(source, keep)
            if (byteCount > keep) {
                source.skip(byteCount - keep)
                dropped = true
            }
        }

        override fun flush() = Unit

        override fun close() = Unit

        override fun timeout(): okio.Timeout = okio.Timeout.NONE
    }
}
