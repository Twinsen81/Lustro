package io.github.twinsen81.lustro.screen.internal

import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Reads the JSON body of a POST route: one selector key, the route's own keys, and the
 * optional `force`, `merged`, and `settle`. A key that the route does not take is a
 * 400 with that key as the field, so that a misspelled key never acts on another node.
 * `force` acts also on a node that a user can't tap now.
 */
internal object ActionBodies {
    /** The settle time of an action when the request names none: the tree is read after the action's frames. */
    const val DEFAULT_SETTLE_MS: Int = 500

    private val COMMON = setOf("force", "merged", "settle")
    private val OWN = mapOf("click" to emptySet(), "set-text" to setOf("value"), "scroll" to setOf("index", "dx", "dy"))

    fun parse(route: String, text: String?): ActionRequest {
        val body = objectOf(text)
        val allowed = Selector.KEYS.toSet() + COMMON + OWN.getValue(route)
        body.keys().forEach { key -> if (key !in allowed) bad("$route takes no key $key", key) }
        val selectors = Selector.KEYS.filter { body.has(it) }
        if (selectors.size != 1) bad("Give exactly one of ${Selector.KEYS.joinToString(", ")}", selectors.getOrNull(1) ?: "tag")
        val key = selectors.single()
        val value = string(body, key)
        if (value.isEmpty()) bad("$key is empty", key)
        val action =
            when (route) {
                "click" -> ScreenAction.Click
                "set-text" -> ScreenAction.SetText(if (body.has("value")) string(body, "value") else bad("set-text needs value, the new text", "value"))
                else -> scroll(body)
            }
        val settle = if (body.has("settle")) int(body, "settle") else DEFAULT_SETTLE_MS
        if (settle !in 1..ScreenSource.MAX_SETTLE_MS) bad("settle must be from 1 to ${ScreenSource.MAX_SETTLE_MS} milliseconds, was $settle", "settle")
        return ActionRequest(
            Selector(key, value),
            action,
            force = if (body.has("force")) boolean(body, "force") else false,
            merged = if (body.has("merged")) boolean(body, "merged") else true,
            settleMs = settle,
        )
    }

    private fun scroll(body: JSONObject): ScreenAction {
        val byIndex = body.has("index")
        val byOffset = body.has("dx") || body.has("dy")
        if (byIndex == byOffset) bad("scroll takes index, or dx and dy, but not both", if (byIndex) "dx" else "index")
        if (byIndex) {
            val index = int(body, "index")
            if (index < 0) bad("index must not be negative, was $index", "index")
            return ScreenAction.ScrollToIndex(index)
        }
        return ScreenAction.ScrollBy(number(body, "dx"), number(body, "dy"))
    }

    private fun objectOf(text: String?): JSONObject {
        if (text.isNullOrBlank()) bad("The body must be a JSON object, such as {\"tag\":\"save\"}", null)
        return try {
            JSONTokener(text).nextValue() as? JSONObject ?: bad("The body must be a JSON object, such as {\"tag\":\"save\"}", null)
        } catch (e: JSONException) {
            bad("The body is not JSON: ${e.message}", null)
        }
    }

    private fun string(body: JSONObject, key: String): String = body.opt(key) as? String ?: bad("$key must be a string", key)

    private fun boolean(body: JSONObject, key: String): Boolean = body.opt(key) as? Boolean ?: bad("$key must be true or false", key)

    private fun int(body: JSONObject, key: String): Int {
        val value = body.opt(key)
        if (value !is Int) bad("$key must be a whole number", key)
        return value
    }

    // dx and dy default to 0, so that a vertical scroll needs only dy.
    private fun number(body: JSONObject, key: String): Float {
        if (!body.has(key)) return 0f
        val value = (body.opt(key) as? Number)?.toFloat()
        if (value == null || !value.isFinite()) bad("$key must be a number of pixels", key)
        return value
    }

    private fun bad(message: String, field: String?): Nothing = throw ScreenRoutes.RouteException(message, field = field)
}
