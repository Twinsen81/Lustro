package io.github.twinsen81.lustro.sample

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SampleFlagsTabTest {
    private val tab = SampleFlagsTab(ApplicationProvider.getApplicationContext<Application>())

    @Test
    fun `the app reads the seeded flags, with their defaults`() {
        val flags = tab.flags.value

        assertTrue(SampleFlags.isOn(flags, SampleFlags.HOME_INTRO))
        assertFalse(SampleFlags.isOn(flags, SampleFlags.DARK_THEME))
    }

    @Test
    fun `a flag that the app sets shows in the flow`() {
        assertTrue(tab.setEnabled(SampleFlags.DARK_THEME, true))

        assertTrue(SampleFlags.isOn(tab.flags.value, SampleFlags.DARK_THEME))
        assertEquals(3, tab.flags.value.size)
    }

    @Test
    fun `an unknown flag is not added`() {
        assertFalse(tab.setEnabled("no-such-flag", true))
        assertEquals(3, tab.flags.value.size)
    }

    @Test
    fun `without the tab the app uses its defaults`() {
        assertTrue(SampleFlags.isOn(null, SampleFlags.HOME_INTRO))
        assertFalse(SampleFlags.isOn(null, SampleFlags.DARK_THEME))
    }
}
