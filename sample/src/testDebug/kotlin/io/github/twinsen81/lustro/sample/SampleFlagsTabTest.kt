package io.github.twinsen81.lustro.sample

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.MediaType
import org.json.JSONObject
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
    fun `the app reads the seeded flags, and the dark theme follows the light system setting`() {
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
    fun `a change of the system setting moves the dark theme flag`() {
        tab.onSystemDarkTheme(true)
        assertTrue(SampleFlags.isOn(tab.flags.value, SampleFlags.DARK_THEME))

        tab.onSystemDarkTheme(false)
        assertFalse(SampleFlags.isOn(tab.flags.value, SampleFlags.DARK_THEME))
    }

    @Test
    fun `a toggle of the dark theme flag lasts while the system setting does not change`() {
        tab.setEnabled(SampleFlags.DARK_THEME, true)

        tab.onSystemDarkTheme(false)

        assertTrue(SampleFlags.isOn(tab.flags.value, SampleFlags.DARK_THEME))
    }

    @Test
    fun `a toggle takes the id as JSON or as a form body`() {
        fun toggle(body: String, contentType: String) =
            tab.handle(
                DebugRequest(
                    path = "toggle",
                    method = "POST",
                    body = body.toByteArray(),
                    contentType = MediaType.parse(contentType),
                ),
            )?.status

        assertEquals(200, toggle("id=${SampleFlags.DARK_THEME}", "application/x-www-form-urlencoded"))
        assertTrue(SampleFlags.isOn(tab.flags.value, SampleFlags.DARK_THEME))
        assertEquals(200, toggle("""{"id":"${SampleFlags.DARK_THEME}"}""", "application/json"))
        assertFalse(SampleFlags.isOn(tab.flags.value, SampleFlags.DARK_THEME))
        assertEquals(404, toggle("id=no-such-flag", "application/x-www-form-urlencoded"))
    }

    @Test
    fun `a form body without an id is a 400 for the id`() {
        val response =
            tab.handle(
                DebugRequest(
                    path = "toggle",
                    method = "POST",
                    body = "flag=${SampleFlags.DARK_THEME}".toByteArray(),
                    contentType = MediaType.parse("application/x-www-form-urlencoded"),
                ),
            )!!

        assertEquals(400, response.status)
        val envelope = JSONObject(response.body.toString(Charsets.UTF_8))
        assertEquals("id is required", envelope.getString("message"))
        assertEquals("id", envelope.getString("field"))
    }

    @Test
    fun `an unknown flag is not added`() {
        assertFalse(tab.setEnabled("no-such-flag", true))
        assertEquals(3, tab.flags.value.size)
    }

    @Test
    fun `without the tab the app uses its defaults`() {
        assertTrue(SampleFlags.isOn(null, SampleFlags.HOME_INTRO))
        assertTrue(SampleFlags.isOn(null, SampleFlags.DARK_THEME, default = true))
        assertFalse(SampleFlags.isOn(null, SampleFlags.DARK_THEME, default = false))
    }
}
