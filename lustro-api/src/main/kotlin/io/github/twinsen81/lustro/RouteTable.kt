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
     * the most literal segments wins, whatever its methods. For the route's own method, a
     * segment that its path parameter does not take also returns `null`: the path names a
     * resource that does not exist. The method check comes first, so another method gets
     * `405`. One slash at the end of the path does not count, so `seed/` matches `seed`.
     */
    fun handle(request: DebugRequest): DebugResponse? {
        val path = withoutTrailingSlash(request.path)
        val segments = segmentsOf(path)
        val matching = routes.filter { it.matches(segments) }
        val literals = matching.maxOfOrNull { it.literals } ?: return null
        val candidates = matching.filter { it.literals == literals }
        val method = request.method.uppercase()
        val route = candidates.firstOrNull { it.method == method } ?: return methodNotAllowed(path, method, candidates)
        val pathValues = route.readPath(segments) ?: return null
        val values =
            try {
                route.read(request, pathValues)
            } catch (e: BadInput) {
                return DebugResponse.error(e.message.orEmpty(), field = e.field)
            }
        return try {
            route.handler.handle(DebugCall(request, values))
        } catch (e: DebugResponseException) {
            e.response
        }
    }

    private fun methodNotAllowed(path: String, method: String, candidates: List<Route>): DebugResponse {
        val declared = candidates.mapTo(HashSet()) { it.method }
        val allowed = METHOD_ORDER.filter { it in declared }
        val where = if (path.isEmpty()) tabId else "$tabId/$path"
        val error = DebugResponse.error("$where takes ${allowed.joinToString(" or ")}, not $method", status = 405)
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

        /** The values of the path parameters, or `null` when a segment does not fit its parameter. */
        fun readPath(given: List<String>): MutableMap<ParamDecl, Any?>? {
            val values = HashMap<ParamDecl, Any?>()
            segments.forEachIndexed { i, segment ->
                val param = segment.param ?: return@forEachIndexed
                values[param.decl] =
                    try {
                        param.fromText(given[i])
                    } catch (_: BadInput) {
                        return null
                    }
            }
            return values
        }

        fun read(request: DebugRequest, values: MutableMap<ParamDecl, Any?>): Map<ParamDecl, Any?> {
            for (param in query) values[param.decl] = param.fromTexts(request.queryParams[param.name])
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
                for (param in body) values[param.decl] = param.fromTexts(fields[param.name])
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
        private val minDouble = (decl.spec as? DebugNumberSpec)?.min
        private val maxDouble = (decl.spec as? DebugNumberSpec)?.max

        init {
            // A default that a request could not send would reach the handler as a value outside the declaration.
            if (decl.hasDefault) {
                val problem =
                    try {
                        when (type) {
                            ParamType.STRING -> checked(default as String)
                            ParamType.STRINGS -> (default as List<*>).forEach { checked(it as String) }
                            ParamType.INT, ParamType.LONG -> checked((default as Number).toLong())
                            ParamType.DOUBLE -> checked(default as Double, default.toString())
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

        /** The value from the query or a form, which can give a parameter more than once. */
        fun fromTexts(texts: List<String>?): Any? =
            if (type == ParamType.STRINGS) {
                if (texts.isNullOrEmpty()) absent() else texts.map(::checked)
            } else {
                fromText(texts?.firstOrNull())
            }

        /** The value from one text: a path segment, or the first value of a query or form parameter. */
        fun fromText(text: String?): Any? {
            text ?: return absent()
            if (text.isEmpty() && type != ParamType.STRING && decl.hasDefault) return default
            return when (type) {
                ParamType.STRING -> checked(text)
                ParamType.INT, ParamType.LONG -> checked(text.toLongOrNull() ?: badInput("$name must be a whole number, was ${shown(text)}", name))
                ParamType.DOUBLE -> {
                    if (!DECIMAL.matches(text)) badInput("$name must be a number, was ${shown(text)}", name)
                    checked(text.toDouble(), text)
                }
                ParamType.BOOLEAN ->
                    when (text) {
                        "true" -> true
                        "false" -> false
                        else -> badInput("$name must be true or false, was ${shown(text)}", name)
                    }
                ParamType.STRINGS, ParamType.JSON -> error("$type is not one text")
            }
        }

        /** The value from a member of a JSON body: `null` counts as absent. */
        fun fromJson(value: Any?): Any? {
            if (value == null || value == JsonNull) return absent()
            return when (type) {
                ParamType.STRING -> checked(value as? String ?: badInput("$name must be a JSON string, was ${shownJson(value)}", name))
                ParamType.STRINGS -> {
                    val items = value as? List<*> ?: badInput("$name must be a JSON array of strings, was ${shownJson(value)}", name)
                    items.map { item -> checked(item as? String ?: badInput("$name must be a JSON array of strings, has ${shownJson(item)}", name)) }
                }
                ParamType.INT, ParamType.LONG ->
                    checked((value as? JsonNumber)?.toLongOrNull() ?: badInput("$name must be a whole number, was ${shownJson(value)}", name))
                ParamType.DOUBLE -> {
                    val text = (value as? JsonNumber)?.toString() ?: badInput("$name must be a number, was ${shownJson(value)}", name)
                    checked(text.toDouble(), text)
                }
                ParamType.BOOLEAN -> value as? Boolean ?: badInput("$name must be true or false, was ${shownJson(value)}", name)
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
            val below = min != null && number < min
            if (below || (max != null && number > max)) {
                // An int's own limits are not in the message unless they are the limit that the number passed.
                val declared = decl.spec as? DebugIntegerSpec
                val range =
                    when {
                        declared?.min != null && declared.max != null -> "from $min to $max"
                        below -> "at least $min"
                        else -> "at most $max"
                    }
                badInput("$name must be $range, was $number", name)
            }
            return if (type == ParamType.INT) number.toInt() else number
        }

        /** [number] as [text] gave it, which the message shows, so that 1e999 is not shown as Infinity. */
        private fun checked(number: Double, text: String): Double {
            if (!number.isFinite()) badInput("$name must be a finite number, was ${shown(text)}", name)
            val below = minDouble != null && number < minDouble
            if (below || (maxDouble != null && number > maxDouble)) {
                val range =
                    when {
                        minDouble != null && maxDouble != null -> "from $minDouble to $maxDouble"
                        below -> "at least $minDouble"
                        else -> "at most $maxDouble"
                    }
                badInput("$name must be $range, was ${shown(text)}", name)
            }
            return number
        }

        private fun shown(text: String): String =
            when {
                text.isEmpty() -> "empty"
                text.length > SHOWN_CHARS -> text.take(SHOWN_CHARS) + "..."
                else -> text
            }

        private fun shownJson(value: Any?): String = shown(Json.write(value, pretty = false))
    }

    class BadInput(message: String, val field: String) : Exception(message)

    companion object {
        private const val BODY = "body"
        private const val SHOWN_CHARS = 40

        // A JSON number (RFC 8259), with an optional leading +, as an int also takes "+5". Double.parseDouble alone
        // would also take NaN, Infinity, hex such as 0x1p3, a suffix such as 1d, and spaces around the number.
        private val DECIMAL = Regex("[+-]?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

        /** The order of the methods in `Allow`, as the server writes it. */
        private val METHOD_ORDER = listOf("GET", "PUT", "POST", "DELETE", "OPTIONS", "HEAD", "PATCH", "TRACE")

        /** The sub-paths that the server answers itself, before a tab sees the request. */
        private val RESERVED = setOf("_schema", "_view", "_view.js", "_view.css")
        private val TEMPLATE = Regex("\\{([A-Za-z0-9_.-]+)\\}")

        /** The segments of a path: none for the tab root, `""`. */
        private fun segmentsOf(path: String): List<String> = if (path.isEmpty()) emptyList() else path.split('/')

        // One slash only, so "items//" keeps an empty segment and matches no route. "/" stays too: the server
        // gives "" for both /api/v1/<tab> and /api/v1/<tab>/, so "/" came from a second slash.
        private fun withoutTrailingSlash(path: String): String = if (path.length > 1 && path.endsWith('/')) path.dropLast(1) else path

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
                check(seen.add("${route.method} $path")) { "Tab $tabId: two routes ${route.method} ${route.shownTemplate}" }
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
            val parts = segmentsOf(route.template)
            check(parts.none { it.isEmpty() }) {
                "$where: a path is relative to the tab, such as items/{id} or \"\" for the tab root, with no empty segment"
            }
            check(parts.firstOrNull() !in RESERVED) { "$where: the server answers ${parts.first()} itself" }
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
            if (spec is DebugNumberSpec) {
                val min = spec.min
                val max = spec.max
                check((min == null || min.isFinite()) && (max == null || max.isFinite())) { "$where: ${param.name} has a bound that is not a finite number" }
                if (min != null && max != null) check(min <= max) { "$where: ${param.name} has a min above its max" }
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
