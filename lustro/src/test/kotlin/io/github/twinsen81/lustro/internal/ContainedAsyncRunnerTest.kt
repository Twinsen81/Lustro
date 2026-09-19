package io.github.twinsen81.lustro.internal

import fi.iki.elonen.NanoHTTPD
import io.github.twinsen81.lustro.UncaughtExceptionRecorder
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.Socket

/**
 * Tests [ContainedAsyncRunner] on a bare NanoHTTPD server, where nothing in
 * `serve()` catches: whatever it throws reaches the runner's connection thread.
 * The server allows two open connections.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContainedAsyncRunnerTest {
    @get:Rule
    val uncaught = UncaughtExceptionRecorder()

    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()

    private val server =
        object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response =
                if (session.uri == "/error") {
                    throw OutOfMemoryError("simulated")
                } else {
                    newFixedLengthResponse("ok")
                }
        }.apply { setAsyncRunner(ContainedAsyncRunner(maxConnections = 2)) }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun get(path: String): String =
        client.newCall(Request.Builder().url("http://127.0.0.1:${server.listeningPort}$path").build())
            .execute()
            .use { it.body!!.string() }

    @Test
    fun `an Error escaping serve() stays on the connection thread`() {
        server.start(2_000, false)
        // The connection closes without a response, and the server keeps accepting.
        assertThrows(IOException::class.java) { get("/error") }
        assertEquals("ok", get("/ok"))
        uncaught.assertNothingUncaught()
    }

    @Test
    fun `connections past the cap are closed until one ends`() {
        server.start(30_000, false)
        val held = List(2) { Socket("127.0.0.1", server.listeningPort) }
        try {
            // Closed before the server reads anything, so the client sees a clean end of stream.
            Socket("127.0.0.1", server.listeningPort).use { extra ->
                extra.soTimeout = 5_000
                assertEquals(-1, extra.getInputStream().read())
            }

            held[0].close()

            // The freed slot takes new connections once the server sees that one end.
            val deadline = System.currentTimeMillis() + 5_000
            var body: String? = null
            while (body == null && System.currentTimeMillis() < deadline) {
                body =
                    try {
                        get("/ok")
                    } catch (_: IOException) {
                        Thread.sleep(20)
                        null
                    }
            }
            assertEquals("ok", body)
        } finally {
            held.forEach { it.close() }
        }
    }

    @Test
    fun `stop closes idle keep-alive connections`() {
        // A read timeout far longer than the client's, so only stop() can end the connection in time.
        server.start(30_000, false)
        Socket("127.0.0.1", server.listeningPort).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write("GET /ok HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
            val input = socket.getInputStream()
            val response = StringBuilder()
            while (!response.endsWith("\r\n\r\nok")) {
                val byte = input.read()
                check(byte >= 0) { "connection closed mid-response: $response" }
                response.append(byte.toChar())
            }
            assertTrue(response.contains("Connection: keep-alive", ignoreCase = true))

            server.stop()

            assertEquals("server closed the connection", -1, input.read())
        }
    }
}
