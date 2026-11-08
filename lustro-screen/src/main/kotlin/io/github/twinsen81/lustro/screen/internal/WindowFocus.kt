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
        val groups = IntArray(roots.size)
        for (index in roots.indices) {
            val params = roots[index].layoutParams as? WindowManager.LayoutParams
            val token = params?.token
            val sub = params != null && params.type in WindowManager.LayoutParams.FIRST_SUB_WINDOW..WindowManager.LayoutParams.LAST_SUB_WINDOW
            val parent =
                (0 until index).firstOrNull { other ->
                    if (sub) {
                        // A popup's token is the window token of the window that it is attached to.
                        token != null && roots[other].windowToken === token
                    } else {
                        val otherParams = roots[other].layoutParams as? WindowManager.LayoutParams
                        val otherSub = otherParams != null &&
                            otherParams.type in WindowManager.LayoutParams.FIRST_SUB_WINDOW..WindowManager.LayoutParams.LAST_SUB_WINDOW
                        !otherSub && otherParams?.token === token
                    }
                }
            groups[index] = if (parent == null) index else groups[parent]
        }
        return groups
    }
}
