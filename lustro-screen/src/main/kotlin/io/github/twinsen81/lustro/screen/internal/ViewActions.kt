package io.github.twinsen81.lustro.screen.internal

import android.view.View
import android.widget.AbsListView
import android.widget.EditText

/** The actions of a classic View: what the View's own API does when a user acts on it. */
internal object ViewActions {
    private const val RECYCLER_VIEW = "androidx.recyclerview.widget.RecyclerView"

    /** Whether [view] scrolls to an item by its index: a ListView, a GridView, or a RecyclerView. */
    fun scrollsToIndex(view: View): Boolean = view is AbsListView || isRecyclerView(view)

    /**
     * Runs [action] on [view] and returns whether it did anything: `performClick()`,
     * which runs the View's click listener, `setText()` with the cursor at the end,
     * `scrollBy()`, or a scroll to an item.
     *
     * @throws IllegalStateException when the View has no such action.
     */
    fun perform(view: View, action: ScreenAction): Boolean =
        when (action) {
            is ScreenAction.Click -> view.performClick()
            is ScreenAction.SetText -> {
                val field = view as? EditText ?: error("The View is not an EditText")
                field.setText(action.value)
                field.setSelection(field.text?.length ?: 0)
                true
            }
            is ScreenAction.ScrollBy -> {
                view.scrollBy(action.dx.toInt(), action.dy.toInt())
                true
            }
            is ScreenAction.ScrollToIndex -> scrollToIndex(view, action.index)
        }

    private fun scrollToIndex(view: View, index: Int): Boolean {
        if (view is AbsListView) {
            view.setSelection(index)
            return true
        }
        check(isRecyclerView(view)) { "The View does not scroll to an index" }
        // RecyclerView is not on the tab's classpath; scrollToPosition is its public API.
        view.javaClass.getMethod("scrollToPosition", Int::class.javaPrimitiveType).invoke(view, index)
        return true
    }

    private fun isRecyclerView(view: View): Boolean {
        var type: Class<*>? = view.javaClass
        while (type != null && type != View::class.java) {
            if (type.name == RECYCLER_VIEW) return true
            type = type.superclass
        }
        return false
    }
}
