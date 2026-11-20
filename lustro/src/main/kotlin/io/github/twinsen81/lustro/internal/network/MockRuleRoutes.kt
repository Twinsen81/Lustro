package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugRoutes
import io.github.twinsen81.lustro.DebugRoutes.Companion.ref
import io.github.twinsen81.lustro.ExperimentalDebugRoutes
import io.github.twinsen81.lustro.escapeForJson
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Network tab's routes under `rules`: the list of the mock rules, an add, a sync of the whole
 * set, and a delete or a toggle of one rule. [store] keeps the rules in the order they are tried.
 */
internal class MockRuleRoutes(private val store: NetworkTrafficStore) {
    /** Declares the routes, with the text of their OpenAPI document in [NetworkDocument]. */
    @OptIn(ExperimentalDebugRoutes::class)
    fun declare(routes: DebugRoutes) {
        routes.get("rules") {
            operationId = "listMockRules"
            summary = "List mock rules."
            response(NetworkDocument.RULES_RESPONSE, NetworkDocument.RULES_RESPONSE_SCHEMA)
            handle { listRules() }
        }
        routes.post("rules") {
            operationId = "addMockRule"
            summary = "Add (or upsert by id) a mock rule."
            description = NetworkDocument.ADD_RULE
            // MockRuleCodec reads the rule, with its own errors for each field.
            rawBody("application/json", ref("MockRuleInput"))
            response("Created.", NetworkDocument.ADD_RULE_RESPONSE_SCHEMA)
            handle { call -> addRule(call.request.bodyAsString().orEmpty()) }
        }
        routes.post("rules/_/sync") {
            operationId = "syncMockRules"
            summary = "Atomically replace the entire set of mock rules."
            description = NetworkDocument.SYNC_RULES
            rawBody("application/json", NetworkDocument.SYNC_RULES_BODY_SCHEMA)
            response("Replaced.", NetworkDocument.SYNC_RULES_RESPONSE_SCHEMA)
            handle { call -> syncRules(call.request.bodyAsString().orEmpty()) }
        }
        routes.post("rules/delete") {
            operationId = "deleteMockRule"
            summary = "Delete a mock rule by id."
            description = NetworkDocument.NO_SUCH_RULE
            val id = body.string("id").required()
            response("Acknowledged.", NetworkDocument.OK)
            errors(404)
            handle { call -> deleteRule(call[id]) }
        }
        routes.post("rules/toggle") {
            operationId = "toggleMockRule"
            summary = "Toggle a mock rule's enabled flag."
            description = NetworkDocument.NO_SUCH_RULE
            val id = body.string("id").required()
            response("Acknowledged.", NetworkDocument.OK)
            errors(404)
            handle { call -> toggleRule(call[id]) }
        }
    }

    // In the order the rules are tried, which the store keeps.
    private fun listRules(): DebugResponse =
        DebugResponse.json {
            append("{\"items\":[")
            val rules = store.getMockRules()
            rules.forEachIndexed { index, rule ->
                if (index > 0) append(",")
                appendMockRule(rule)
            }
            append("]}")
        }

    private fun addRule(body: String): DebugResponse =
        try {
            when (val parsed = MockRuleCodec.parse(JSONObject(body), generateMissingId = true)) {
                is MockRuleParseResult.Invalid ->
                    DebugResponse.error(parsed.message, field = parsed.field)

                is MockRuleParseResult.Valid -> {
                    store.addMockRule(parsed.rule)
                    DebugResponse.json {
                        append("{\"status\":\"ok\",\"id\":\"${parsed.rule.id.escapeForJson()}\"}")
                    }
                }
            }
        } catch (e: Exception) {
            DebugResponse.error("Invalid JSON: ${e.message}")
        }

    private fun syncRules(body: String): DebugResponse {
        return try {
            val arr = JSONArray(body)
            val rules = ArrayList<MockRuleImpl>(arr.length())
            val indexById = HashMap<String, Int>()
            for (i in 0 until arr.length()) {
                // Declarative sync is all-or-nothing: the OpenAPI promises the
                // resulting set equals the posted array, so a malformed entry must
                // reject the whole batch with a 400 BEFORE the store is touched —
                // never silently drop it — mirroring the single-rule add validation.
                val obj = arr.optJSONObject(i)
                    ?: return DebugResponse.error("rule at index $i is not a JSON object")
                when (val parsed = MockRuleCodec.parse(obj, generateMissingId = true)) {
                    is MockRuleParseResult.Invalid ->
                        return DebugResponse.error(
                            "rule at index $i: ${parsed.message}",
                            field = parsed.field,
                        )

                    is MockRuleParseResult.Valid -> {
                        // The store keeps one rule for each id, so a repeated id
                        // would leave a posted rule out of the set.
                        val first = indexById.putIfAbsent(parsed.rule.id, i)
                        if (first != null) {
                            return DebugResponse.error(
                                "rule at index $i has the same id as the rule at index $first: ${parsed.rule.id}",
                                field = "id",
                            )
                        }
                        rules.add(parsed.rule)
                    }
                }
            }
            store.replaceMockRules(rules)
            DebugResponse.json { append("{\"status\":\"ok\",\"count\":${rules.size}}") }
        } catch (e: Exception) {
            DebugResponse.error("Invalid JSON: ${e.message}")
        }
    }

    private fun deleteRule(ruleId: String): DebugResponse =
        when {
            ruleId.isBlank() -> DebugResponse.error("id is required", field = "id")
            store.removeMockRule(ruleId) -> ok()
            else -> noSuchRule(ruleId)
        }

    private fun toggleRule(ruleId: String): DebugResponse =
        when {
            ruleId.isBlank() -> DebugResponse.error("id is required", field = "id")
            store.toggleMockRule(ruleId) -> ok()
            else -> noSuchRule(ruleId)
        }

    // A 404, not ok: a client that mistyped the id must not think that it changed a rule.
    private fun noSuchRule(id: String): DebugResponse =
        DebugResponse.error("No mock rule has the id $id. GET rules lists them", status = 404, field = "id")

    // responseHeaders go out with the rule (the browser form doesn't show them,
    // but agents and the CLI round-trip the whole rule through this route).
    private fun StringBuilder.appendMockRule(rule: MockRuleImpl) {
        MockRuleCodec.appendJson(this, rule, includeHitCount = true)
    }

    private fun ok(): DebugResponse = DebugResponse.json { append("{\"status\":\"ok\"}") }
}
