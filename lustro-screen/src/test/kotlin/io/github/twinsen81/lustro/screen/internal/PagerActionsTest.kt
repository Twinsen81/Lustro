package io.github.twinsen81.lustro.screen.internal

import android.app.Activity
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import io.github.twinsen81.lustro.screen.internal.ScreenFixtures.json
import io.github.twinsen81.lustro.screen.internal.ScreenFixtures.postOffMain
import io.github.twinsen81.lustro.screen.internal.ScreenFixtures.tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A ViewPager2 says that it scrolls when its RecyclerView does, but View.scrollBy() of the
 * pager moves that RecyclerView off its place. The tab turns the pager's pages instead, as a
 * swipe does, also when the action runs on the pager's RecyclerView.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class PagerActionsTest {
    // Robolectric gives no window the focus; these tests give it to every window.
    private val everyWindow = tab(focus = { true })

    @Test
    fun `a scroll of a ViewPager2 by pixels turns to the nearest page and leaves the pager in place`() {
        val pager = pager()
        val page = json(postOffMain(everyWindow, "scroll", """{"tag":"pager","dx":${pager.width}}"""))
        assertTrue(page.getBoolean("handled"))
        assertEquals(1, pager.currentItem)
        assertEquals(0, pager.scrollX)
        // A quarter of a page snaps back, as a slow swipe that short does.
        val quarter = json(postOffMain(everyWindow, "scroll", """{"tag":"pager","dx":${pager.width / 4}}"""))
        assertFalse(quarter.getBoolean("handled"))
        assertEquals(1, pager.currentItem)
        assertEquals(0, pager.scrollX)
    }

    @Test
    fun `a ViewPager2 scrolls to a page by its index, and through its RecyclerView too`() {
        val pager = pager()
        val tree = json(ScreenFixtures.getOffMain(everyWindow, "tree"))
        val node = ScreenFixtures.nodes(tree).single { it.optString("testTag") == "pager" }
        assertEquals(listOf("ScrollBy", "ScrollToIndex"), ScreenFixtures.strings(node.getJSONArray("actions")))
        assertTrue(json(postOffMain(everyWindow, "scroll", """{"tag":"pager","index":2}""")).getBoolean("handled"))
        assertEquals(2, pager.currentItem)
        // The text of a page: its nearest node with ScrollBy is the pager's RecyclerView.
        val back = json(postOffMain(everyWindow, "scroll", """{"text":"Page 3","dx":${-pager.width}}"""))
        assertTrue(back.getBoolean("handled"))
        assertEquals(1, pager.currentItem)
        assertEquals(0, pager.scrollX)
        // As a View list does, the pager ignores an index past its last page.
        assertFalse(json(postOffMain(everyWindow, "scroll", """{"tag":"pager","index":7}""")).getBoolean("handled"))
        assertEquals(1, pager.currentItem)
    }

    private fun pager(): ViewPager2 {
        val controller = Robolectric.buildActivity(Activity::class.java)
        controller.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        val activity = controller.setup().get()
        val pager = ViewPager2(activity).apply {
            tag = "pager"
            adapter = Pages()
        }
        activity.setContentView(pager, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 400))
        ScreenFixtures.idle()
        return pager
    }

    private class Pages : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val page = TextView(parent.context)
            page.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            return object : RecyclerView.ViewHolder(page) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            (holder.itemView as TextView).text = "Page ${position + 1}"
        }

        override fun getItemCount(): Int = 3
    }
}
