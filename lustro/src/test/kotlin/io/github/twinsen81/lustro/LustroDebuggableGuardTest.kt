package io.github.twinsen81.lustro

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.internal.DebugTabRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * The non-debuggable runtime guard: [Lustro.start] refuses to arm when the host
 * app does not carry `FLAG_DEBUGGABLE`, unless
 * [DebugConfig.allowNonDebuggableBuilds] opts in. This is the backstop for a
 * consumer whose Gradle wiring puts the real runtime on a release variant, where
 * neither the published lint check nor the `:lustro-noop` swap applies.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LustroDebuggableGuardTest {
    private lateinit var app: Application
    private lateinit var owner: FakeLifecycleOwner
    private val started = mutableListOf<Lustro>()

    private class FakeLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        owner = FakeLifecycleOwner()
        // STARTED so an armed server binds eagerly inside start() and `isBound()`
        // separates "refused to arm" from "armed but not yet bound".
        owner.registry.currentState = Lifecycle.State.STARTED
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        started.forEach { runCatching { it.stop() } }
        started.clear()
    }

    private fun setDebuggable(debuggable: Boolean) {
        val flags = app.applicationInfo.flags
        app.applicationInfo.flags =
            if (debuggable) {
                flags or ApplicationInfo.FLAG_DEBUGGABLE
            } else {
                flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
            }
    }

    // Port 0 so binding picks a free ephemeral port; the fixed default 8080 may be
    // taken in the test sandbox.
    private fun lustro(allowNonDebuggable: Boolean = false): Lustro {
        val config =
            DebugConfig.builder()
                .serverPort(0)
                .allowNonDebuggableBuilds(allowNonDebuggable)
                .build()
        return Lustro(app, config, DebugTabRegistry(), owner.registry).also { started.add(it) }
    }

    private fun refusalLogged(): Boolean =
        ShadowLog.getLogsForTag("Lustro").any { it.msg.contains("not marked debuggable") }

    @Test
    fun `a non-debuggable build does not arm and does not bind`() {
        setDebuggable(false)
        val lustro = lustro()

        assertEquals(LustroStatus.DISABLED, lustro.start())
        assertFalse("socket must stay closed", lustro.isBound())
        assertEquals("no port is bound", -1, lustro.boundPort())
        assertTrue("the refusal is logged at WARN", refusalLogged())
    }

    @Test
    fun `a non-debuggable build arms when allowNonDebuggableBuilds opts in`() {
        setDebuggable(false)
        val lustro = lustro(allowNonDebuggable = true)

        assertEquals(LustroStatus.ENABLED, lustro.start())
        assertTrue("socket is bound", lustro.isBound())
        assertFalse("no refusal is logged", refusalLogged())
    }

    @Test
    fun `a debuggable build arms with the default config`() {
        setDebuggable(true)
        val lustro = lustro()

        assertEquals(LustroStatus.ENABLED, lustro.start())
        assertTrue("socket is bound", lustro.isBound())
        assertFalse("no refusal is logged", refusalLogged())
    }

    @Test
    fun `a refused start leaves stop and a later start harmless`() {
        setDebuggable(false)
        val lustro = lustro()

        assertEquals(LustroStatus.DISABLED, lustro.start())
        lustro.stop()
        assertEquals("start stays refused and never throws", LustroStatus.DISABLED, lustro.start())
        assertFalse(lustro.isBound())
    }
}
