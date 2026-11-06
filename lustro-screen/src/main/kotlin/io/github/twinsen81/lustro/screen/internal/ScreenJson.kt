package io.github.twinsen81.lustro.screen.internal

import io.github.twinsen81.lustro.escapeForJson

/**
 * Writes the tree response. A node leaves out what it doesn't have: a text, a role,
 * or a state that is not set, `enabled` when it is enabled, and `clickable`,
 * `focused`, `password`, and `clipped` when they are false.
 */
internal object ScreenJson {
    fun appendTree(out: StringBuilder, tree: ScreenTree) {
        out.append("{\"windows\":[")
        tree.read.windows.forEachIndexed { index, window ->
            if (index > 0) out.append(',')
            appendWindow(out, window)
        }
        out.append("],\"merged\":").append(tree.merged)
        out.append(",\"density\":").append(number(tree.density))
        out.append(",\"compose\":").append(tree.compose)
        out.append(",\"windowSource\":").appendString(tree.read.source)
        tree.read.composeError?.let { out.append(",\"composeError\":").appendString(it) }
        tree.settle?.let {
            out.append(",\"settle\":{\"requestedMs\":").append(it.requestedMs)
            out.append(",\"settled\":").append(it.settled)
            out.append(",\"waitedMs\":").append(it.waitedMs)
            out.append(",\"frames\":").append(it.frames).append('}')
        }
        out.append(",\"context\":").append(tree.context ?: "null")
        tree.contextError?.let { out.append(",\"contextError\":").appendString(it) }
        out.append('}')
    }

    private fun appendWindow(out: StringBuilder, window: ScreenWindow) {
        out.append("{\"id\":").appendString(window.id)
        out.append(",\"type\":").append(window.type?.toString() ?: "null")
        out.append(",\"kind\":").appendString(window.kind)
        out.append(",\"title\":")
        if (window.title == null) out.append("null") else out.appendString(window.title)
        out.append(",\"hasWindowFocus\":").append(window.hasWindowFocus)
        out.append(",\"visible\":").append(window.visible)
        out.append(",\"bounds\":").appendRect(window.bounds)
        out.append(",\"nodes\":[")
        window.nodes.forEachIndexed { index, node ->
            if (index > 0) out.append(',')
            appendNode(out, node)
        }
        out.append("]}")
    }

    fun appendNode(out: StringBuilder, node: ScreenNode) {
        out.append("{\"id\":").appendString(node.id)
        node.parent?.let { out.append(",\"parent\":").appendString(it) }
        out.append(",\"kind\":").appendString(node.kind.wire)
        node.className?.let { out.append(",\"className\":").appendString(it) }
        node.resourceId?.let { out.append(",\"resourceId\":").appendString(it) }
        node.testTag?.let { out.append(",\"testTag\":").appendString(it) }
        if (node.text.isNotEmpty()) out.append(",\"text\":").appendStrings(node.text)
        if (node.contentDescription.isNotEmpty()) out.append(",\"contentDescription\":").appendStrings(node.contentDescription)
        node.editableText?.let { out.append(",\"editableText\":").appendString(it) }
        if (node.password) out.append(",\"password\":true")
        node.role?.let { out.append(",\"role\":").appendString(it) }
        node.stateDescription?.let { out.append(",\"stateDescription\":").appendString(it) }
        node.error?.let { out.append(",\"error\":").appendString(it) }
        out.append(",\"bounds\":").appendRect(node.bounds)
        out.append(",\"screenBounds\":").appendRect(node.screenBounds)
        out.append(",\"visibleBounds\":")
        if (node.visibleBounds == null) out.append("null") else out.appendRect(node.visibleBounds)
        if (node.clipped) out.append(",\"clipped\":true")
        if (!node.enabled) out.append(",\"enabled\":false")
        if (node.focused) out.append(",\"focused\":true")
        node.selected?.let { out.append(",\"selected\":").append(it) }
        node.toggle?.let { out.append(",\"toggle\":").appendString(it) }
        if (node.clickable) out.append(",\"clickable\":true")
        if (node.actions.isNotEmpty()) out.append(",\"actions\":").appendStrings(node.actions)
        node.horizontalScroll?.let { out.append(",\"horizontalScroll\":").appendScroll(it) }
        node.verticalScroll?.let { out.append(",\"verticalScroll\":").appendScroll(it) }
        if (node.collectionRows != null || node.collectionColumns != null) {
            out.append(",\"collection\":{\"rows\":").append(node.collectionRows?.toString() ?: "null")
            out.append(",\"columns\":").append(node.collectionColumns?.toString() ?: "null").append('}')
        }
        out.append('}')
    }

    private fun StringBuilder.appendString(value: String): StringBuilder = append('"').append(value.escapeForJson()).append('"')

    private fun StringBuilder.appendStrings(values: List<String>): StringBuilder {
        append('[')
        values.forEachIndexed { index, value ->
            if (index > 0) append(',')
            appendString(value)
        }
        return append(']')
    }

    private fun StringBuilder.appendRect(rect: ScreenRect): StringBuilder =
        append("{\"left\":").append(rect.left).append(",\"top\":").append(rect.top)
            .append(",\"right\":").append(rect.right).append(",\"bottom\":").append(rect.bottom).append('}')

    private fun StringBuilder.appendScroll(range: ScrollRange): StringBuilder {
        append("{\"value\":").append(number(range.value))
        append(",\"max\":").append(range.max?.let(::number) ?: "null")
        append(",\"reverse\":").append(range.reverse)
        append(",\"forward\":").append(range.forward)
        return append(",\"backward\":").append(range.backward).append('}')
    }

    /** A whole number without a fraction, as `2` for 2.0, and any other number as Kotlin writes it. */
    private fun number(value: Float): String =
        when {
            !value.isFinite() -> "null"
            value == value.toLong().toFloat() -> value.toLong().toString()
            else -> value.toString()
        }
}
