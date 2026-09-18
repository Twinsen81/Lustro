package io.github.twinsen81.lustro

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.internal.DebugTabRegistry
import io.github.twinsen81.lustro.internal.LustroTokenStore
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lifecycle + binding tests for [Lustro]. Drives binding
 * through an injected [LifecycleRegistry] rather than the real
 * [androidx.lifecycle.ProcessLifecycleOwner], using the internal
 * [Lustro.onForeground]/[Lustro.onBackground] seams and the lifecycle observer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LustroLifecycleTest {
    private val client = OkHttpClient.Builder().followRedirects(false).build()

    private lateinit var app: Application
    private lateinit var owner: FakeLifecycleOwner
    private val registry: LifecycleRegistry
        get() = owner.registry
    private val started = mutableListOf<Lustro>()

    /** A standalone [LifecycleOwner] backed by a drivable [LifecycleRegistry]. */
    private class FakeLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        owner = FakeLifecycleOwner()
        // Start in CREATED so start() does not eagerly bind; tests drive foreground.
        registry.currentState = Lifecycle.State.CREATED
    }

    @After
    fun tearDown() {
        started.forEach { runCatching { it.stop() } }
        started.clear()
    }

    // Port 0 by default so binding picks a free ephemeral port (the fixed default
    // 8080 may be taken or blocked in the test sandbox); collision tests override.
    private fun lustro(config: DebugConfig = DebugConfig.builder().serverPort(0).build()): Lustro {
        // A fresh (unstarted) internal registry; Lustro.start() freezes it. No tabs
        // are needed — the unauthenticated /shared.js asset route proves binding.
        val tabRegistry = DebugTabRegistry()
        return Lustro(app, config, tabRegistry, registry).also { started.add(it) }
    }

    private fun get(port: Int, path: String): okhttp3.Response =
        client.newCall(Request.Builder().url("http://127.0.0.1:$port$path").get().build()).execute()

    // Backgrounding now drains+closes on an internal thread, so onStop returns
    // before the socket is actually down. Poll for the expected bound state.
    private fun awaitBound(lustro: Lustro, expected: Boolean, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (lustro.isBound() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals("socket bound state", expected, lustro.isBound())
    }


    @Test
    fun `start arms and returns ENABLED, second start is idempotent`() {
        val lustro = lustro()
        assertEquals(LustroStatus.ENABLED, lustro.start())
        assertEquals(LustroStatus.ENABLED, lustro.start())
    }

    @Test
    fun `stop is idempotent and safe before start`() {
        val lustro = lustro()
        lustro.stop() // before start: no-op
        lustro.start()
        lustro.stop()
        lustro.stop() // second stop: no-op
        assertFalse(lustro.isBound())
    }



    @Test
    fun `foreground binds the socket and background closes it`() {
        val lustro = lustro()
        lustro.start()
        assertFalse("armed but not foregrounded yet", lustro.isBound())

        registry.currentState = Lifecycle.State.STARTED // fires onStart -> bind
        assertTrue(lustro.isBound())
        val port = lustro.boundPort()
        assertNotEquals(-1, port)
        get(port, "/shared.js").use { assertEquals(200, it.code) }

        registry.currentState = Lifecycle.State.CREATED // fires onStop -> async drain+close
        awaitBound(lustro, expected = false)
    }

    @Test
    fun `re-foregrounding re-binds after a background close`() {
        val lustro = lustro()
        lustro.start()
        registry.currentState = Lifecycle.State.STARTED
        assertTrue(lustro.isBound())
        registry.currentState = Lifecycle.State.CREATED
        awaitBound(lustro, expected = false)
        registry.currentState = Lifecycle.State.STARTED
        assertTrue("re-binds on the next foreground", lustro.isBound())
    }

    @Test
    fun `start binds eagerly when the process is already foregrounded`() {
        registry.currentState = Lifecycle.State.RESUMED // already in foreground
        val lustro = lustro()
        assertEquals(LustroStatus.ENABLED, lustro.start())
        assertTrue("binds eagerly without waiting for a fresh onStart", lustro.isBound())
    }

    /** An [Application] over [base] that records the threads opening prefs through it. */
    private class PrefsRecordingApplication(base: Context) : Application() {
        val prefsThreads = CopyOnWriteArrayList<Thread>()

        init {
            attachBaseContext(base)
        }

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            prefsThreads += Thread.currentThread()
            return super.getSharedPreferences(name, mode)
        }
    }

    @Test
    fun `binding leaves the token read to the worker thread`() {
        val recording = PrefsRecordingApplication(app)
        val lustro =
            Lustro(recording, DebugConfig.builder().serverPort(0).build(), DebugTabRegistry(), registry)
                .also { started.add(it) }
        lustro.start()
        registry.currentState = Lifecycle.State.STARTED // fires onStart -> bind, on this thread
        assertTrue(lustro.isBound())

        val endpoint = "Lustro ready endpoint=http://127.0.0.1:${lustro.boundPort()} token="
        val deadline = System.currentTimeMillis() + 5_000
        var line: String? = null
        while (line == null && System.currentTimeMillis() < deadline) {
            line = ShadowLog.getLogsForTag("LustroToken").map { it.msg }.firstOrNull { it.startsWith(endpoint) }
            if (line == null) Thread.sleep(10)
        }
        assertEquals(endpoint + LustroTokenStore(app).token(), line)
        assertTrue("the endpoint log read the token", recording.prefsThreads.isNotEmpty())
        assertFalse(
            "the binding thread must not open the prefs file",
            Thread.currentThread() in recording.prefsThreads,
        )
    }

    @Test
    fun `stop disarms so later lifecycle events do not re-bind`() {
        val lustro = lustro()
        lustro.start()
        registry.currentState = Lifecycle.State.STARTED
        assertTrue(lustro.isBound())
        lustro.stop()
        assertFalse(lustro.isBound())
        // Removing the observer means foreground events no longer reach us.
        registry.currentState = Lifecycle.State.CREATED
        registry.currentState = Lifecycle.State.STARTED
        assertFalse("disarmed: no re-bind", lustro.isBound())
    }

    @Test
    fun `a tab lifecycle callback that throws an Error does not break start or stop`() {
        val calls = mutableListOf<String>()
        val throwing =
            object : DebugTab() {
                override val id = "a-throws"
                override val title = "Throws"
                override val icon = "T"

                override fun onStart() {
                    TODO("simulated")
                }

                override fun onStop() {
                    TODO("simulated")
                }
            }
        val recording =
            object : DebugTab() {
                override val id = "b-records"
                override val title = "Records"
                override val icon = "R"

                override fun onStart() {
                    calls += "onStart"
                }

                override fun onStop() {
                    calls += "onStop"
                }
            }
        val tabRegistry = DebugTabRegistry().apply {
            addTab(throwing)
            addTab(recording)
        }
        val lustro =
            Lustro(app, DebugConfig.builder().serverPort(0).build(), tabRegistry, registry)
                .also { started.add(it) }

        // The throwing tab sorts first, so the other one is notified after it fails.
        assertEquals(LustroStatus.ENABLED, lustro.start())
        lustro.stop()
        assertEquals(listOf("onStart", "onStop"), calls)
    }



    @Test
    fun `port collision with bindFallback false yields DISABLED-style unbound server`() {
        ServerSocket().use { squatter ->
            squatter.bind(java.net.InetSocketAddress("127.0.0.1", 0))
            val takenPort = squatter.localPort
            val config =
                DebugConfig.builder()
                    .serverPort(takenPort)
                    .bindFallback(false)
                    .build()
            registry.currentState = Lifecycle.State.STARTED
            val lustro = lustro(config)
            // Arming still succeeds (ENABLED); the bind itself fails and leaves the
            // server unbound because fallback is off.
            assertEquals(LustroStatus.ENABLED, lustro.start())
            assertFalse("no fallback: socket must not be bound", lustro.isBound())
        }
    }

    @Test
    fun `port collision with bindFallback true binds on an ephemeral port`() {
        ServerSocket().use { squatter ->
            squatter.bind(java.net.InetSocketAddress("127.0.0.1", 0))
            val takenPort = squatter.localPort
            val config =
                DebugConfig.builder()
                    .serverPort(takenPort)
                    .bindFallback(true)
                    .build()
            registry.currentState = Lifecycle.State.STARTED
            val lustro = lustro(config)
            assertEquals(LustroStatus.ENABLED, lustro.start())
            assertTrue("fallback: must bind on an ephemeral port", lustro.isBound())
            assertNotEquals("ephemeral port differs from the taken one", takenPort, lustro.boundPort())
            get(lustro.boundPort(), "/shared.js").use { assertEquals(200, it.code) }
        }
    }

}
