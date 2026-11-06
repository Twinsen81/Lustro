package io.github.twinsen81.lustro.screen

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.DebugRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScreenDebugTabTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `the tab is out of the tab bar, under the id screen, with no view`() {
        val tab = ScreenDebugTab.create(app)
        assertEquals("screen", tab.id)
        assertEquals("Screen", tab.title)
        assertFalse(tab.showInTabBar)
        assertTrue(tab.renderContent().isEmpty())
        assertNull(tab.schema())
        assertNull(tab.handle(DebugRequest("", "GET")))
    }

    @Test
    fun `the tab takes the context provider as a lambda, from Kotlin and from Java`() {
        val kotlin = ScreenDebugTab.create(app) { "{}" }
        val java = ScreenDebugTab.create(app, ScreenContextProvider { "[]" })
        assertEquals(kotlin.id, java.id)
    }
}
