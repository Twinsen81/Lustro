package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.MediaType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [platformCapturedBody], which turns the bytes platform
 * `HttpURLConnection` capture tees off a stream into a body. The tee stops
 * writing at the cap, so a buffer at the cap means the body was cut.
 */
class PlatformCapturedBodyTest {
    private val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    @Test
    fun `an image body is kept as bytes`() {
        val body = platformCapturedBody(png, maxBodySize = 64, contentType = MediaType.parse("image/png"))

        assertArrayEquals(png, body.bytes)
        assertNull(body.text)
        assertFalse(body.truncated)
        assertEquals(8L, body.byteSize)
    }

    @Test
    fun `an image body that filled the tee is reported truncated`() {
        val body = platformCapturedBody(png, maxBodySize = 8, contentType = MediaType.parse("image/jpeg; q=1"))

        assertArrayEquals(png, body.bytes)
        assertTrue(body.truncated)
    }

    @Test
    fun `other bodies are decoded as text, typed or not`() {
        for (type in listOf("application/json", "image/svg+xml", "application/octet-stream", null)) {
            val body = platformCapturedBody("{}".toByteArray(), maxBodySize = 64, contentType = type?.let { MediaType.parse(it) })

            assertEquals(type, "{}", body.text)
            assertNull(type, body.bytes)
        }
    }
}
