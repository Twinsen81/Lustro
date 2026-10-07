package io.github.twinsen81.lustro.internal.network

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

internal data class EventStreamBodyCapture(
    val text: String?,
    val truncated: Boolean,
    val sizeBytes: Long?,
    val responseComplete: Boolean,
)

internal class EventStreamCapturingResponseBody(
    private val delegate: ResponseBody,
    private val declaredSize: Long?,
    private val maxBodySize: Long,
    private val onCapture: (EventStreamBodyCapture) -> Unit,
    private val onFailure: (bytesRead: Long, error: IOException) -> Unit = { _, _ -> },
) : ResponseBody() {
    private val captureBuffer = Buffer()
    private val charset = delegate.contentType().resolvedCharset()
    private var totalBytesRead = 0L
    private var lastCapture: EventStreamBodyCapture? = null
    private var decodedText: String? = null
    private var decodedBufferSize = -1L

    // A read that throws ends the capture: the close after it doesn't report the stream as complete.
    private var failed = false

    private val capturingSource: BufferedSource by lazy {
        object : ForwardingSource(delegate.source()) {
            override fun read(
                sink: Buffer,
                byteCount: Long,
            ): Long {
                val bytesRead =
                    try {
                        super.read(sink, byteCount)
                    } catch (e: IOException) {
                        if (!failed) {
                            failed = true
                            onFailure(totalBytesRead, e)
                        }
                        throw e
                    }
                when {
                    bytesRead > 0L -> {
                        captureBytes(sink, bytesRead)
                        publishCapture(responseComplete = false)
                    }

                    bytesRead == -1L -> {
                        publishCapture(responseComplete = true)
                    }
                }
                return bytesRead
            }

            override fun close() {
                try {
                    super.close()
                } finally {
                    publishCapture(responseComplete = true)
                }
            }
        }.buffer()
    }

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun source(): BufferedSource = capturingSource

    private fun captureBytes(
        sink: Buffer,
        bytesRead: Long,
    ) {
        totalBytesRead += bytesRead
        val remainingCapacity = maxBodySize - captureBuffer.size
        if (remainingCapacity <= 0L) return

        val bytesToCapture = minOf(bytesRead, remainingCapacity)
        sink.copyTo(
            out = captureBuffer,
            offset = sink.size - bytesRead,
            byteCount = bytesToCapture,
        )
    }

    private fun publishCapture(responseComplete: Boolean) {
        if (failed) return
        val capture =
            EventStreamBodyCapture(
                text = decodeCapturedText(),
                truncated = totalBytesRead > maxBodySize,
                sizeBytes = declaredSize ?: totalBytesRead,
                responseComplete = responseComplete,
            )
        if (capture == lastCapture) return
        lastCapture = capture
        onCapture(capture)
    }

    private fun decodeCapturedText(): String? {
        if (captureBuffer.size == 0L) return null
        if (captureBuffer.size == decodedBufferSize) return decodedText

        val bytes = captureBuffer.clone().readByteArray()
        decodedText = decodeCompletePrefix(bytes, charset).takeIf { it.isNotEmpty() }
        decodedBufferSize = captureBuffer.size
        return decodedText
    }

    private fun decodeCompletePrefix(
        bytes: ByteArray,
        charset: Charset,
    ): String {
        val minEnd = maxOf(0, bytes.size - MAX_TRAILING_INCOMPLETE_CHAR_BYTES)
        for (end in bytes.size downTo minEnd) {
            try {
                val decoder =
                    charset.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                return decoder.decode(ByteBuffer.wrap(bytes, 0, end)).toString()
            } catch (_: CharacterCodingException) {
                // The most common failure is a trailing partial UTF-8 code point.
            }
        }
        return ""
    }

    private companion object {
        private const val MAX_TRAILING_INCOMPLETE_CHAR_BYTES = 8
    }
}
