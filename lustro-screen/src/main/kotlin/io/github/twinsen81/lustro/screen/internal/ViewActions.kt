package io.github.twinsen81.lustro.screen.internal

import android.view.View
import android.widget.AbsListView
import android.widget.EditText
import kotlin.math.roundToInt

/** The actions of a classic View: what the View's own API does when a user acts on it. */
internal object ViewActions {
    private const val RECYCLER_VIEW = "androidx.recyclerview.widget.RecyclerView"
    private const val RECYCLER_VIEW_ADAPTER = "androidx.recyclerview.widget.RecyclerView\$Adapter"
    private const val VIEW_PAGER_2 = "androidx.viewpager2.widget.ViewPager2"

    // ViewPager2.ORIENTATION_HORIZONTAL.
    private const val PAGER_HORIZONTAL = 0

    /** Whether [view] scrolls to an item by its index: a ListView, a GridView, a RecyclerView, or a ViewPager2. */
    fun scrollsToIndex(view: View): Boolean = view is AbsListView || isA(view, RECYCLER_VIEW) || isA(view, VIEW_PAGER_2)

    /**
     * Runs [action] on [view] and returns whether it did anything: `performClick()`,
     * which runs the View's click listener, `setText()` with the cursor at the end,
     * `scrollListBy()` of a ListView or GridView or `scrollBy()` of any other View, or a
     * scroll to an item. A scroll of a ViewPager2, or of its RecyclerView, turns its pages.
     *
     * @throws IllegalStateException when the View has no such action.
     */
    fun perform(view: View, action: ScreenAction): Boolean {
        val pager = pagerOf(view)
        if (pager != null && action is ScreenAction.ScrollBy) return turnPages(pager, action)
        if (pager != null && action is ScreenAction.ScrollToIndex) return showPage(pager, action.index)
        return when (action) {
            is ScreenAction.Click -> view.performClick()
            is ScreenAction.SetText -> {
                val field = view as? EditText ?: error("The View is not an EditText")
                field.setText(action.value)
                field.setSelection(field.text?.length ?: 0)
                true
            }
            is ScreenAction.ScrollBy -> {
                // A ListView or GridView scrolls its rows with scrollListBy(); its scrollBy() is
                // View's, which moves the whole View's content and leaves blank space.
                if (view is AbsListView) view.scrollListBy(action.dy.toInt()) else view.scrollBy(action.dx.toInt(), action.dy.toInt())
                true
            }
            is ScreenAction.ScrollToIndex -> scrollToIndex(view, action.index)
        }
    }

    private fun scrollToIndex(view: View, index: Int): Boolean {
        if (view is AbsListView) {
            view.setSelection(index)
            return true
        }
        check(isA(view, RECYCLER_VIEW)) { "The View does not scroll to an index" }
        // RecyclerView is not on the tab's classpath; scrollToPosition is its public API.
        view.javaClass.getMethod("scrollToPosition", Int::class.javaPrimitiveType).invoke(view, index)
        return true
    }

    // A ViewPager2 says that it scrolls when its RecyclerView does, but its scrollBy() is View's,
    // which moves that RecyclerView off its place; and a scroll of the RecyclerView by pixels
    // stops between two pages. Both act on the pager.
    private fun pagerOf(view: View): View? {
        if (isA(view, VIEW_PAGER_2)) return view
        val parent = view.parent as? View ?: return null
        return parent.takeIf { isA(view, RECYCLER_VIEW) && isA(it, VIEW_PAGER_2) }
    }

    /**
     * Turns the pages of [pager] as a slow swipe of the scroll's pixels does: the pager snaps
     * to the nearest page. Returns whether the current page changed.
     */
    private fun turnPages(pager: View, action: ScreenAction.ScrollBy): Boolean {
        val horizontal = Pager.orientation(pager) == PAGER_HORIZONTAL
        val pixels = if (horizontal) action.dx else action.dy
        val size = if (horizontal) pager.width else pager.height
        if (size <= 0) return false
        // Of a horizontal pager in a right-to-left layout, the next page is on the left.
        val sign = if (horizontal && pager.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
        val before = Pager.currentItem(pager)
        Pager.setCurrentItem(pager, before + sign * (pixels / size).roundToInt())
        return Pager.currentItem(pager) != before
    }

    /** Shows the page at [index] of [pager]. As a View list does, it ignores an index past the last page. */
    private fun showPage(pager: View, index: Int): Boolean {
        if (index >= Pager.pageCount(pager)) return false
        Pager.setCurrentItem(pager, index)
        return true
    }

    private fun isA(view: View, className: String): Boolean {
        var type: Class<*>? = view.javaClass
        while (type != null && type != View::class.java) {
            if (type.name == className) return true
            type = type.superclass
        }
        return false
    }

    // ViewPager2 is not on the tab's classpath either; these calls are its public API. It is a
    // final class, so its methods are found on the View's own class.
    private object Pager {
        fun orientation(pager: View): Int = pager.javaClass.getMethod("getOrientation").invoke(pager) as Int

        fun currentItem(pager: View): Int = pager.javaClass.getMethod("getCurrentItem").invoke(pager) as Int

        // The pager keeps the item within its pages, and scrolls to it as a swipe does.
        fun setCurrentItem(pager: View, item: Int) {
            pager.javaClass.getMethod("setCurrentItem", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).invoke(pager, item, true)
        }

        // The adapter's class can be private, so getItemCount comes from RecyclerView.Adapter, which is public.
        fun pageCount(pager: View): Int {
            val adapter = pager.javaClass.getMethod("getAdapter").invoke(pager) ?: return 0
            var type: Class<*>? = adapter.javaClass
            while (type != null && type.name != RECYCLER_VIEW_ADAPTER) type = type.superclass
            return type?.getMethod("getItemCount")?.invoke(adapter) as? Int ?: 0
        }
    }
}
