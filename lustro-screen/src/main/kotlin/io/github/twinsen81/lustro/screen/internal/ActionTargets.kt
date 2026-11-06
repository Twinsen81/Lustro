package io.github.twinsen81.lustro.screen.internal

/**
 * Finds the node that a POST route acts on, in a read of the unmerged tree: the nodes
 * that the selector matches, each acting through itself or the nearest node above it
 * that has the action. A test tag inside a clickable parent is in the unmerged tree
 * only, and its click is the parent's, as a user's tap on it would be.
 *
 * It acts as a user can, unless the request says `force`: only in the windows that have
 * the focus, and only on a target with a visible part. A node under a dialog, or
 * scrolled out of view, gets no tap from a user, so an action on it would pass a check
 * that a user fails.
 */
internal object ActionTargets {
    private const val MAX_CANDIDATES = 10

    /** The node that the selector matched, and the node whose action runs. */
    class Target(val window: ScreenWindow, val matched: ScreenNode, val node: ScreenNode)

    /** @throws ActionRefused when no node, or more than one, can take the action. */
    fun resolve(read: ScreenWindows, request: ActionRequest): Target {
        val selector = request.selector
        // Only one window gets the touches: the top one that has the focus. On API 24 the
        // window under a new dialog still says that it has it for some frames.
        val focused = listOfNotNull(read.windows.lastOrNull { it.hasWindowFocus })
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
        if (matches.isEmpty()) refuse(nothingMatches(read, request, focused))
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

    private fun refuse(status: Int, code: String, message: String, hint: String? = null): Nothing = throw ActionRefused(status, code, message, hint)

    private fun refuse(refusal: ActionRefused): Nothing = throw refusal

    private fun nothingMatches(read: ScreenWindows, request: ActionRequest, focused: List<ScreenWindow>): ActionRefused {
        if (!request.force) {
            for (window in read.windows) {
                if (window in focused) continue
                val node = window.nodes.firstOrNull(request.selector::matches) ?: continue
                return ActionRefused(
                    409,
                    "not_in_focused_window",
                    "${node.id} matches ${request.selector} in the ${window.kind} window, which does not have the focus: " +
                        "a window over it, such as a dialog, gets the touches",
                    hint = "Close the window on top first: a user can't tap the node now. \"force\": true acts on it anyway",
                )
            }
        }
        val where = if (request.force) "in any window of the app" else "in the window that has the focus"
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
