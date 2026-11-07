package io.github.twinsen81.lustro.screen.internal

/** A rectangle in pixels: [left] and [top] inside it, [right] and [bottom] just outside. */
internal data class ScreenRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val isEmpty: Boolean get() = right <= left || bottom <= top

    fun offset(dx: Int, dy: Int): ScreenRect = ScreenRect(left + dx, top + dy, right + dx, bottom + dy)

    /** The part of this rectangle that is also in [other], or `null` when they don't overlap. */
    fun intersect(other: ScreenRect): ScreenRect? =
        ScreenRect(maxOf(left, other.left), maxOf(top, other.top), minOf(right, other.right), minOf(bottom, other.bottom)).takeUnless { it.isEmpty }
}

/**
 * The scroll position of a node on one axis. [max] is `null` for a classic View,
 * which does not say how far it scrolls. [forward] and [backward] say whether it can
 * scroll further in each direction.
 */
internal data class ScrollRange(val value: Float, val max: Float?, val reverse: Boolean, val forward: Boolean, val backward: Boolean)

/** Whether a node is a classic View or a Compose semantics node. */
internal enum class NodeKind(val wire: String) {
    VIEW("view"),
    COMPOSE("compose"),
}

/**
 * One node of a window's tree, as the read found it. Every property is read once, on
 * the main thread, so two reads compare by value: `settle` waits for two equal ones.
 */
internal data class ScreenNode(
    val id: String,
    val parent: String?,
    val kind: NodeKind,
    val className: String? = null,
    val resourceId: String? = null,
    val testTag: String? = null,
    val text: List<String> = emptyList(),
    val contentDescription: List<String> = emptyList(),
    val editableText: String? = null,
    val password: Boolean = false,
    val role: String? = null,
    val stateDescription: String? = null,
    val error: String? = null,
    val bounds: ScreenRect,
    val screenBounds: ScreenRect,
    val visibleBounds: ScreenRect?,
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val selected: Boolean? = null,
    val toggle: String? = null,
    val actions: List<String> = emptyList(),
    val horizontalScroll: ScrollRange? = null,
    val verticalScroll: ScrollRange? = null,
    val collectionRows: Int? = null,
    val collectionColumns: Int? = null,
) {
    /** Whether a parent or the window cuts the node, so that only a part of it is on the screen. */
    val clipped: Boolean get() = visibleBounds != null && visibleBounds != screenBounds

    val clickable: Boolean get() = ACTION_CLICK in actions

    companion object {
        const val ACTION_CLICK: String = "OnClick"
        const val ACTION_LONG_CLICK: String = "OnLongClick"
        const val ACTION_SET_TEXT: String = "SetText"
        const val ACTION_SCROLL_BY: String = "ScrollBy"
        const val ACTION_SCROLL_TO_INDEX: String = "ScrollToIndex"
    }
}

/**
 * One window of the app's process: an activity, or a dialog, popup, or bottom sheet
 * over it. [id] is the id of its root View node. [type] is the window type of its
 * `WindowManager.LayoutParams`, and [kind] the name of its group.
 */
internal data class ScreenWindow(
    val id: String,
    val type: Int?,
    val kind: String,
    val title: String?,
    val hasWindowFocus: Boolean,
    val focusable: Boolean,
    val visible: Boolean,
    val bounds: ScreenRect,
    val nodes: List<ScreenNode>,
    /** The index of the first window of the same activity: see [WindowFocus.groups]. */
    val group: Int = 0,
    /** Whether the app has added the window and it has not had its first frame yet: see [TreeReader.isPendingWindow]. */
    val pending: Boolean = false,
    /** The index of the window that this one is a popup on, or `null`: see [WindowFocus.parents]. */
    val popupOn: Int? = null,
    /** Whether touches reach the window: it has no FLAG_NOT_TOUCHABLE. */
    val touchable: Boolean = true,
)

/** What a read of all the windows found, before the app's context is added. */
internal data class ScreenWindows(
    val windows: List<ScreenWindow>,
    val source: String,
    val composeError: String?,
) {
    /** Whether the window focus has come to rest: see [WindowFocus.atRest]. */
    val focusSettled: Boolean
        get() = WindowFocus.atRest(windows.map { WindowFocus.Entry(it.group, it.visible, it.focusable, it.hasWindowFocus, it.pending) })
}

/** How a `settle` wait ended: whether two reads in a row were equal, after how long and how many frames. */
internal class SettleResult(val requestedMs: Int, val settled: Boolean, val waitedMs: Long, val frames: Int)

/** The response of `GET tree`. [context] is the app's JSON, checked, or `null`. */
internal class ScreenTree(
    val read: ScreenWindows,
    val merged: Boolean,
    val density: Float,
    val compose: Boolean,
    val settle: SettleResult?,
    val context: String?,
    val contextError: String?,
)
