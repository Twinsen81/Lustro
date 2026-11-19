package io.github.twinsen81.lustro

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.internal.DebugTabRegistry
import io.github.twinsen81.lustro.internal.InterfaceAddress
import io.github.twinsen81.lustro.internal.LustroTokenStore
import java.net.InetAddress
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/** [Lustro.endpoint]: what an app's own code, such as an instrumentation test, connects to. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LustroEndpointTest {
    private class FakeLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override fun getLifecycle(): Lifecycle = registry
    }

    private lateinit var app: Application
    private val owner = FakeLifecycleOwner()
    private val started = mutableListOf<Lustro>()
    private val wifi = listOf(InterfaceAddress("wlan0", InetAddress.getByName("192.168.1.23")))

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        owner.registry.currentState = Lifecycle.State.CREATED
    }

    @After
    fun tearDown() {
        started.forEach { runCatching { it.stop() } }
    }

    private fun lustro(bindAddress: String = "127.0.0.1"): Lustro =
        Lustro(app, DebugConfig.builder().serverPort(0).bindAddress(bindAddress).build(), DebugTabRegistry(), owner.registry) { wifi }
            .also { started.add(it) }

    @Test
    fun `endpoint is null until the server listens and after stop`() {
        val lustro = lustro()
        assertNull("before start", lustro.endpoint())
        lustro.start()
        assertNull("armed in the background", lustro.endpoint())

        owner.registry.currentState = Lifecycle.State.STARTED
        val endpoint = lustro.endpoint()
        assertNotNull(endpoint)

        lustro.stop()
        assertNull("after stop", lustro.endpoint())
    }

    @Test
    fun `endpoint gives the bound loopback host, port, and token, which a client can call`() {
        val lustro = lustro()
        lustro.start()
        owner.registry.currentState = Lifecycle.State.STARTED

        val endpoint = lustro.endpoint()!!

        assertEquals("127.0.0.1", endpoint.host)
        assertEquals(lustro.boundPort(), endpoint.port)
        assertEquals(LustroTokenStore(app).token(), endpoint.token)
        assertEquals("http://127.0.0.1:${lustro.boundPort()}", endpoint.url)
        assertEquals(emptyList<String>(), endpoint.lanHosts)
        assertFalse("toString leaves the token out", endpoint.token in endpoint.toString())
        val request = Request.Builder().url("${endpoint.url}/api/v1/_meta").header("Authorization", "Bearer ${endpoint.token}").build()
        OkHttpClient().newCall(request).execute().use { assertEquals(200, it.code) }
    }

    @Test
    fun `with LAN exposure, endpoint gives loopback for adb forward and the device's LAN addresses`() {
        val lustro = lustro(bindAddress = "0.0.0.0")
        lustro.start()
        owner.registry.currentState = Lifecycle.State.STARTED

        val endpoint = lustro.endpoint()!!

        assertEquals("127.0.0.1", endpoint.host)
        assertEquals(listOf("192.168.1.23"), endpoint.lanHosts)
        assertEquals(listOf("http://192.168.1.23:${lustro.boundPort()}"), endpoint.lanUrls)
    }

    @Test
    fun `with LAN exposure, the endpoint log keeps its format and adds the LAN urls after the token`() {
        val lustro = lustro(bindAddress = "0.0.0.0")
        lustro.start()
        owner.registry.currentState = Lifecycle.State.STARTED
        val port = lustro.boundPort()

        val expected = "Lustro ready endpoint=http://0.0.0.0:$port token=${LustroTokenStore(app).token()} lan=http://192.168.1.23:$port"
        val deadline = System.currentTimeMillis() + 5_000
        var lines = emptyList<String>()
        while (expected !in lines && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            lines = ShadowLog.getLogsForTag("LustroToken").map { it.msg }
        }
        assertTrue(lines.toString(), expected in lines)
    }
}
