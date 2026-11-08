package io.github.twinsen81.lustro.screen.internal

import android.view.View
import android.view.WindowManager

/**
 * Whether the window focus has come to rest, from what each window says. The window
 * manager moves the focus some frames after a dialog opens or closes, 200 ms and more on
 * a physical device, and on API 24 the window under a new dialog still says that it has
 * the focus for some frames after the dialog has it.
 *
 * Each window belongs to the group of its activity: a dialog has the activity's token,
 * and a popup the token of the window that it is attached to. The focus is at rest when
 * exactly one window has it, and it is the top visible window of its group that can take
 * the focus; the group itself can be any, as with two activities side by side, or one in
 * picture-in-picture. While no window has the focus, it is at rest only when no window can
 * take it: otherwise a window of another app is on top, or the focus is on its way.
 *
 * A window that is [pending][Entry.pending] has the focus still to come: the app has added
 * it, and it is not visible before its first frame. Compose adds a dialog's window on the
 * frame after the tap that opened it, before the window's own first frame.
 */
internal object WindowFocus {
    class Entry(val group: Int, val visible: Boolean, val focusable: Boolean, val hasFocus: Boolean, val pending: Boolean = false)

    fun atRest(entries: List<Entry>): Boolean {
        if (entries.any { it.pending }) return false
        val focused = entries.filter { it.hasFocus }
        if (focused.size > 1) return false
        val window = focused.firstOrNull() ?: return entries.none { it.visible && it.focusable }
        val top = entries.lastOrNull { it.group == window.group && it.visible && it.focusable } ?: return true
        return top === window
    }

    /**
     * The group of each of [roots], in their order: the index of the first window of the same
     * activity. A window whose token says nothing, as in a test, is in the group of the other
     * windows without one.
     */
    fun groups(roots: List<View>): IntArray {
        val parents = parents(roots)
        val groups = IntArray(roots.size)
        for (index in roots.indices) {
            val token = (roots[index].layoutParams as? WindowManager.LayoutParams)?.token
            val parent =
                if (isSubWindow(roots[index])) {
                    parents[index].takeIf { it >= 0 }
                } else {
                    (0 until index).firstOrNull { other ->
                        !isSubWindow(roots[other]) && (roots[other].layoutParams as? WindowManager.LayoutParams)?.token === token
                    }
                }
            groups[index] = if (parent == null) index else groups[parent]
        }
        return groups
    }

    /**
     * For each of [roots], in their order, the index of the window that it is a popup on, or
     * -1 for a window that is not a popup. A popup's token is the window token of the window
     * that it is attached to: for a Compose popup and a `PopupWindow`, the window of the
     * activity or the dialog that shows it, also for a popup in a popup.
     */
    fun parents(roots: List<View>): IntArray =
        IntArray(roots.size) { index ->
            val token = (roots[index].layoutParams as? WindowManager.LayoutParams)?.token
            if (token == null || !isSubWindow(roots[index])) -1 else (0 until index).firstOrNull { roots[it].windowToken === token } ?: -1
        }

    private fun isSubWindow(root: View): Boolean {
        val params = root.layoutParams as? WindowManager.LayoutParams ?: return false
        return params.type in WindowManager.LayoutParams.FIRST_SUB_WINDOW..WindowManager.LayoutParams.LAST_SUB_WINDOW
    }
}
