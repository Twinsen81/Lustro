package io.github.twinsen81.lustro.internal

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The query parameters that a tab's OpenAPI document declares for each of its routes. The
 * server compares a request's query parameters with them, and names each one that the
 * route does not declare in a `Lustro-Warning` header: a route ignores a parameter that it
 * does not know, so a misspelled filter returns everything, with no sign of the mistake.
 *
 * Only the paths under `/api/v1/<tabId>/` count. A path segment in braces, such as `{id}`,
 * matches any one segment, and a literal segment wins over a template.
 */
internal class DeclaredQueryParameters private constructor(private val operations: List<Operation>) {

    private class Operation(val method: String, val segments: List<String?>, val names: Set<String>) {
        val literals = segments.count { it != null }
    }

    /**
     * The names of the request's query parameters that the route does not declare, in the
     * order given. Empty when the document has no operation for [method] and [subPath]:
     * then the document says nothing about the route.
     */
    fun undeclared(method: String, subPath: String, given: Collection<String>): List<String> {
        val requested = method.lowercase()
        val segments = subPath.split('/')
        val operation =
            operations
                .filter { it.method == requested && matches(it.segments, segments) }
                .maxByOrNull { it.literals }
                ?: return emptyList()
        return given.filter { it !in operation.names }
    }

    private fun matches(template: List<String?>, segments: List<String>): Boolean =
        template.size == segments.size &&
            template.indices.all { i -> template[i]?.equals(segments[i]) ?: segments[i].isNotEmpty() }

    companion object {
        private val METHODS = setOf("get", "put", "post", "delete", "options", "head", "patch", "trace")
        private val TEMPLATE = Regex("\\{[^/{}]+\\}")

        /**
         * The warning for an undeclared [name]. The name is a quoted string: a backslash
         * escapes `"` and `\`, and `\uXXXX` stands for a character outside printable ASCII,
         * so a name can't end the header or split the list of warnings.
         */
        fun warning(name: String): String =
            buildString {
                append("unknown query parameter \"")
                for (c in name) {
                    when {
                        c == '"' || c == '\\' -> append('\\').append(c)
                        c in ' '..'~' -> append(c)
                        else -> append("\\u%04x".format(c.code))
                    }
                }
                append('"')
            }

        /** Reads [document], or returns `null` when it is not an OpenAPI document with paths. */
        fun parse(tabId: String, document: String): DeclaredQueryParameters? {
            val root =
                try {
                    JSONObject(document)
                } catch (_: JSONException) {
                    return null
                }
            val paths = root.optJSONObject("paths") ?: return null
            val prefix = "/api/v1/$tabId/"
            val operations = ArrayList<Operation>()
            for (path in paths.keys()) {
                if (!path.startsWith(prefix)) continue
                val item = paths.optJSONObject(path) ?: continue
                val segments = path.removePrefix(prefix).split('/').map { if (TEMPLATE.matches(it)) null else it }
                val shared = queryNames(root, item.optJSONArray("parameters"))
                for (method in item.keys()) {
                    if (method !in METHODS) continue
                    val operation = item.optJSONObject(method) ?: continue
                    operations += Operation(method, segments, shared + queryNames(root, operation.optJSONArray("parameters")))
                }
            }
            return DeclaredQueryParameters(operations)
        }

        private fun queryNames(root: JSONObject, parameters: JSONArray?): Set<String> {
            if (parameters == null) return emptySet()
            val names = HashSet<String>()
            for (i in 0 until parameters.length()) {
                val parameter = resolve(root, parameters.optJSONObject(i)) ?: continue
                if (parameter.optString("in") == "query") names += parameter.optString("name")
            }
            return names
        }

        /** Follows a local `$ref`, such as `#/components/parameters/Cursor`, to its object. */
        private fun resolve(root: JSONObject, parameter: JSONObject?): JSONObject? {
            val ref = parameter?.optString("\$ref").orEmpty()
            if (!ref.startsWith("#/")) return parameter
            var target: JSONObject? = root
            for (key in ref.removePrefix("#/").split('/')) {
                target = target?.optJSONObject(key.replace("~1", "/").replace("~0", "~"))
            }
            return target
        }
    }
}
