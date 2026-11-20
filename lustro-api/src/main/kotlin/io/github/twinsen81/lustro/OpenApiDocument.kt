@file:OptIn(ExperimentalDebugRoutes::class)

package io.github.twinsen81.lustro

/**
 * Writes a tab's OpenAPI document from its declared routes: the paths and methods, the
 * parameters with their types, options, and descriptions, the request bodies, the responses,
 * the shared error envelope, and the schemas that the tab added.
 *
 * Every operation lists the error statuses that the server can answer for any route (`401`,
 * `403`, `413`, `503`), `400` when the route has parameters or a body, and the statuses that
 * the route declares with [DebugRoute.errors].
 *
 * @throws IllegalStateException for a JSON fragment that is not a JSON object, a reference to
 *   a schema that the tab did not add, or two operations with the same `operationId`.
 */
internal class OpenApiDocument(private val declared: DebugRoutes) {
    private val tab = declared.tab

    fun write(): String {
        val schemas = LinkedHashMap<String, Any>()
        schemas[ERROR_ENVELOPE_NAME] = Json.parse(ERROR_ENVELOPE)
        for ((name, schema) in declared.components) {
            check(name != ERROR_ENVELOPE_NAME) { "Tab ${tab.id}: $ERROR_ENVELOPE_NAME is the shared error envelope" }
            check(COMPONENT_NAME.matches(name)) { "Tab ${tab.id}: $name is not a component name" }
            schemas[name] = fragment("the component $name", schema)
        }
        val paths = LinkedHashMap<String, MutableMap<String, Any>>()
        val operationIds = HashSet<String>()
        for (route in declared.declared) {
            val item = paths.getOrPut("/api/v1/${tab.id}/${route.template}") { LinkedHashMap() }
            item[route.method.lowercase()] = operation(route, operationIds)
        }
        val info = linkedMapOf<String, Any>("title" to (declared.title ?: "${tab.title} debug tab"), "version" to declared.version.orEmpty())
        declared.description?.let { info["description"] = it }
        val document =
            linkedMapOf(
                "openapi" to "3.1.0",
                "info" to info,
                "servers" to listOf(linkedMapOf("url" to "/", "description" to "The Lustro debug server (loopback by default).")),
                "paths" to paths,
                "components" to linkedMapOf("responses" to linkedMapOf("Error" to Json.parse(ERROR_RESPONSE)), "schemas" to schemas),
            )
        checkReferences(document, schemas.keys)
        return Json.write(document, pretty = true)
    }

    private fun operation(route: DebugRoute, operationIds: MutableSet<String>): Map<String, Any> {
        val operation = LinkedHashMap<String, Any>()
        val id = route.operationId ?: defaultOperationId(route)
        check(operationIds.add(id)) { "Tab ${tab.id}: two operations with the operationId $id" }
        operation["operationId"] = id
        route.summary?.let { operation["summary"] = it }
        route.description?.let { operation["description"] = it }
        val path = route.template.split('/').mapNotNull { segment -> TEMPLATE.matchEntire(segment)?.groupValues?.get(1) }
        val parameters =
            path.mapNotNull { name -> route.params.firstOrNull { it.location == ParamIn.PATH && it.name == name } } +
                route.params.filter { it.location == ParamIn.QUERY }
        if (parameters.isNotEmpty()) operation["parameters"] = parameters.map { parameter(route, it) }
        requestBody(route)?.let { operation["requestBody"] = it }
        operation["responses"] = responses(route)
        return operation
    }

    private fun parameter(route: DebugRoute, param: ParamDecl): Map<String, Any> {
        val parameter = linkedMapOf<String, Any>("name" to param.name, "in" to param.location.wire, "required" to param.required)
        parameter["schema"] = schema(route, param)
        param.spec.description?.let { parameter["description"] = it }
        return parameter
    }

    private fun requestBody(route: DebugRoute): Map<String, Any>? {
        val rawType = route.rawBodyType
        val fields = route.params.filter { it.location == ParamIn.BODY }
        if (rawType == null && fields.isEmpty()) return null
        val body = LinkedHashMap<String, Any>()
        val content = LinkedHashMap<String, Any>()
        if (rawType != null) {
            body["required"] = true
            content[rawType] = linkedMapOf("schema" to fragment("${route.describe()}, the raw body", route.rawBodySchema.orEmpty()))
        } else {
            body["required"] = fields.any { it.required }
            val schema = linkedMapOf<String, Any>("type" to "object")
            val required = fields.filter { it.required }.map { it.name }
            if (required.isNotEmpty()) schema["required"] = required
            schema["properties"] = fields.associateTo(LinkedHashMap()) { field -> field.name to property(route, field) }
            content["application/json"] = linkedMapOf("schema" to schema)
            if (route.acceptsForm) content["application/x-www-form-urlencoded"] = linkedMapOf("schema" to schema)
        }
        route.bodyDescription?.let { body["description"] = it }
        body["content"] = content
        return body
    }

    private fun property(route: DebugRoute, field: ParamDecl): Map<String, Any> {
        val schema = LinkedHashMap(schema(route, field))
        val description = field.spec.description
        if (description != null && "description" !in schema) schema["description"] = description
        return schema
    }

    private fun schema(route: DebugRoute, param: ParamDecl): Map<String, Any> {
        param.spec.schema?.let { return fragment("${route.describe()}, the schema of ${param.name}", it) }
        val schema = LinkedHashMap<String, Any>()
        when (param.type) {
            ParamType.STRING -> {
                val spec = param.spec as DebugStringSpec
                schema["type"] = "string"
                if (spec.minLength > 0) schema["minLength"] = spec.minLength
                spec.maxLength?.let { schema["maxLength"] = it }
                spec.values?.let { schema["enum"] = it.toList() }
            }
            ParamType.INT, ParamType.LONG -> {
                val spec = param.spec as DebugIntegerSpec
                schema["type"] = "integer"
                spec.min?.let { schema["minimum"] = it }
                spec.max?.let { schema["maximum"] = it }
            }
            ParamType.BOOLEAN -> schema["type"] = "boolean"
            ParamType.JSON -> Unit
        }
        if (param.hasDefault) {
            val default = param.default
            schema["default"] = if (param.type == ParamType.JSON) Json.parse(default as String) else default as Any
        }
        return schema
    }

    private fun responses(route: DebugRoute): Map<String, Any> {
        val responses = LinkedHashMap<String, Any>()
        val ok = linkedMapOf<String, Any>("description" to (route.responseDescription ?: "Success."))
        route.responseSchema?.let { schema ->
            ok["content"] = linkedMapOf("application/json" to linkedMapOf("schema" to fragment("${route.describe()}, the response", schema)))
        }
        responses["200"] = ok
        val errors = sortedSetOf(401, 403, 413, 503)
        if (route.params.isNotEmpty() || route.rawBodyType != null) errors += 400
        errors += route.errorStatuses
        for (status in errors) responses[status.toString()] = linkedMapOf("\$ref" to ERROR_RESPONSE_REF)
        return responses
    }

    private fun fragment(what: String, json: String): Map<String, Any> {
        val parsed =
            try {
                Json.parse(json)
            } catch (e: JsonException) {
                throw IllegalStateException("Tab ${tab.id}: $what is not valid JSON: ${e.message}", e)
            }
        @Suppress("UNCHECKED_CAST")
        return checkNotNull(parsed as? Map<String, Any>) { "Tab ${tab.id}: $what is not a JSON object" }
    }

    private fun checkReferences(value: Any?, schemas: Set<String>) {
        when (value) {
            is Map<*, *> -> {
                val ref = value["\$ref"] as? String
                if (ref != null && ref.startsWith(SCHEMA_REF)) {
                    check(ref.removePrefix(SCHEMA_REF) in schemas) { "Tab ${tab.id}: $ref names no schema; add it with routes.component()" }
                }
                if (ref != null && ref.startsWith("#/components/responses/")) {
                    check(ref == ERROR_RESPONSE_REF) { "Tab ${tab.id}: $ref names no response" }
                }
                value.values.forEach { checkReferences(it, schemas) }
            }
            is List<*> -> value.forEach { checkReferences(it, schemas) }
        }
    }

    private fun defaultOperationId(route: DebugRoute): String =
        route.method.lowercase() +
            route.template.split('/').joinToString("") { segment ->
                val name = TEMPLATE.matchEntire(segment)?.groupValues?.get(1)
                if (name != null) "By" + words(name) else words(segment)
            }

    private fun words(text: String): String =
        text.split(NOT_ALPHANUMERIC).filter { it.isNotEmpty() }.joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }

    private companion object {
        const val ERROR_ENVELOPE_NAME = "ErrorEnvelope"
        const val SCHEMA_REF = "#/components/schemas/"
        const val ERROR_RESPONSE_REF = "#/components/responses/Error"
        val TEMPLATE = Regex("\\{([A-Za-z0-9_.-]+)\\}")
        val COMPONENT_NAME = Regex("[A-Za-z0-9._-]+")
        val NOT_ALPHANUMERIC = Regex("[^A-Za-z0-9]+")

        const val ERROR_RESPONSE =
            """{"description": "Error envelope.", "content": {"application/json": {"schema": {"${'$'}ref": "#/components/schemas/ErrorEnvelope"}}}}"""

        const val ERROR_ENVELOPE = """
            {
              "type": "object",
              "required": ["error", "message"],
              "properties": {
                "error": {
                  "type": "string",
                  "enum": ["bad_request", "unauthorized", "forbidden", "not_found", "method_not_allowed", "conflict", "payload_too_large",
                    "unprocessable_entity", "too_many_requests", "internal_error", "unavailable", "timeout", "error"],
                  "description": "Machine error type, e.g. bad_request, not_found, timeout."
                },
                "message": {"type": "string"},
                "code": {"type": "string"},
                "field": {"type": "string"},
                "hint": {"type": "string"}
              },
              "additionalProperties": false
            }
        """
    }
}
