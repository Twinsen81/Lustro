package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.network.NetworkCaptureRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafeCaptureFilterTest {
    @Test
    fun `the filter gets the request and its answer decides`() {
        var seen: NetworkCaptureRequest? = null
        val filter =
            SafeCaptureFilter { request ->
                seen = request
                request.method != "HEAD"
            }
        val headers = Headers.of("X-No-Capture" to "1")

        assertFalse(filter.shouldCapture("https://example.com/a", "HEAD", headers))
        assertEquals("https://example.com/a", seen!!.url)
        assertEquals("HEAD", seen!!.method)
        assertSame(headers, seen!!.headers)
        assertTrue(filter.shouldCapture("https://example.com/a", "GET", headers))
    }

    @Test
    fun `a filter that throws captures the request and logs the first failure only`() {
        val filter = SafeCaptureFilter { error("no capture for token=secret") }

        repeat(3) {
            assertTrue(filter.shouldCapture("https://example.com/a?token=secret", "GET", Headers.EMPTY))
        }

        val logs = ShadowLog.getLogsForTag(CAPTURE_TAG)
        assertEquals(1, logs.size)
        // By exception class only: the message can quote the URL or a header.
        assertTrue(logs.single().msg, logs.single().msg.endsWith(IllegalStateException::class.java.name))
        assertFalse(logs.single().msg.contains("secret"))
    }
}
