package io.github.twinsen81.lustro.work

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import io.github.twinsen81.lustro.work.internal.WorkSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `the tab is API only for now, under the id work`() {
        val tab = WorkDebugTab.create(app)
        assertEquals("work", tab.id)
        assertFalse(tab.showInTabBar)
        assertEquals("", tab.renderContent())
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
        val old = WorkSupport.missing(Hiding(javaClass.classLoader!!, "androidx.work.ExecutionEventListener"))
        assertTrue(old!!.contains("2.12.0"))
    }

    // A class loader that can't find one class, as an app with an older WorkManager can't.
    private class Hiding(parent: ClassLoader, private val hidden: String) : ClassLoader(parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> =
            if (name == hidden) throw ClassNotFoundException(name) else super.loadClass(name, resolve)
    }
}
