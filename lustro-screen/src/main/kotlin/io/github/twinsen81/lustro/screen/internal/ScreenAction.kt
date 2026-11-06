package io.github.twinsen81.lustro.screen.internal

/** An action that a POST route runs on a node, and the semantics action that it needs. */
internal sealed class ScreenAction(val route: String, val needs: String) {
    object Click : ScreenAction("click", ScreenNode.ACTION_CLICK)

    class SetText(val value: String) : ScreenAction("set-text", ScreenNode.ACTION_SET_TEXT)

    class ScrollToIndex(val index: Int) : ScreenAction("scroll", ScreenNode.ACTION_SCROLL_TO_INDEX)

    class ScrollBy(val dx: Float, val dy: Float) : ScreenAction("scroll", ScreenNode.ACTION_SCROLL_BY)
}

/** Which nodes a POST route acts on: exactly one of a test tag, a text, a content description, or an id. */
internal class Selector(val key: String, val value: String) {
    fun matches(node: ScreenNode): Boolean =
        when (key) {
            TAG -> node.testTag == value
            TEXT -> value in node.text || node.editableText == value
            CONTENT_DESCRIPTION -> value in node.contentDescription
            else -> node.id == value
        }

    override fun toString(): String = "$key \"$value\""

    companion object {
        const val TAG: String = "tag"
        const val TEXT: String = "text"
        const val CONTENT_DESCRIPTION: String = "contentDescription"
        const val ID: String = "id"
        val KEYS: List<String> = listOf(TAG, TEXT, CONTENT_DESCRIPTION, ID)
    }
}

/**
 * A POST request: the [selector], the [action], whether to [force] it on a node that a user
 * can't tap now, and how to read the tree after it.
 */
internal class ActionRequest(
    val selector: Selector,
    val action: ScreenAction,
    val force: Boolean,
    val merged: Boolean,
    val settleMs: Int,
)

/** What a POST route did: the node that the selector [matched], the [target] whose action ran, and the tree after. */
internal class ActionResult(val action: ScreenAction, val matched: String, val target: String, val handled: Boolean, val tree: ScreenTree)

/** Why a POST route ran no action: the HTTP status and the error envelope's fields. */
internal class ActionRefused(
    val status: Int,
    val code: String,
    message: String,
    val hint: String? = null,
) : Exception(message)
