package io.github.twinsen81.lustro.internal

import androidx.test.core.app.ApplicationProvider
import fi.iki.elonen.NanoHTTPD
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Socket options that [LustroServer] sets on each connection it accepts. The test
 * checks the option itself, not a response time: the delay that the option
 * removes depends on the TCP stack of the machine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LustroServerSocketTest {
    private lateinit var server: LustroServer
    private val accepted = LinkedBlockingQueue<Socket>()

    private fun startServer() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val registry = DebugTabRegistry().apply { start() }
        server = LustroServer("127.0.0.1", 0, registry, DebugAssetLoader(context), LustroTokenStore(context), allowedOrigins = emptyList())
        server.setServerSocketFactory(
            NanoHTTPD.ServerSocketFactory {
                object : ServerSocket() {
                    override fun accept(): Socket = super.accept().also(accepted::add)
                }
            },
        )
        server.start(2000, false)
    }

    @After
    fun tearDown() {
        server.stop()
        server.shutdownLimiter()
    }

    @Test
    fun `an accepted connection has TCP_NODELAY, so a response on a reused connection is not delayed`() {
        startServer()
        Socket("127.0.0.1", server.listeningPort).use { client ->
            client.soTimeout = 5_000
            client.getOutputStream().write("GET /nope HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
            // A response means the server has set up the connection; it stays open
            // for the next request.
            val statusLine = client.getInputStream().bufferedReader().readLine()
            assertTrue(statusLine, statusLine.startsWith("HTTP/1.1 404"))

            val serverSide = accepted.poll(5, TimeUnit.SECONDS)
            assertNotNull(serverSide)
            assertTrue(serverSide!!.tcpNoDelay)
        }
    }
}
