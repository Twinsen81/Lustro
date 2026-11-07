package io.github.twinsen81.lustro.screen.internal

import android.view.WindowManager

/**
 * Finds the node that a POST route acts on, in a read of the unmerged tree: the nodes
 * that the selector matches, each acting through itself or the nearest node above it
 * that has the action. A test tag inside a clickable parent is in the unmerged tree
 * only, and its click is the parent's, as a user's tap on it would be.
 *
 * It acts as a user can, unless the request says `force`: only in the window that has the
 * focus and in the popups over it, also a popup that a popup on it opened, and only on a
 * target with a visible part. A node under a
 * dialog, or scrolled out of view, gets no tap from a user, so an action on it would pass a
 * check that a user fails. A popup that takes no focus, such as a menu or a dropdown's list,
 * still gets the touches inside it: the window manager gives a touch to the top window
 * under it that takes touches, whether it can take the focus or not.
 */
internal object ActionTargets {
    private const val MAX_CANDIDATES = 10

    // WindowManager.LayoutParams.TYPE_APPLICATION_ABOVE_SUB_PANEL, which the SDK hides.
    private const val TYPE_APPLICATION_ABOVE_SUB_PANEL = 1005

    /** The node that the selector matched, and the node whose action runs. */
    class Target(val window: ScreenWindow, val matched: ScreenNode, val node: ScreenNode)

    /** @throws ActionRefused when no node, or more than one, can take the action. */
    fun resolve(read: ScreenWindows, request: ActionRequest): Target {
        val selector = request.selector
        // The touches go to the top window that has the focus, and to the popups over it. On
        // API 24 the window under a new dialog still says that it has the focus for some frames.
        val focusedIndex = read.windows.indexOfLast { it.hasWindowFocus }
        val focused = read.windows.filterIndexed { index, _ -> index == focusedIndex || isPopupOver(read.windows, index, focusedIndex) }
        if (!request.force && focused.isEmpty()) {
            throw ActionRefused(
                409,
                "no_window_focus",
                "No window of the app has the focus: a window of another app, such as a permission dialog or the chooser, is on top",
                hint = "Read that window with uiautomator, or send \"force\": true to act on the app's windows anyway",
            )
        }
        val windows = if (request.force) read.windows else focused
        val matches = windows.flatMap { window -> window.nodes.filter(selector::matches).map { window to it } }
        if (matches.isEmpty()) refuse(nothingMatches(read, request, focused, read.windows.getOrNull(focusedIndex)))
        val targets =
            matches
                .map { (window, node) -> Triple(window, node, actionNode(window, node, request.action.needs)) }
                .distinctBy { (_, node, target) -> (target ?: node).id }
        if (targets.size > 1) {
            val shown = targets.take(MAX_CANDIDATES).joinToString("; ") { (_, node, target) -> describe(target ?: node) }
            val more = if (targets.size > MAX_CANDIDATES) "; and ${targets.size - MAX_CANDIDATES} more" else ""
            val first = targets.first().let { (_, node, target) -> (target ?: node).id }
            val hint = "Select one by its id, such as {\"id\":\"$first\"}"
            refuse(409, "ambiguous", "${targets.size} nodes match $selector: $shown$more", hint)
        }
        val (window, matched, target) = targets.single()
        if (target == null) {
            refuse(409, "no_action", "${matched.id} matches $selector, but neither it nor a node above it has ${request.action.needs}")
        }
        if (!request.force && target.visibleBounds == null) {
            val hint = "Scroll it into view first. \"force\": true acts on it anyway"
            refuse(409, "not_on_screen", "${describe(target)} has no part on the screen, so a user can't tap it now", hint)
        }
        if (!target.enabled) refuse(409, "disabled", "${describe(target)} is disabled, so a tap on it does nothing")
        return Target(window, matched, target)
    }

    /**
     * Whether the window at [index] is a visible popup that takes touches over the window that
     * has the focus, at [focusedIndex]: a popup on that window, or, when that window is a popup
     * itself, a popup on the same window that is above it. A popup that another popup opened
     * is on the window under both, as Compose and `PopupWindow` give it that window's token.
     * Of two popups on one window, the one with the higher window type is on top, and of two
     * with the same type, the later one.
     */
    private fun isPopupOver(windows: List<ScreenWindow>, index: Int, focusedIndex: Int): Boolean {
        val window = windows[index]
        val focused = windows.getOrNull(focusedIndex) ?: return false
        if (window.popupOn == null || !window.visible || !window.touchable) return false
        if (window.popupOn == focusedIndex) return true
        if (focused.popupOn != window.popupOn) return false
        val layer = subLayer(window.type).compareTo(subLayer(focused.type))
        return layer > 0 || (layer == 0 && index > focusedIndex)
    }

    // The order of the popup types on their window, as the window manager stacks them: a panel
    // and an attached dialog, then a sub-panel, which Compose's popups are, then the one above it.
    private fun subLayer(type: Int?): Int =
        when (type) {
            WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL -> 2
            TYPE_APPLICATION_ABOVE_SUB_PANEL -> 3
            else -> 1
        }

    private fun refuse(status: Int, code: String, message: String, hint: String? = null): Nothing = throw ActionRefused(status, code, message, hint)

    private fun refuse(refusal: ActionRefused): Nothing = throw refusal

    private fun nothingMatches(read: ScreenWindows, request: ActionRequest, focused: List<ScreenWindow>, top: ScreenWindow?): ActionRefused {
        if (!request.force && top != null) {
            for (window in read.windows) {
                if (window in focused) continue
                val node = window.nodes.firstOrNull(request.selector::matches) ?: continue
                return ActionRefused(
                    409,
                    "not_in_focused_window",
                    "${node.id} matches ${request.selector} in the ${window.kind} window, which does not have the focus and is not " +
                        "a popup on the ${top.kind} window that has it, so a user can't tap the node now",
                    hint = "Close the window over it first, such as a dialog. \"force\": true acts on the node anyway",
                )
            }
        }
        val where = if (request.force) "in any window of the app" else "in the window that has the focus or a popup on it"
        return ActionRefused(404, "no_match", "No node matches ${request.selector} $where")
    }

    private fun actionNode(window: ScreenWindow, node: ScreenNode, action: String): ScreenNode? {
        val byId = window.nodes.associateBy { it.id }
        var current: ScreenNode? = node
        while (current != null) {
            if (action in current.actions) return current
            current = current.parent?.let(byId::get)
        }
        return null
    }

    private fun describe(node: ScreenNode): String {
        val what = node.role ?: node.className?.substringAfterLast('.') ?: "node"
        val label = (node.text + node.contentDescription).firstOrNull()?.let { " \"$it\"" }.orEmpty()
        val tag = node.testTag?.let { " #$it" }.orEmpty()
        val box = node.visibleBounds?.let { " [${it.left},${it.top}][${it.right},${it.bottom}]" } ?: " (not on screen)"
        return "${node.id} $what$label$tag$box"
    }
}
