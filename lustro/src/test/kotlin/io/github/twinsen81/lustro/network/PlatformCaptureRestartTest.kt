package io.github.twinsen81.lustro.network

import io.github.twinsen81.lustro.ExperimentalPlatformCapture
import io.github.twinsen81.lustro.internal.network.HttpUrlConnectionCapture
import io.github.twinsen81.lustro.internal.network.SafeCaptureFilter
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.net.URLStreamHandlerFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A process can set its URLStreamHandlerFactory only once, so platform capture
 * must set it once per tab, also when Lustro starts again after a stop. The real
 * factory cannot be removed, so these tests use a stand-in for the platform.
 */
class PlatformCaptureRestartTest {
    @Test
    fun `a second install of one capture does not set the factory again`() {
        val platform = FakePlatform()
        val capture = capture(platform)

        capture.install()
        capture.install()

        assertEquals(1, platform.factoryCalls)
        assertEquals(HttpUrlConnectionCapture.HandlerSource.HANDLER_CLASS, capture.httpSource)
    }

    @OptIn(ExperimentalPlatformCapture::class)
    @Test
    fun `a start after a stop keeps the factory of the first start`() {
        val platform = FakePlatform()
        val tab = NetworkDebugTab.create(senderClient = null, capturePlatformHttp = true)
        tab.platform = platform

        tab.onStart()
        tab.onStop()
        tab.onStart()

        assertEquals(1, platform.factoryCalls)
    }

    @Test
    fun `a capture that could not get the handlers tries again on the next install`() {
        val platform = FakePlatform(handlersFrom = 2)
        val capture = capture(platform)

        capture.install()
        assertNull(capture.httpSource)
        capture.install()

        assertEquals(1, platform.factoryCalls)
        assertEquals(HttpUrlConnectionCapture.HandlerSource.HANDLER_CLASS, capture.httpSource)
    }

    @Test
    fun `a factory that something else set leaves capture off`() {
        val platform = FakePlatform(factorySetElsewhere = true)
        val capture = capture(platform)

        capture.install()

        assertEquals(1, platform.factoryCalls)
        assertNull(capture.httpSource)
    }

    private fun capture(platform: HttpUrlConnectionCapture.Platform) =
        HttpUrlConnectionCapture(
            sink = NetworkDebugTab.create().captureSink,
            isPaused = { false },
            captureFilter = SafeCaptureFilter(NoOpNetworkCaptureFilter),
            maxBodySize = 1024,
            platform = platform,
        )

    /**
     * Stands in for the platform. Like URL.setURLStreamHandlerFactory, it fails
     * when a factory is set already. It has handlers from install number
     * [handlersFrom] on.
     */
    private class FakePlatform(
        private val handlersFrom: Int = 1,
        private val factorySetElsewhere: Boolean = false,
    ) : HttpUrlConnectionCapture.Platform {
        var factoryCalls = 0
            private set
        private var lookups = 0
        private var factory: URLStreamHandlerFactory? = null

        override fun handler(className: String, spec: String): HttpUrlConnectionCapture.PlatformHandler? {
            lookups++
            // Two lookups, https then http, for each install.
            val install = (lookups + 1) / 2
            if (install < handlersFrom) return null
            return HttpUrlConnectionCapture.PlatformHandler(StandInHandler, HttpUrlConnectionCapture.HandlerSource.HANDLER_CLASS)
        }

        override fun setFactory(factory: URLStreamHandlerFactory) {
            factoryCalls++
            if (factorySetElsewhere || this.factory != null) throw Error("factory already defined")
            this.factory = factory
        }
    }

    private object StandInHandler : URLStreamHandler() {
        override fun openConnection(u: URL): URLConnection = throw UnsupportedOperationException("not used")
    }
}
