package io.github.twinsen81.lustro.work

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.work.internal.WorkFixtures
import io.github.twinsen81.lustro.work.internal.WorkSupport
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkDebugTabTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `the tab is in the tab bar, under the id work, with its view`() {
        val tab = WorkDebugTab.create(app)
        assertEquals("work", tab.id)
        assertTrue(tab.showInTabBar)
        assertTrue(tab.renderContent().contains("id=\"work-root\""))
    }

    @Test
    fun `the tab records events only between onStart and onStop, and forwards to the app's listener always`() {
        val tab = WorkDebugTab.create(app)
        val listeners = WorkFixtures.AppListeners()
        val listener = tab.configure(Configuration.Builder().setExecutionEventListener(listeners)).build().getExecutionEventListener()!!
        val info = WorkInfo(UUID.randomUUID(), WorkInfo.State.RUNNING, emptySet())
        runBlocking { listener.onStarted(info) }
        tab.onStart()
        runBlocking { listener.onStarted(info) }
        tab.onStop()
        runBlocking { listener.onStarted(info) }
        assertEquals(3, listeners.seen.size)
        val events = JSONObject(String(tab.handle(DebugRequest("events", "GET"))!!.body))
        assertEquals(1, events.getJSONArray("items").length())
    }

    @Test
    fun `a buffer that is not positive is refused`() {
        assertThrows(IllegalArgumentException::class.java) { WorkDebugTab.create(app, bufferBytes = 0) }
    }

    @Test
    fun `configure returns the builder that it was given, with the tab's listeners on it`() {
        val builder = Configuration.Builder()
        val returned = WorkDebugTab.create(app).configure(builder)
        assertTrue(returned === builder)
        assertTrue(builder.build().getExecutionEventListener() != null)
    }

    @Test
    fun `the support check names what the app's class loader lacks`() {
        assertNull(WorkSupport.missing(javaClass.classLoader))
        val none = WorkSupport.missing(Hiding(javaClass.classLoader!!, "androidx.work.WorkManager"))
        assertTrue(none!!.contains("no WorkManager"))
        val old = WorkSupport.missing(Hiding(javaClass.classLoader!!, "androidx.work.ExecutionEventListener", version = "2.11.2"))
        assertEquals("The tab needs WorkManager 2.12.0 or newer; the app has 2.11.2", old)
        val unknown = WorkSupport.missing(Hiding(javaClass.classLoader!!, "androidx.work.ExecutionEventListener", version = null))
        assertEquals("The tab needs WorkManager 2.12.0 or newer; the app has an older one", unknown)
    }

    @Test
    fun `the version of the app's WorkManager comes from the version file of its jar, when the APK has it`() {
        // The tests run on the catalog's newest WorkManager, which has the file.
        val version = WorkSupport.runtimeVersion(javaClass.classLoader)
        assertTrue(version, version.orEmpty().matches(Regex("""2\.\d+\.\d+.*""")))
        assertNull(WorkSupport.runtimeVersion(Hiding(javaClass.classLoader!!, "none", version = null)))
        assertNull(WorkSupport.runtimeVersion(Hiding(javaClass.classLoader!!, "none", version = "not\na version")))
    }

    // A class loader that can't find one class, as an app with an older WorkManager can't, and
    // that has [version] as the version file of work-runtime, or none.
    private class Hiding(parent: ClassLoader, private val hidden: String, private val version: String? = "2.12.0") : ClassLoader(parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> =
            if (name == hidden) throw ClassNotFoundException(name) else super.loadClass(name, resolve)

        override fun getResourceAsStream(name: String): InputStream? =
            if (name == "META-INF/androidx.work_work-runtime.version") version?.byteInputStream() else super.getResourceAsStream(name)
    }
}
