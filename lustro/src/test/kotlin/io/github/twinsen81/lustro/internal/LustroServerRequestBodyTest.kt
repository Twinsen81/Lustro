package io.github.twinsen81.lustro.internal

import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugTab
import io.github.twinsen81.lustro.UncaughtExceptionRecorder
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * End-to-end (over loopback HTTP) tests for how [LustroServer] handles request
 * bodies. NanoHTTPD parses a connection's next request from wherever the last
 * one's body stopped, so a response that leaves a body unread must close the
 * connection. And an API request's body is read before the request takes a
 * concurrency slot, so a client that sends it slowly holds only its own
 * connection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LustroServerRequestBodyTest {
    @get:Rule
    val uncaught = UncaughtExceptionRecorder()

    private val client = OkHttpClient.Builder().followRedirects(false).build()
    private val tab = EchoTab()
    private var server: LustroServer? = null
    private lateinit var token: String

    /** Keeps each body it gets. A `block` request holds its slot until [release] opens. */
    private class EchoTab : DebugTab() {
        override val id: String = "sample"
        override val title: String = "Sample"
        override val icon: String = "S"

        val bodies = CopyOnWriteArrayList<String>()
        val blocking = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun handle(request: DebugRequest): DebugResponse {
            if (request.path == "block") {
                blocking.countDown()
                release.await(10, TimeUnit.SECONDS)
            } else {
                bodies += request.bodyAsString().orEmpty()
            }
            return DebugResponse.ok("{\"ok\":true}")
        }
    }

    private fun startServer(
        maxRequestBodyBytes: Long = 1L * 1024 * 1024,
        maxConcurrent: Int = 16,
        queueCapacity: Int = 64,
        timeoutMs: Long = 30_000,
    ) {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val registry = DebugTabRegistry().apply { addTab(tab); start() }
        val tokenStore = LustroTokenStore(context)
        token = tokenStore.token()
        server =
            LustroServer(
                hostname = "127.0.0.1",
                port = 0,
                registry = registry,
                assetLoader = DebugAssetLoader(context),
                tokenStore = tokenStore,
                allowedOrigins = emptyList(),
                maxRequestBodyBytes = maxRequestBodyBytes,
                maxConcurrentRequests = maxConcurrent,
                requestQueueCapacity = queueCapacity,
                requestTimeoutMs = timeoutMs,
            ).apply { start(5_000, false) }
    }

    @After
    fun tearDown() {
        tab.release.countDown()
        server?.stop()
        server?.shutdownLimiter()
    }

    private fun url(path: String): String = "http://127.0.0.1:${server!!.listeningPort}$path"

    private fun authorizedGet(path: String): Response =
        client.newCall(Request.Builder().url(url(path)).header("Authorization", "Bearer $token").build()).execute()

    private fun auth(): String = "Authorization: Bearer $token"

    /** A raw HTTP/1.1 request. A [body] gets a matching Content-Length unless [headers] frame it. */
    private fun request(method: String, path: String, body: String? = null, vararg headers: String): String =
        buildString {
            append("$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\n")
            headers.forEach { append(it).append("\r\n") }
            val framed = headers.any { it.startsWith("Transfer-Encoding:") || it.startsWith("Content-Length:") }
            if (body != null && !framed) append("Content-Length: ${body.toByteArray().size}\r\n")
            append("\r\n")
            body?.let(::append)
        }

    private fun chunked(text: String): String = "${Integer.toHexString(text.length)}\r\n$text\r\n0\r\n\r\n"

    // A body that is itself a request. The server answers it with a 404 if it
    // ever reads the body as the connection's next request.
    private val smuggled = request("GET", "/elsewhere")

    private class RawResponse(val status: Int, val headers: Map<String, String>, val body: String)

    /** A client connection that writes raw requests and parses the responses. */
    private inner class Connection : AutoCloseable {
        val socket = Socket("127.0.0.1", server!!.listeningPort).apply { soTimeout = 5_000 }
        private val input = socket.getInputStream().buffered()

        fun send(text: String) {
            socket.getOutputStream().apply {
                write(text.toByteArray())
                flush()
            }
        }

        fun readResponse(): RawResponse {
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val byte = input.read()
                check(byte >= 0) { "connection ended mid-response: $head" }
                head.append(byte.toChar())
            }
            val lines = head.trim().split("\r\n")
            val headers =
                lines.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            val body = ByteArray(headers["content-length"]?.toInt() ?: 0)
            var read = 0
            while (read < body.size) {
                val n = input.read(body, read, body.size - read)
                check(n >= 0) { "body cut short" }
                read += n
            }
            return RawResponse(lines.first().split(' ')[1].toInt(), headers, String(body))
        }

        /**
         * Stops sending, as a client does once it has a `Connection: close`
         * response, and asserts that the server then ends the connection without
         * sending anything more.
         */
        fun assertEndsWithoutMore() {
            if (!socket.isOutputShutdown) socket.shutdownOutput()
            val extra = ByteArrayOutputStream()
            try {
                while (true) {
                    val byte = input.read()
                    if (byte < 0) break
                    extra.write(byte)
                }
            } catch (_: SocketTimeoutException) {
                throw AssertionError("connection still open after: ${extra.toString(Charsets.ISO_8859_1)}")
            } catch (_: SocketException) {
                // A reset ends it too.
            }
            assertEquals("sent after the response", "", extra.toString(Charsets.ISO_8859_1))
        }

        override fun close() {
            socket.close()
        }
    }

    @Test
    fun `a request answered without reading its body closes the connection`() {
        startServer()
        val cases =
            listOf(
                401 to request("POST", "/api/v1/sample/echo", smuggled),
                403 to
                    request(
                        "POST",
                        "/api/v1/sample/echo",
                        smuggled,
                        auth(),
                        "Origin: https://evil.example",
                        "Sec-Fetch-Site: cross-site",
                    ),
                405 to request("PUT", "/api/v1/_auth", smuggled),
                400 to request("POST", "/api/v1/sample/echo", chunked(smuggled), auth(), "Transfer-Encoding: chunked"),
                400 to request("POST", "/api/v1/sample/echo", smuggled, auth(), "Content-Length: many"),
                // Only POST, PUT, PATCH, and DELETE bodies are read.
                200 to request("GET", "/api/v1/_meta", smuggled, auth()),
                // Chrome routes never read bodies.
                200 to request("POST", "/tab/sample", smuggled),
                404 to request("POST", "/elsewhere", smuggled),
            )
        for ((status, raw) in cases) {
            Connection().use { connection ->
                connection.send(raw)
                val response = connection.readResponse()
                val name = raw.substringBefore("\r\n")
                assertEquals(name, status, response.status)
                assertEquals(name, "close", response.headers["connection"])
                connection.assertEndsWithoutMore()
            }
        }
        assertTrue("no body reached the tab: ${tab.bodies}", tab.bodies.isEmpty())
    }

    @Test
    fun `a request body over the cap gets a 413 and closes the connection`() {
        startServer(maxRequestBodyBytes = 16)
        Connection().use { connection ->
            connection.send(request("POST", "/api/v1/sample/echo", smuggled, auth()))
            val response = connection.readResponse()
            assertEquals(413, response.status)
            assertEquals("close", response.headers["connection"])
            connection.assertEndsWithoutMore()
        }
    }

    @Test
    fun `an _auth body over 1 KB gets a 413 before it is sent`() {
        startServer()
        Connection().use { connection ->
            connection.send("POST /api/v1/_auth HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 1025\r\n\r\n")
            val response = connection.readResponse()
            assertEquals(413, response.status)
            assertEquals("close", response.headers["connection"])
            connection.assertEndsWithoutMore()
        }
    }

    @Test
    fun `a client that sends its whole body before reading still gets the response`() {
        startServer()
        val body = "a".repeat(1024 * 1024)
        Connection().use { connection ->
            // Answered with a 401 as soon as the headers arrive. Closing the
            // connection while this upload is still arriving would reset it.
            connection.send(request("POST", "/api/v1/sample/echo", body))
            val response = connection.readResponse()
            assertEquals(401, response.status)
            assertEquals("close", response.headers["connection"])
            connection.assertEndsWithoutMore()
        }
    }

    @Test
    fun `a body the server reads leaves the connection usable`() {
        startServer()
        Connection().use { connection ->
            connection.send(request("POST", "/api/v1/sample/echo", "first", auth()))
            assertEquals(200, connection.readResponse().status)
            // An authenticated request's body is read before routing, so even a 404 consumes it.
            connection.send(request("POST", "/api/v1/unknown/echo", smuggled, auth()))
            assertEquals(404, connection.readResponse().status)
            connection.send(request("POST", "/api/v1/sample/echo", "second", auth()))
            val response = connection.readResponse()
            assertEquals(200, response.status)
            assertEquals("{\"ok\":true}", response.body)
            assertEquals("keep-alive", response.headers["connection"])
        }
        assertEquals(listOf("first", "second"), tab.bodies)
    }

    @Test
    fun `a 503 after the body was read leaves the connection usable`() {
        startServer(maxConcurrent = 1, queueCapacity = 0)
        val blocker =
            thread {
                client.newCall(
                    Request.Builder()
                        .url(url("/api/v1/sample/block"))
                        .post(ByteArray(0).toRequestBody())
                        .header("Authorization", "Bearer $token")
                        .build(),
                ).execute().close()
            }
        assertTrue(tab.blocking.await(5, TimeUnit.SECONDS))
        Connection().use { connection ->
            connection.send(request("POST", "/api/v1/sample/echo", smuggled, auth()))
            assertEquals(503, connection.readResponse().status)

            tab.release.countDown()
            blocker.join(5_000)
            connection.send(request("POST", "/api/v1/sample/echo", "after", auth()))
            val response = connection.readResponse()
            assertEquals(200, response.status)
            assertEquals("{\"ok\":true}", response.body)
        }
        assertEquals(listOf("after"), tab.bodies)
    }

    @Test
    fun `after a 401 for a POST the client's next request succeeds`() {
        // A console whose cookie went stale gets a 401 for each POST, then reuses the connection.
        startServer()
        client.newCall(Request.Builder().url(url("/api/v1/sample/echo")).post("abc".toRequestBody()).build())
            .execute()
            .use { assertEquals(401, it.code) }
        authorizedGet("/api/v1/_meta").use { assertEquals(200, it.code) }
    }

    @Test
    fun `a slow _auth body holds no concurrency slot`() {
        startServer(maxConcurrent = 1, queueCapacity = 0)
        val json = "{\"token\":\"$token\"}"
        Connection().use { slow ->
            slow.send("POST /api/v1/_auth HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: ${json.length}\r\n\r\n{")
            // Time for the server to start reading the body, which used to hold
            // the only concurrency slot while it waited.
            Thread.sleep(300)
            assertEquals(0, server!!.inFlightCount())
            authorizedGet("/api/v1/_meta").use { assertEquals(200, it.code) }

            slow.send(json.drop(1))
            val response = slow.readResponse()
            assertEquals(200, response.status)
            assertTrue(response.headers["set-cookie"]!!.startsWith("lustro_token=$token;"))
        }
    }

    @Test
    fun `a slow body holds no concurrency slot and reaches the tab whole`() {
        startServer(maxConcurrent = 1, queueCapacity = 0)
        val body = "{\"name\":\"value\"}"
        Connection().use { slow ->
            slow.send(request("POST", "/api/v1/sample/echo", body.take(5), auth(), "Content-Length: ${body.length}"))
            Thread.sleep(300)
            assertEquals(0, server!!.inFlightCount())
            authorizedGet("/api/v1/_meta").use { assertEquals(200, it.code) }
            assertTrue("the tab waits for the whole body", tab.bodies.isEmpty())

            slow.send(body.drop(5))
            assertEquals(200, slow.readResponse().status)
        }
        assertEquals(listOf(body), tab.bodies)
    }

    @Test
    fun `bodies wait for room within the slots' worth of memory`() {
        // One slot and a 16-byte cap leave room for one 16-byte body at a time.
        startServer(maxRequestBodyBytes = 16, maxConcurrent = 1, queueCapacity = 0, timeoutMs = 300)
        Connection().use { slow ->
            slow.send(request("POST", "/api/v1/sample/echo", "0123", auth(), "Content-Length: 16"))
            Thread.sleep(200)
            Connection().use { other ->
                // Waits for room for the request timeout, then gives up.
                other.send(request("POST", "/api/v1/sample/echo", "x", auth()))
                assertEquals(503, other.readResponse().status)
            }
            // A request without a body needs no room.
            authorizedGet("/api/v1/_meta").use { assertEquals(200, it.code) }

            slow.send("456789abcdef")
            assertEquals(200, slow.readResponse().status)
        }
        Connection().use { connection ->
            connection.send(request("POST", "/api/v1/sample/echo", "y", auth()))
            assertEquals(200, connection.readResponse().status)
        }
        assertEquals(listOf("0123456789abcdef", "y"), tab.bodies)
    }

    @Test
    fun `a client that stops sending partway through its body gets no response`() {
        startServer()
        Connection().use { connection ->
            connection.send(request("POST", "/api/v1/sample/echo", "{\"partial\":", auth(), "Content-Length: 100"))
            connection.socket.shutdownOutput()
            connection.assertEndsWithoutMore()
        }
        assertTrue("the tab never saw the partial body: ${tab.bodies}", tab.bodies.isEmpty())
        uncaught.assertNothingUncaught()
    }
}
