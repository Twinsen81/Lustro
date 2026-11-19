package io.github.twinsen81.lustro.prefs.internal

import io.github.twinsen81.lustro.SensitiveKeys
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Replaces the values of the sensitive fields in a string value that is a JSON
 * object or array, at any depth, as the Network tab's redaction does in a body:
 * the same [SensitiveKeys] check, and the same placeholder.
 */
internal object JsonRedaction {
    const val PLACEHOLDER: String = "[REDACTED]"

    /**
     * [text] with the values of its sensitive fields replaced, or `null` when it is
     * not one JSON object or array, or has no sensitive field with a value to hide.
     * The result is built again from the parse, so it is compact.
     */
    fun redact(text: String): String? {
        val start = text.indexOfFirst { !it.isWhitespace() }
        if (start < 0 || (text[start] != '{' && text[start] != '[')) return null
        return try {
            val tokener = JSONTokener(text)
            val value = tokener.nextValue()
            // nextClean() returns the NUL char at the end of the input: anything else is a second value.
            if (tokener.nextClean() != Char.MIN_VALUE) return null
            val changed =
                when (value) {
                    is JSONObject -> redactObject(value)
                    is JSONArray -> redactArray(value)
                    else -> return null
                }
            if (changed) value.toString() else null
        } catch (_: JSONException) {
            null
        } catch (_: StackOverflowError) {
            // A value nested deep enough to overflow the parser or the walk is shown as it is: it is no secret's usual shape.
            null
        }
    }

    // Returns whether it replaced a value.
    private fun redactObject(obj: JSONObject): Boolean {
        var changed = false
        for (key in obj.keys().asSequence().toList()) {
            val value = obj.get(key)
            changed =
                if (SensitiveKeys.isSensitive(key)) {
                    hide(value) { obj.put(key, PLACEHOLDER) }
                } else {
                    walk(value)
                } || changed
        }
        return changed
    }

    private fun redactArray(array: JSONArray): Boolean {
        var changed = false
        for (index in 0 until array.length()) changed = walk(array.get(index)) || changed
        return changed
    }

    private fun walk(value: Any?): Boolean =
        when (value) {
            is JSONObject -> redactObject(value)
            is JSONArray -> redactArray(value)
            else -> false
        }

    // A boolean or a null holds no secret, as in the tab's own check, and stays,
    // so that a flag such as "token_valid" keeps its meaning.
    private inline fun hide(value: Any?, replace: () -> Unit): Boolean {
        if (value is Boolean || value == JSONObject.NULL) return false
        replace()
        return true
    }
}
