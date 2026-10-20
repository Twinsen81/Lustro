package io.github.twinsen81.lustro.logs.internal

import java.io.InputStream

/**
 * Splits logcat's output into lines at each `\n`, and only there. logcat ends a
 * line only at `\n`, and a message can hold a lone `\r`, at which
 * `readLine()` would end a line too and lose the rest of the message. A `\r`
 * just before the `\n` goes, as `readLine()` drops it. A line longer than
 * [maxLineBytes] keeps its first [maxLineBytes] bytes.
 *
 * Not thread-safe: the reader thread owns one.
 */
internal class LogcatLines(private val input: InputStream, private val maxLineBytes: Int = MAX_LINE_BYTES) {
    private val chunk = ByteArray(CHUNK_BYTES)
    private var chunkLength = 0
    private var chunkPosition = 0

    /** The bytes of the line that [next] read; the first [length] of them are the line. */
    var line: ByteArray = ByteArray(INITIAL_LINE_BYTES)
        private set

    /** The length of the line that [next] read. */
    var length: Int = 0
        private set

    /** Reads the next line, without its line break. Returns `false` at the end of the output. */
    fun next(): Boolean {
        length = 0
        var read = false
        while (true) {
            if (chunkPosition == chunkLength) {
                chunkLength = input.read(chunk)
                chunkPosition = 0
                if (chunkLength <= 0) {
                    chunkLength = 0
                    // The output can end without a line break after its last line.
                    return read
                }
            }
            val byte = chunk[chunkPosition++]
            read = true
            if (byte == LF) {
                if (length > 0 && line[length - 1] == CR) length--
                return true
            }
            if (length < maxLineBytes) append(byte)
        }
    }

    /** The line that [next] read, as UTF-8. */
    fun text(): String = String(line, 0, length, Charsets.UTF_8)

    private fun append(byte: Byte) {
        if (length == line.size) line = line.copyOf(minOf(line.size * 2, maxLineBytes))
        line[length++] = byte
    }

    companion object {
        // logcat keeps at most about 4 KB of a message, and its prefix is shorter than 128 bytes.
        const val MAX_LINE_BYTES: Int = 16 * 1024

        private const val CHUNK_BYTES = 8 * 1024
        private const val INITIAL_LINE_BYTES = 256
        private const val LF = '\n'.code.toByte()
        private const val CR = '\r'.code.toByte()
    }
}
