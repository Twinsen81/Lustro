package io.github.twinsen81.lustro.internal.network

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [EventStreamCapturingResponseBody], with AssertJ
 * assertions converted to JUnit4.
 */
class EventStreamCapturingResponseBodyTest {
    @Test
    fun `capture is capped while byte count keeps increasing`() {
        val captures = mutableListOf<EventStreamBodyCapture>()
        val body =
            ChunkedResponseBody(
                contentType = EVENT_STREAM,
                bytes = "abcdef".toByteArray(Charsets.UTF_8),
                chunkSize = 3,
            )
        val wrapper =
            EventStreamCapturingResponseBody(
                delegate = body,
                declaredSize = null,
                maxBodySize = 4,
                onCapture = captures::add,
            )

        val sink = Buffer()
        val source = wrapper.source()
        while (source.read(sink, 8_192) != -1L) {
            // Drain the stream.
        }

        assertEquals("abcdef", sink.readUtf8())
        assertEquals(
            EventStreamBodyCapture(
                text = "abcd",
                truncated = true,
                sizeBytes = 6,
                responseComplete = true,
            ),
            captures.last(),
        )
    }

    @Test
    fun `post cap reads reuse captured text while byte count updates`() {
        val captures = mutableListOf<EventStreamBodyCapture>()
        val body =
            ChunkedResponseBody(
                contentType = EVENT_STREAM,
                bytes = "abcdefghi".toByteArray(Charsets.UTF_8),
                chunkSize = 2,
            )
        val wrapper =
            EventStreamCapturingResponseBody(
                delegate = body,
                declaredSize = null,
                maxBodySize = 4,
                onCapture = captures::add,
            )

        val sink = Buffer()
        val source = wrapper.source()
        while (source.read(sink, 8_192) != -1L) {
            // Drain the stream.
        }

        assertEquals(
            listOf(
                EventStreamBodyCapture("ab", truncated = false, sizeBytes = 2, responseComplete = false),
                EventStreamBodyCapture("abcd", truncated = false, sizeBytes = 4, responseComplete = false),
                EventStreamBodyCapture("abcd", truncated = true, sizeBytes = 6, responseComplete = false),
                EventStreamBodyCapture("abcd", truncated = true, sizeBytes = 8, responseComplete = false),
                EventStreamBodyCapture("abcd", truncated = true, sizeBytes = 9, responseComplete = false),
                EventStreamBodyCapture("abcd", truncated = true, sizeBytes = 9, responseComplete = true),
            ),
            captures,
        )
    }

    @Test
    fun `close publishes a completed capture without waiting for EOF`() {
        val captures = mutableListOf<EventStreamBodyCapture>()
        val body =
            ChunkedResponseBody(
                contentType = EVENT_STREAM,
                bytes = "abc".toByteArray(Charsets.UTF_8),
                chunkSize = 1,
            )
        val wrapper =
            EventStreamCapturingResponseBody(
                delegate = body,
                declaredSize = null,
                maxBodySize = 10,
                onCapture = captures::add,
            )

        val sink = Buffer()
        val source = wrapper.source()
        assertEquals(1L, source.read(sink, 1))
        source.close()

        assertEquals(
            EventStreamBodyCapture(
                text = "a",
                truncated = false,
                sizeBytes = 1,
                responseComplete = true,
            ),
            captures.last(),
        )
    }

    @Test
    fun `EOF publishes exactly one completed capture`() {
        val captures = mutableListOf<EventStreamBodyCapture>()
        val body =
            ChunkedResponseBody(
                contentType = EVENT_STREAM,
                bytes = "abc".toByteArray(Charsets.UTF_8),
                chunkSize = 3,
            )
        val wrapper =
            EventStreamCapturingResponseBody(
                delegate = body,
                declaredSize = null,
                maxBodySize = 10,
                onCapture = captures::add,
            )

        val sink = Buffer()
        val source = wrapper.source()
        while (source.read(sink, 8_192) != -1L) {
            // Drain the stream.
        }

        assertEquals(listOf(false, true), captures.map { it.responseComplete })
        assertEquals("abc", captures.last().text)
    }

    @Test
    fun `partial UTF-8 code points are not published as replacement characters`() {
        val captures = mutableListOf<EventStreamBodyCapture>()
        val content = "a🙂b"
        val body =
            ChunkedResponseBody(
                contentType = EVENT_STREAM,
                bytes = content.toByteArray(Charsets.UTF_8),
                chunkSize = 3,
            )
        val wrapper =
            EventStreamCapturingResponseBody(
                delegate = body,
                declaredSize = null,
                maxBodySize = 20,
                onCapture = captures::add,
            )

        val sink = Buffer()
        val source = wrapper.source()
        assertEquals(3L, source.read(sink, 8_192))
        assertEquals("a", captures.last().text)
        assertFalse(captures.last().text!!.contains("�"))

        while (source.read(sink, 8_192) != -1L) {
            // Drain the stream.
        }

        assertEquals(content, captures.last().text)
        assertFalse(captures.last().text!!.contains("�"))
    }

    @Test
    fun `declaredSize is reported as sizeBytes when present`() {
        val captures = mutableListOf<EventStreamBodyCapture>()
        val body =
            ChunkedResponseBody(
                contentType = EVENT_STREAM,
                bytes = "hi".toByteArray(Charsets.UTF_8),
                chunkSize = 2,
            )
        val wrapper =
            EventStreamCapturingResponseBody(
                delegate = body,
                declaredSize = 999,
                maxBodySize = 10,
                onCapture = captures::add,
            )

        val sink = Buffer()
        val source = wrapper.source()
        while (source.read(sink, 8_192) != -1L) {
            // Drain.
        }
        assertTrue(captures.all { it.sizeBytes == 999L })
    }

    private class ChunkedResponseBody(
        private val contentType: MediaType,
        private val bytes: ByteArray,
        private val chunkSize: Int,
    ) : ResponseBody() {
        override fun contentType(): MediaType = contentType

        override fun contentLength(): Long = -1L

        override fun source(): BufferedSource =
            object : Source {
                private var offset = 0

                override fun read(
                    sink: Buffer,
                    byteCount: Long,
                ): Long {
                    if (offset == bytes.size) return -1L
                    val bytesToRead =
                        minOf(
                            byteCount,
                            chunkSize.toLong(),
                            (bytes.size - offset).toLong(),
                        ).toInt()
                    sink.write(bytes, offset, bytesToRead)
                    offset += bytesToRead
                    return bytesToRead.toLong()
                }

                override fun timeout(): Timeout = Timeout.NONE

                override fun close() = Unit
            }.buffer()
    }

    private companion object {
        val EVENT_STREAM: MediaType = "text/event-stream".toMediaType()
    }
}
