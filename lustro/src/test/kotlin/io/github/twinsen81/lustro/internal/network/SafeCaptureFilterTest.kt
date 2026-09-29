package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.network.NetworkCaptureFilter
import io.github.twinsen81.lustro.network.NetworkCaptureRequest
import io.github.twinsen81.lustro.network.NoOpNetworkCaptureFilter
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
            SafeCaptureFilter(
                NetworkCaptureFilter.of("Skips HEAD") { request ->
                    seen = request
                    request.method != "HEAD"
                },
            )
        val headers = Headers.of("X-No-Capture" to "1")

        assertFalse(filter.shouldCapture("https://example.com/a", "HEAD", headers))
        assertEquals("https://example.com/a", seen!!.url)
        assertEquals("HEAD", seen!!.method)
        assertSame(headers, seen!!.headers)
        assertTrue(filter.shouldCapture("https://example.com/a", "GET", headers))
    }

    @Test
    fun `it counts the requests the filter skips and fails on, until the counts are reset`() {
        val filter =
            SafeCaptureFilter(
                NetworkCaptureFilter.of("Skips /skip, fails on /fail") { request ->
                    check("/fail" !in request.url)
                    "/skip" !in request.url
                },
            )

        filter.shouldCapture("https://example.com/skip", "GET", Headers.EMPTY)
        filter.shouldCapture("https://example.com/skip", "GET", Headers.EMPTY)
        filter.shouldCapture("https://example.com/fail", "GET", Headers.EMPTY)
        filter.shouldCapture("https://example.com/keep", "GET", Headers.EMPTY)

        assertEquals(2, filter.skipped)
        assertEquals(1, filter.failed)
        filter.resetCounts()
        assertEquals(0, filter.skipped)
        assertEquals(0, filter.failed)
    }

    @Test
    fun `a filter that throws captures the request and logs the first failure only`() {
        val filter = SafeCaptureFilter(NetworkCaptureFilter.of("Throws") { error("no capture for token=secret") })

        repeat(3) {
            assertTrue(filter.shouldCapture("https://example.com/a?token=secret", "GET", Headers.EMPTY))
        }

        val logs = ShadowLog.getLogsForTag(CAPTURE_TAG)
        assertEquals(1, logs.size)
        // By exception class only: the message can quote the URL or a header.
        assertTrue(logs.single().msg, logs.single().msg.endsWith(IllegalStateException::class.java.name))
        assertFalse(logs.single().msg.contains("secret"))
    }

    @Test
    fun `only a filter the app set is reported, with its description`() {
        assertFalse(SafeCaptureFilter(NoOpNetworkCaptureFilter).isSet)

        val filter = SafeCaptureFilter(NetworkCaptureFilter.of("Analytics calls") { true })

        assertTrue(filter.isSet)
        assertEquals("Analytics calls", filter.description)
    }

    @Test
    fun `a blank or failing description is replaced, so the tab always has one to show`() {
        val blank = SafeCaptureFilter(NetworkCaptureFilter.of("  ") { true })
        val failing =
            SafeCaptureFilter(
                object : NetworkCaptureFilter {
                    override val description: String
                        get() = error("no description")

                    override fun shouldCapture(request: NetworkCaptureRequest): Boolean = true
                },
            )

        assertEquals("The app gave this filter no description.", blank.description)
        assertEquals("The app gave this filter no description.", failing.description)
    }
}
