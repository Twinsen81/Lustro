package io.github.twinsen81.lustro.screen.internal

import android.app.Activity
import io.github.twinsen81.lustro.screen.internal.ScreenFixtures.get
import io.github.twinsen81.lustro.screen.internal.ScreenFixtures.json
import io.github.twinsen81.lustro.screen.internal.ScreenFixtures.tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class WindowRootsTest {
    @Test
    @Config(sdk = [28])
    fun `before API 29 the tab lists the windows through WindowManagerGlobal, dialogs too`() {
        ScreenFixtures.composeActivity(dialog = true)
        val tree = json(get(tab(), "tree"))
        assertEquals("reflection", tree.getString("windowSource"))
        assertEquals(2, tree.getJSONArray("windows").length())
    }

    @Test
    fun `the activity tracker has the decor views of the started activities only`() {
        val tracker = ActivityTracker()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        tracker.onActivityStarted(activity)
        assertEquals(listOf(activity.window.decorView), tracker.decorViews())
        tracker.onActivityStarted(activity)
        assertEquals(1, tracker.decorViews().size)
        tracker.onActivityStopped(activity)
        assertTrue(tracker.decorViews().isEmpty())
    }

    @Test
    fun `a window's kind comes from its type`() {
        assertEquals("activity", TreeReader.windowKind(android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION))
        assertEquals("dialog", TreeReader.windowKind(android.view.WindowManager.LayoutParams.TYPE_APPLICATION))
        assertEquals("popup", TreeReader.windowKind(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL))
        assertEquals("system", TreeReader.windowKind(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY))
        assertEquals("other", TreeReader.windowKind(null))
    }
}
