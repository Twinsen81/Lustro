package io.github.twinsen81.lustro.screen.internal

import android.annotation.SuppressLint
import android.view.View
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import kotlin.math.roundToInt

/**
 * Reads the semantics tree of a Compose host, the merged one or the unmerged one.
 *
 * This class names Compose types, also in the properties that its constructor sets, so
 * the tab creates it only after [ComposeSupport.isAvailable] found Compose UI in the
 * app. It has no companion object and no static state: in an app without Compose,
 * nothing initializes it.
 *
 * A Compose host is a View that implements `ViewRootForTest`; its `semanticsOwner`
 * gives the tree, and every call here is public API of Compose UI 1.6.0 and newer.
 * `ViewRootForTest` carries `@VisibleForTesting`: the tab reads the tree in a debug
 * build, as tests do. `SemanticsNode.children` and `config` build a new list and a
 * new configuration on each access, so each is read once for each node.
 */
@SuppressLint("VisibleForTests")
internal class ComposeReader {
    /**
     * The actions that drive a UI, by their semantics names. Each text node also has
     * the accessibility actions of text substitution and layout, and each focusable
     * one RequestFocus, which say nothing about what the node does.
     */
    private val drivingActions: List<SemanticsPropertyKey<*>> =
        listOf(
            SemanticsActions.OnClick,
            SemanticsActions.OnLongClick,
            SemanticsActions.SetText,
            SemanticsActions.OnImeAction,
            SemanticsActions.ScrollBy,
            SemanticsActions.ScrollToIndex,
            SemanticsActions.PageUp,
            SemanticsActions.PageDown,
            SemanticsActions.PageLeft,
            SemanticsActions.PageRight,
            SemanticsActions.SetProgress,
            SemanticsActions.Expand,
            SemanticsActions.Collapse,
            SemanticsActions.Dismiss,
        )

    fun isHost(view: View): Boolean = view is ViewRootForTest

    /**
     * Adds the nodes of [view]'s tree to [out], under [host], the host's View node, and,
     * with [live], each node's `SemanticsNode` under its id.
     */
    fun read(view: View, host: ScreenNode, merged: Boolean, out: MutableList<ScreenNode>, live: MutableMap<String, Any>? = null) {
        val owner = (view as ViewRootForTest).semanticsOwner
        val root = if (merged) owner.rootSemanticsNode else owner.unmergedRootSemanticsNode
        val inWindow = IntArray(2).also(view::getLocationInWindow)
        val onScreen = IntArray(2).also(view::getLocationOnScreen)
        val place = Place("c${host.id.removePrefix("v")}.", onScreen[0] - inWindow[0], onScreen[1] - inWindow[1], host.visibleBounds)
        visit(root, host.id, place, out, live)
    }

    /**
     * Runs [action] on [node], a `SemanticsNode` that a read with live nodes gave, and
     * returns what the action's lambda returned: whether it did anything. Calls [gaveFocus]
     * when set-text gave the field the focus first, which asks for the keyboard. Throws
     * [IllegalStateException] when the node has no such action now.
     */
    fun perform(node: Any, action: ScreenAction, gaveFocus: () -> Unit = {}): Boolean {
        val config = (node as SemanticsNode).config
        fun missing(name: String): Nothing = error("The node has no $name now")
        return when (action) {
            is ScreenAction.Click -> config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() ?: missing("OnClick")
            is ScreenAction.SetText -> {
                val setText = config.getOrNull(SemanticsActions.SetText)?.action ?: missing("SetText")
                // As a user's tap does, and Compose's own test API: a field that gets text without
                // the focus has no input session, and a focus request on the next frame, such as an
                // editable dropdown's, starts one with the old text's layout and throws there.
                if (config.getOrNull(SemanticsProperties.Focused) != true) {
                    val requestFocus = config.getOrNull(SemanticsActions.RequestFocus)?.action
                    if (requestFocus != null && requestFocus()) gaveFocus()
                }
                setText(AnnotatedString(action.value))
            }
            is ScreenAction.ScrollToIndex -> config.getOrNull(SemanticsActions.ScrollToIndex)?.action?.invoke(action.index) ?: missing("ScrollToIndex")
            is ScreenAction.ScrollBy -> config.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(action.dx, action.dy) ?: missing("ScrollBy")
        }
    }

    private class Place(val prefix: String, val dx: Int, val dy: Int, val hostVisible: ScreenRect?)

    private fun visit(node: SemanticsNode, parent: String, place: Place, out: MutableList<ScreenNode>, live: MutableMap<String, Any>?) {
        val id = place.prefix + node.id
        out += node(node, id, parent, place)
        live?.put(id, node)
        for (child in node.children) visit(child, id, place, out, live)
    }

    private fun node(node: SemanticsNode, id: String, parent: String, place: Place): ScreenNode {
        val config = node.config
        val position = node.positionInWindow
        val size = node.size
        val left = position.x.roundToInt()
        val top = position.y.roundToInt()
        val bounds = ScreenRect(left, top, left + size.width, top + size.height)
        val window = node.boundsInWindow
        val visible =
            ScreenRect(window.left.roundToInt(), window.top.roundToInt(), window.right.roundToInt(), window.bottom.roundToInt())
                .offset(place.dx, place.dy)
                .let { rect -> place.hostVisible?.let { rect.intersect(it) } }
        val password = config.getOrNull(SemanticsProperties.Password) != null
        val text = config.getOrNull(SemanticsProperties.Text)?.map { if (password) TreeReader.mask(it.text) else it.text }.orEmpty()
        val editable = config.getOrNull(SemanticsProperties.EditableText)?.text?.let { if (password) TreeReader.mask(it) else it }
        val actions = drivingActions.filter { config.contains(it) }.map { it.name }
        val collection = config.getOrNull(SemanticsProperties.CollectionInfo)
        return ScreenNode(
            id = id,
            parent = parent,
            kind = NodeKind.COMPOSE,
            testTag = config.getOrNull(SemanticsProperties.TestTag),
            text = text,
            contentDescription = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty(),
            editableText = editable,
            password = password,
            role = config.getOrNull(SemanticsProperties.Role)?.toString(),
            stateDescription = config.getOrNull(SemanticsProperties.StateDescription),
            error = config.getOrNull(SemanticsProperties.Error),
            bounds = bounds,
            screenBounds = bounds.offset(place.dx, place.dy),
            visibleBounds = visible,
            enabled = config.getOrNull(SemanticsProperties.Disabled) == null,
            focused = config.getOrNull(SemanticsProperties.Focused) == true,
            selected = config.getOrNull(SemanticsProperties.Selected),
            // On, Off, or Indeterminate, as a name, so that a state that a later Compose adds comes as its name too.
            toggle = config.getOrNull(SemanticsProperties.ToggleableState)?.name?.lowercase(),
            actions = actions,
            horizontalScroll = config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.let(::scroll),
            verticalScroll = config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let(::scroll),
            collectionRows = collection?.rowCount?.takeIf { it >= 0 },
            collectionColumns = collection?.columnCount?.takeIf { it >= 0 },
        )
    }

    private fun scroll(range: ScrollAxisRange): ScrollRange {
        val value = range.value().takeIf { it.isFinite() } ?: 0f
        val max = range.maxValue().takeIf { it.isFinite() }
        return ScrollRange(value, max, range.reverseScrolling, forward = max == null || value < max, backward = value > 0f)
    }
}
