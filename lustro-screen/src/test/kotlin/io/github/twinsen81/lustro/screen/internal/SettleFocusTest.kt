package io.github.twinsen81.lustro.screen.internal

import android.view.View
import androidx.compose.runtime.mutableStateOf
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
    fun `a settle waits while the window under the dialog still says that it has the focus`() {
        ScreenFixtures.composeActivity(dialog = true)
        val roots = android.view.inspector.WindowInspector.getGlobalWindowViews()
        val dialog = roots.last()
        var reads = 0
        // As on API 24: the dialog has the focus, and the activity's window loses it some reads later.
        val both: (View) -> Boolean = { root -> root === dialog || ++reads <= MOVE_AFTER_CALLS / 2 }
        val tree = json(ScreenFixtures.getOffMain(tab(focus = both), "tree", "settle" to "2000"))
        assertTrue(tree.getJSONObject("settle").getBoolean("settled"))
        val windows = tree.getJSONArray("windows")
        assertFalse(windows.getJSONObject(0).getBoolean("hasWindowFocus"))
        assertTrue(windows.getJSONObject(1).getBoolean("hasWindowFocus"))
    }

    @Test
    fun `with two activities on the screen, the focus is at rest on the earlier one too`() {
        val first = ScreenFixtures.viewActivity()
        ScreenFixtures.composeActivity()
        // As in split screen or picture-in-picture: the user's tap gave the earlier activity the focus.
        val firstRoot = first.window.decorView
        val tree = json(ScreenFixtures.getOffMain(tab(focus = { it === firstRoot }), "tree", "settle" to "2000"))
        val settle = tree.getJSONObject("settle")
        assertTrue(settle.toString(), settle.getBoolean("settled"))
        assertTrue(settle.getLong("waitedMs") < 2000)
        assertEquals(2, tree.getJSONArray("windows").length())
    }

    @Test
    fun `a dialog is in the group of its activity, and a second activity in its own`() {
        ScreenFixtures.composeActivity(dialog = true)
        ScreenFixtures.viewActivity()
        val roots = android.view.inspector.WindowInspector.getGlobalWindowViews()
        assertEquals(listOf(0, 0, 2), WindowFocus.groups(roots).toList())
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

    @Test
    fun `a settle does not end on a window that has not had its first frame yet`() {
        val activity = ScreenFixtures.composeActivity()
        val dialog = android.app.Dialog(activity).apply { setContentView(android.widget.TextView(activity).apply { text = "Sure?" }) }
        // Shown, and so in the windows, but not visible before its first frame.
        val tree = json(ScreenFixtures.getOffMain(tab(focus = ::topAttached), "tree", "settle" to "2000", beforeMain = dialog::show))
        assertTrue(tree.getJSONObject("settle").getBoolean("settled"))
        val windows = tree.getJSONArray("windows")
        assertEquals(2, windows.length())
        assertTrue(windows.getJSONObject(1).getBoolean("visible"))
        assertTrue(windows.getJSONObject(1).getBoolean("hasWindowFocus"))
        assertFalse(windows.getJSONObject(0).getBoolean("hasWindowFocus"))
    }

    @Test
    fun `an action waits for the focus to reach a new dialog, and so refuses the node under it`() {
        var saved = 0
        ScreenFixtures.composeActivity(dialog = true, onSave = { saved++ })
        val roots = android.view.inspector.WindowInspector.getGlobalWindowViews()
        val activity = roots.first()
        val dialog = roots.last()
        var reads = 0
        // Right after the tap that opened the dialog, the activity still has the focus.
        val moving: (View) -> Boolean = { root -> if (++reads <= MOVE_AFTER_CALLS) root === activity else root === dialog }
        val response = ScreenFixtures.postOffMain(tab(focus = moving), "click", """{"tag":"save"}""")
        assertEquals(409, response.status)
        assertEquals("not_in_focused_window", json(response).getString("code"))
        assertEquals(0, saved)
    }

    @Test
    fun `an action right after the tap that opens a Compose dialog waits for the dialog, and so refuses the node under it`() {
        var saved = 0
        val show = mutableStateOf(false)
        ScreenFixtures.composeActivity(show, onSave = { saved++ })
        // The tap was handled before the request came: Compose adds the dialog's window on the next frame.
        val response = ScreenFixtures.postOffMain(tab(focus = ::topAttached), "click", """{"tag":"save"}""", beforeMain = { show.value = true })
        assertEquals(json(response).toString(), 409, response.status)
        assertEquals("not_in_focused_window", json(response).getString("code"))
        assertEquals(0, saved)
    }

    @Test
    fun `an action takes the top window as the focused one while the window under it still says that it has the focus`() {
        var saved = 0
        ScreenFixtures.composeActivity(dialog = true, onSave = { saved++ })
        // As on API 24 for some frames after the dialog got the focus, past the action's wait: both windows say so.
        val response = ScreenFixtures.postOffMain(tab(focus = { true }), "click", """{"tag":"save"}""")
        assertEquals(409, response.status)
        assertEquals("not_in_focused_window", json(response).getString("code"))
        assertEquals(0, saved)
    }

    @Test
    fun `with two activities on the screen, an action on the earlier one that has the focus does not wait for the focus to move`() {
        val first = ScreenFixtures.viewActivity()
        ScreenFixtures.composeActivity()
        val firstRoot = first.window.decorView
        first.findViewById<View>(android.R.id.content).let { content ->
            (content as android.view.ViewGroup).getChildAt(0).tag = "first screen"
        }
        val started = System.nanoTime()
        val response = ScreenFixtures.postOffMain(tab(focus = { it === firstRoot }), "scroll", """{"text":"Item 1","dy":40}""")
        assertEquals(200, response.status)
        // No wait for the focus: it is at rest on the earlier activity.
        assertTrue(json(response).getJSONObject("tree").getJSONObject("settle").getBoolean("settled"))
        assertTrue((System.nanoTime() - started) / 1_000_000 < ScreenSource.FOCUS_WAIT_MS * 4)
    }

    private companion object {
        // Two windows, so two calls for each read: the focus moves on the fourth read.
        const val MOVE_AFTER_CALLS = 8

        // As the window manager does, a frame after a window's first frame at the soonest: the top window that has had one has the focus.
        fun topAttached(root: View): Boolean =
            root === android.view.inspector.WindowInspector.getGlobalWindowViews().lastOrNull { it.isAttachedToWindow }
    }
}
