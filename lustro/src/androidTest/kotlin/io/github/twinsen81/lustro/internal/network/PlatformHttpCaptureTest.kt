package io.github.twinsen81.lustro.internal.network

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.MediaType
import io.github.twinsen81.lustro.internal.network.HttpUrlConnectionCapture.HandlerSource
import io.github.twinsen81.lustro.network.CapturedBody
import io.github.twinsen81.lustro.network.CapturedResponse
import io.github.twinsen81.lustro.network.MockRule
import io.github.twinsen81.lustro.network.NetworkCaptureSink
import io.github.twinsen81.lustro.network.NoOpNetworkCaptureFilter
import io.github.twinsen81.lustro.network.TransactionId
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the platform `HttpURLConnection` capture on the device's own HTTP stack.
 *
 * The capture gets the platform's http and https handlers by reflection, and the
 * hidden-API policy of each release decides which way works. The unit tests wrap
 * a stand-in handler, so only this test sees what a release allows. The
 * process-wide handler factory can be set once per process, so every check that
 * needs it lives in this class, which installs the capture once.
 */
@RunWith(AndroidJUnit4::class)
class PlatformHttpCaptureTest {
    private val server = MockWebServer()

    @Before
    fun startServer() {
        sink.clear()
        server.start(InetAddress.getByName(LOOPBACK), 0)
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun installsThroughTheHandlerPathThisReleaseAllows() {
        val expected = expectedSource()
        val release = "API ${Build.VERSION.SDK_INT}"
        assertEquals("http handler on $release", expected, capture.httpSource)
        assertEquals("https handler on $release", expected, capture.httpsSource)
    }

    @Test
    fun getReachesTheSinkWithMethodUrlStatusHeadersAndBody() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/plain; charset=utf-8")
                .setHeader("X-Reply", "pong")
                .setBody("hello from the server"),
        )
        val url = URL("http://$LOOPBACK:${server.port}/items?page=1")

        val connection = url.openConnection() as HttpURLConnection
        connection.setRequestProperty("X-Trace", "trace-1")
        val code = connection.responseCode
        val body = connection.inputStream.use { it.readBytes().decodeToString() }
        connection.disconnect()

        // The app's call works the same whether or not it is captured.
        assertEquals(200, code)
        assertEquals("hello from the server", body)
        val received = server.takeRequest()
        assertEquals("GET", received.method)
        assertEquals("trace-1", received.getHeader("X-Trace"))

        if (capture.httpSource == null) {
            assertTrue(sink.transactions.isEmpty())
            return
        }
        val tx = sink.transactions.single()
        assertEquals("GET", tx.method)
        assertEquals(url.toString(), tx.url)
        assertEquals("trace-1", tx.requestHeaders.get("X-Trace"))
        assertNull(tx.requestBody)
        val response = requireNotNull(tx.response) { "no response was captured" }
        assertEquals(200, response.statusCode)
        assertEquals("pong", response.headers.get("X-Reply"))
        assertEquals("text/plain; charset=utf-8", response.headers.get("Content-Type"))
        assertEquals("hello from the server", response.body?.text)
        assertNull(tx.error)
    }

    @Test
    fun postReachesTheSinkWithBothBodies() {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":7}"""),
        )
        val url = URL("http://$LOOPBACK:${server.port}/items")

        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write("""{"name":"lustro"}""".toByteArray()) }
        val code = connection.responseCode
        val body = connection.inputStream.use { it.readBytes().decodeToString() }
        connection.disconnect()

        assertEquals(201, code)
        assertEquals("""{"id":7}""", body)
        val received = server.takeRequest()
        assertEquals("POST", received.method)
        assertEquals("""{"name":"lustro"}""", received.body.readUtf8())

        if (capture.httpSource == null) {
            assertTrue(sink.transactions.isEmpty())
            return
        }
        val tx = sink.transactions.single()
        assertEquals("POST", tx.method)
        assertEquals(url.toString(), tx.url)
        assertEquals("application/json", tx.requestHeaders.get("Content-Type"))
        assertEquals(MediaType.parse("application/json"), tx.contentType)
        assertEquals("""{"name":"lustro"}""", tx.requestBody?.text)
        val response = requireNotNull(tx.response) { "no response was captured" }
        assertEquals(201, response.statusCode)
        assertEquals("application/json", response.headers.get("Content-Type"))
        assertEquals("""{"id":7}""", response.body?.text)
        assertNull(tx.error)
    }

    /** Keeps each transaction the capture reports, with the last response reported for it. */
    private class RecordingSink : NetworkCaptureSink {
        class Transaction(
            val url: String,
            val method: String,
            val requestHeaders: Headers,
            val requestBody: CapturedBody?,
            val contentType: MediaType?,
        ) {
            var response: CapturedResponse? = null
            var error: String? = null
        }

        private val recorded = LinkedHashMap<TransactionId, Transaction>()

        val transactions: List<Transaction>
            @Synchronized get() = recorded.values.toList()

        @Synchronized
        fun clear() = recorded.clear()

        @Synchronized
        override fun beginRequest(
            url: String,
            method: String,
            headers: Headers,
            requestBody: CapturedBody?,
            contentType: MediaType?,
        ): TransactionId {
            val id = TransactionId("tx-${recorded.size + 1}")
            recorded[id] = Transaction(url, method, headers, requestBody, contentType)
            return id
        }

        override fun findMockRule(url: String, method: String): MockRule? = null

        @Synchronized
        override fun completeRequest(id: TransactionId, response: CapturedResponse) {
            recorded.getValue(id).response = response
        }

        @Synchronized
        override fun failRequest(id: TransactionId, durationMs: Long, error: String) {
            recorded.getValue(id).error = error
        }
    }

    companion object {
        private const val LOOPBACK = "127.0.0.1"

        private val sink = RecordingSink()

        private lateinit var capture: HttpUrlConnectionCapture

        @BeforeClass
        @JvmStatic
        fun installCapture() {
            capture =
                HttpUrlConnectionCapture(
                    sink = sink,
                    isPaused = { false },
                    captureFilter = SafeCaptureFilter(NoOpNetworkCaptureFilter),
                    maxBodySize = 64 * 1024,
                )
            capture.install()
        }

        /**
         * The handler path this release allows, or null where the hidden-API policy
         * blocks both paths and capture stays off. Measured with target SDK 35 on
         * API 26, 28, 30, and 34 to 37: every one allows the handler class. ART logs
         * its constructor as a greylisted ("unsupported") hidden API, which a later
         * release can block. When one does, branch on `Build.VERSION.SDK_INT` here.
         */
        private fun expectedSource(): HandlerSource? = HandlerSource.HANDLER_CLASS
    }
}
