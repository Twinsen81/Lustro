@file:OptIn(ExperimentalDebugRoutes::class)

package io.github.twinsen81.lustro

/**
 * The routes that a tab declared, ready to answer requests: [handle] routes a request and
 * checks its parameters, and [document] is the tab's OpenAPI document.
 */
internal class RouteTable private constructor(
    private val tabId: String,
    private val routes: List<Route>,
    val document: String,
) {
    /**
     * Answers [request], or returns `null` when no declared path matches it, so that the
     * server answers `404`. A path that matches with another method gets `405` with an
     * `Allow` header, in the server's format. As in the server, the path that matches with
     * the most literal segments wins, whatever its methods.
     */
    fun handle(request: DebugRequest): DebugResponse? {
        val segments = request.path.split('/')
        val matching = routes.filter { it.matches(segments) }
        val literals = matching.maxOfOrNull { it.literals } ?: return null
        val candidates = matching.filter { it.literals == literals }
        val method = request.method.uppercase()
        val route = candidates.firstOrNull { it.method == method } ?: return methodNotAllowed(request, method, candidates)
        val values =
            try {
                route.read(request, segments)
            } catch (e: BadInput) {
                return DebugResponse.error(e.message.orEmpty(), field = e.field)
            }
        return try {
            route.handler.handle(DebugCall(request, values))
        } catch (e: DebugResponseException) {
            e.response
        }
    }

    private fun methodNotAllowed(request: DebugRequest, method: String, candidates: List<Route>): DebugResponse {
        val declared = candidates.mapTo(HashSet()) { it.method }
        val allowed = METHOD_ORDER.filter { it in declared }
        val error = DebugResponse.error("$tabId/${request.path} takes ${allowed.joinToString(" or ")}, not $method", status = 405)
        return RealDebugResponse(error.status, Headers.of("Allow" to allowed.joinToString(", ")), error.contentType, error.body)
    }

    private class Route(
        val method: String,
        val segments: List<Segment>,
        val query: List<Param>,
        val body: List<Param>,
        val rawBody: Boolean,
        val acceptsForm: Boolean,
        val handler: DebugHandler,
    ) {
        val literals = segments.count { it.literal != null }

        fun matches(given: List<String>): Boolean =
            given.size == segments.size &&
                segments.indices.all { i -> segments[i].literal?.equals(given[i]) ?: given[i].isNotEmpty() }

        fun read(request: DebugRequest, given: List<String>): Map<ParamDecl, Any?> {
            val values = HashMap<ParamDecl, Any?>()
            segments.forEachIndexed { i, segment -> segment.param?.let { values[it.decl] = it.fromText(given[i]) } }
            for (param in query) values[param.decl] = param.fromText(request.queryParam(param.name))
            if (body.isNotEmpty()) readFields(request, values)
            if (rawBody && (request.body == null || request.body.isEmpty())) badInput("The body is required", BODY)
            return values
        }

        private fun readFields(request: DebugRequest, values: MutableMap<ParamDecl, Any?>) {
            val form = acceptsForm && request.contentType.let { it?.type == "application" && it.subtype == "x-www-form-urlencoded" }
            val text = request.bodyAsString()
            if (text.isNullOrBlank()) {
                if (body.any { it.required }) {
                    badInput(if (acceptsForm) "The body must be a JSON object or a form" else "The body must be a JSON object", BODY)
                }
                for (param in body) values[param.decl] = param.fromText(null)
                return
            }
            if (form) {
                val fields = request.formParams()
                for (param in body) values[param.decl] = param.fromText(fields[param.name]?.firstOrNull())
                return
            }
            val parsed =
                try {
                    Json.parse(text)
                } catch (e: JsonException) {
                    badInput("The body is not a JSON object: ${e.message}", BODY)
                }
            val members = parsed as? Map<*, *> ?: badInput("The body is not a JSON object", BODY)
            for (param in body) values[param.decl] = param.fromJson(members[param.name])
        }
    }

    private class Segment(val literal: String?, val param: Param?)

    private class Param(val decl: ParamDecl) {
        val name = decl.name
        private val type = decl.type
        val required = decl.required
        private val default = decl.default
        private val minLength = (decl.spec as? DebugStringSpec)?.minLength ?: 0
        private val maxLength = (decl.spec as? DebugStringSpec)?.maxLength
        private val values = (decl.spec as? DebugStringSpec)?.values?.toList()
        private val min = (decl.spec as? DebugIntegerSpec)?.min ?: if (type == ParamType.INT) Int.MIN_VALUE.toLong() else null
        private val max = (decl.spec as? DebugIntegerSpec)?.max ?: if (type == ParamType.INT) Int.MAX_VALUE.toLong() else null

        init {
            // A default that a request could not send would reach the handler as a value outside the declaration.
            if (decl.hasDefault) {
                val problem =
                    try {
                        when (type) {
                            ParamType.STRING -> checked(default as String)
                            ParamType.INT, ParamType.LONG -> checked((default as Number).toLong())
                            ParamType.JSON -> Json.parse(default as String)
                            ParamType.BOOLEAN -> Unit
                        }
                        null
                    } catch (e: BadInput) {
                        e
                    } catch (e: JsonException) {
                        e
                    }
                if (problem != null) {
                    throw IllegalStateException("${decl.route.describe()}: the default of $name is not a value that it takes: ${problem.message}", problem)
                }
            }
        }

        /** The value from the path, the query, or a form, where every value is text. */
        fun fromText(text: String?): Any? {
            val given = if (type == ParamType.STRING) text else text?.takeIf { it.isNotEmpty() }
            given ?: return absent()
            return when (type) {
                ParamType.STRING -> checked(given)
                ParamType.INT, ParamType.LONG -> checked(given.toLongOrNull() ?: badInput("$name must be a whole number, was ${shown(given)}", name))
                ParamType.BOOLEAN ->
                    when (given) {
                        "true" -> true
                        "false" -> false
                        else -> badInput("$name must be true or false, was ${shown(given)}", name)
                    }
                ParamType.JSON -> error("A JSON parameter is not text")
            }
        }

        /** The value from a member of a JSON body: `null` counts as absent. */
        fun fromJson(value: Any?): Any? {
            if (value == null || value == JsonNull) return absent()
            return when (type) {
                ParamType.STRING -> checked(value as? String ?: badInput("$name must be a JSON string", name))
                ParamType.INT, ParamType.LONG ->
                    checked((value as? JsonNumber)?.toLongOrNull() ?: badInput("$name must be a whole number", name))
                ParamType.BOOLEAN -> value as? Boolean ?: badInput("$name must be true or false", name)
                ParamType.JSON -> Json.write(value, pretty = false)
            }
        }

        private fun absent(): Any? = if (required) badInput("$name is required", name) else default

        private fun checked(text: String): String {
            if (text.length < minLength) {
                badInput(if (minLength == 1) "$name must not be empty" else "$name must be at least $minLength characters long", name)
            }
            if (maxLength != null && text.length > maxLength) badInput("$name must be at most $maxLength characters long", name)
            if (values != null && text !in values) badInput("$name must be one of ${values.joinToString()}", name)
            return text
        }

        private fun checked(number: Long): Any {
            val outside = (min != null && number < min) || (max != null && number > max)
            if (outside) {
                val range =
                    when {
                        min != null && max != null -> "from $min to $max"
                        min != null -> "at least $min"
                        else -> "at most $max"
                    }
                badInput("$name must be $range, was $number", name)
            }
            return if (type == ParamType.INT) number.toInt() else number
        }

        private fun shown(text: String): String = if (text.length > SHOWN_CHARS) text.take(SHOWN_CHARS) + "..." else text
    }

    class BadInput(message: String, val field: String) : Exception(message)

    companion object {
        private const val BODY = "body"
        private const val SHOWN_CHARS = 40

        /** The order of the methods in `Allow`, as the server writes it. */
        private val METHOD_ORDER = listOf("GET", "PUT", "POST", "DELETE", "OPTIONS", "HEAD", "PATCH", "TRACE")

        /** The sub-paths that the server answers itself, before a tab sees the request. */
        private val RESERVED = setOf("_schema", "_view", "_view.js", "_view.css")
        private val TEMPLATE = Regex("\\{([A-Za-z0-9_.-]+)\\}")

        /**
         * Checks [declared] and compiles it, or returns `null` when the tab declared no route.
         *
         * @throws IllegalStateException for a mistake in the declarations.
         */
        fun build(declared: DebugRoutes): RouteTable? {
            declared.open = false
            val tabId = declared.tab.id
            if (declared.declared.isEmpty()) return null
            check(!declared.version.isNullOrEmpty()) { "Tab $tabId: set routes.version, the version of the tab's routes" }
            // items/{id} and items/{name} match the same requests, so they are one path, and OpenAPI allows one name.
            val templates = HashMap<String, String>()
            val seen = HashSet<String>()
            for (route in declared.declared) {
                val path = route.template.replace(TEMPLATE, "{}")
                val first = templates.getOrPut(path) { route.template }
                check(first == route.template) { "Tab $tabId: $first and ${route.template} are the same path; give its parameters the same names" }
                check(seen.add("${route.method} $path")) { "Tab $tabId: two routes ${route.method} ${route.template}" }
            }
            val routes = declared.declared.map(::compile)
            return RouteTable(tabId, routes, OpenApiDocument(declared).write())
        }

        private fun compile(route: DebugRoute): Route {
            val where = route.describe()
            val path = route.params.filter { it.location == ParamIn.PATH }
            val query = route.params.filter { it.location == ParamIn.QUERY }
            val body = route.params.filter { it.location == ParamIn.BODY }
            val handler = checkNotNull(route.handler) { "$where: no handler; call handle { ... } in the route's block" }
            val segments = segments(route, path)
            check(body.isEmpty() || route.rawBodyType == null) { "$where: a raw body and body fields" }
            check(route.method != "GET" || (body.isEmpty() && route.rawBodyType == null)) { "$where: a GET route has no body" }
            if (route.acceptsForm) {
                check(body.isNotEmpty()) { "$where: acceptsForm without body fields" }
                check(body.none { it.type == ParamType.JSON }) { "$where: a form can't carry the JSON field ${body.first { it.type == ParamType.JSON }.name}" }
            }
            for (param in route.params) checkSpec(where, param)
            return Route(
                method = route.method,
                segments = segments,
                query = query.map(::Param),
                body = body.map(::Param),
                rawBody = route.rawBodyType != null,
                acceptsForm = route.acceptsForm,
                handler = handler,
            )
        }

        private fun segments(route: DebugRoute, path: List<ParamDecl>): List<Segment> {
            val where = route.describe()
            val parts = route.template.split('/')
            check(route.template.isNotEmpty() && parts.none { it.isEmpty() }) {
                "$where: a path is relative to the tab, such as items/{id}, with no empty segment"
            }
            check(parts.first() !in RESERVED) { "$where: the server answers ${parts.first()} itself" }
            val byName = path.associateBy { it.name }
            val used = HashSet<String>()
            val segments =
                parts.map { part ->
                    val name = TEMPLATE.matchEntire(part)?.groupValues?.get(1)
                    if (name == null) {
                        check('{' !in part && '}' !in part && '?' !in part && '#' !in part) { "$where: the segment $part is not a literal or a {name}" }
                        Segment(part, null)
                    } else {
                        val param = checkNotNull(byName[name]) { "$where: declare the path parameter {$name} with path.string(\"$name\")" }
                        check(used.add(name)) { "$where: {$name} twice in the path" }
                        Segment(null, Param(param))
                    }
                }
            val unused = path.filter { it.name !in used }
            check(unused.isEmpty()) { "$where: the path has no {${unused.first().name}}" }
            return segments
        }

        private fun checkSpec(where: String, param: ParamDecl) {
            val spec = param.spec
            if (spec is DebugStringSpec) {
                check(spec.minLength >= 0) { "$where: ${param.name} has a negative minLength" }
                spec.maxLength?.let { check(it >= spec.minLength) { "$where: ${param.name} has a maxLength below its minLength" } }
                spec.values?.let { check(it.isNotEmpty()) { "$where: ${param.name} has an empty list of values" } }
            }
            if (spec is DebugIntegerSpec) {
                val min = spec.min
                val max = spec.max
                if (min != null && max != null) check(min <= max) { "$where: ${param.name} has a min above its max" }
                if (param.type == ParamType.INT) {
                    val range = Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
                    check((min == null || min in range) && (max == null || max in range)) { "$where: ${param.name} is an int, and its bounds must be ints" }
                }
            }
        }
    }
}

private fun badInput(message: String, field: String): Nothing = throw RouteTable.BadInput(message, field)
