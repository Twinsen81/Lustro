package io.github.twinsen81.lustro.screen.internal

import android.view.View
import io.github.twinsen81.lustro.screen.internal.ScreenFixtures.json
import io.github.twinsen81.lustro.screen.internal.ScreenFixtures.tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The settle waits for the window focus: the window manager moves it to a new dialog
 * some frames after the dialog shows. Robolectric gives no window the focus, so these
 * tests move it themselves; with native graphics, the windows are visible.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettleFocusTest {
    @Test
    fun `a settle ends only when the dialog on top has the focus`() {
        ScreenFixtures.composeActivity(dialog = true)
        val roots = android.view.inspector.WindowInspector.getGlobalWindowViews()
        val activity = roots.first()
        val dialog = roots.last()
        var reads = 0
        // The activity keeps the focus for the first reads of each window, as before the window manager moves it.
        val moving: (View) -> Boolean = { root -> if (++reads <= MOVE_AFTER_CALLS) root === activity else root === dialog }
        val tree = json(ScreenFixtures.getOffMain(tab(focus = moving), "tree", "settle" to "2000"))
        val settle = tree.getJSONObject("settle")
        assertTrue(settle.getBoolean("settled"))
        assertTrue("frames ${settle.getInt("frames")}", settle.getInt("frames") >= MOVE_AFTER_CALLS / 2)
        val windows = tree.getJSONArray("windows")
        assertFalse(windows.getJSONObject(0).getBoolean("hasWindowFocus"))
        assertTrue(windows.getJSONObject(1).getBoolean("hasWindowFocus"))
        assertTrue(windows.getJSONObject(1).getBoolean("focusable"))
    }

    @Test
    fun `a settle while no window of the app has the focus runs to its end and says so`() {
        ScreenFixtures.composeActivity(dialog = true)
        val tree = json(ScreenFixtures.getOffMain(tab(focus = { false }), "tree", "settle" to "200"))
        val settle = tree.getJSONObject("settle")
        assertFalse(settle.getBoolean("settled"))
        assertTrue(settle.getLong("waitedMs") >= 200)
        assertEquals(2, tree.getJSONArray("windows").length())
    }

    private companion object {
        // Two windows, so two calls for each read: the focus moves on the fourth read.
        const val MOVE_AFTER_CALLS = 8
    }
}
