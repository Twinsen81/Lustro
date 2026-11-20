package io.github.twinsen81.lustro.work

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The tab's OpenAPI document comes from its route declarations. The committed
 * `openapi/work.openapi.json`, which the wire-schema artifact and the CLI copy, must be that
 * document: run the tests with `LUSTRO_UPDATE_OPENAPI=1` in the environment to write it.
 */
@RunWith(RobolectricTestRunner::class)
class WorkOpenApiDocumentTest {
    private val document = WorkDebugTab.create(ApplicationProvider.getApplicationContext<Application>()).schema()!!

    @Test
    fun `the committed document is the generated one`() {
        val file = File("openapi/work.openapi.json")
        if (System.getenv("LUSTRO_UPDATE_OPENAPI") == "1") {
            file.parentFile?.mkdirs()
            file.writeText(document + "\n")
        }
        assertEquals("Run the tests with LUSTRO_UPDATE_OPENAPI=1 to write ${file.path}", file.readText(), document + "\n")
    }

    @Test
    fun `the generated document has the operations, parameters, and bodies of the hand-written one`() {
        val json = JSONObject(document)
        assertEquals("1.3", json.getJSONObject("info").getString("version"))
        assertEquals("Lustro WorkManager debug tab", json.getJSONObject("info").getString("title"))
        // The hand-written document, with an int as int32 and a long as int64.
        val cursor = Parameter("cursor", """{"type":"string"}""")
        val uuid = """{"type":"string","format":"uuid"}"""
        val string = """{"type":"string"}"""
        val expected =
            listOf(
                Operation(
                    "GET", "items", "listWork",
                    listOf(cursor, Parameter("state", string), Parameter("tag", string), Parameter("class", string), Parameter("name", string)),
                    null, listOf("application/json"), listOf("200", "400", "401", "403", "413", "500", "503", "504"),
                ),
                Operation(
                    "GET", "items/{id}", "getWork", listOf(Parameter("id", uuid, location = "path")),
                    null, listOf("application/json"), listOf("200", "400", "401", "403", "404", "413", "500", "503", "504"),
                ),
                Operation(
                    "GET", "events", "listWorkEvents",
                    listOf(
                        cursor,
                        Parameter("id", uuid),
                        Parameter("class", string),
                        Parameter("type", string),
                        Parameter("limit", """{"type":"integer","format":"int32","minimum":1,"maximum":5000,"default":500}"""),
                    ),
                    null, listOf("application/json"), listOf("200", "400", "401", "403", "413", "503"),
                ),
                Operation(
                    "GET", "metrics", "listWorkMetrics",
                    listOf(
                        Parameter("id", uuid),
                        Parameter("tag", string),
                        Parameter("class", string),
                        Parameter("state", string),
                        Parameter("from", """{"type":"integer","format":"int64","minimum":0}"""),
                        Parameter("to", """{"type":"integer","format":"int64","minimum":0}"""),
                    ),
                    null, listOf("application/json"), listOf("200", "400", "401", "403", "404", "413", "500", "503", "504"),
                ),
                Operation(
                    "POST", "cancel", "cancelWork", emptyList(), CANCEL_BODY,
                    listOf("application/json"), listOf("200", "400", "401", "403", "404", "413", "500", "503", "504"),
                ),
                Operation("POST", "prune", "pruneWork", emptyList(), null, listOf("application/json"), listOf("200", "401", "403", "413", "500", "503", "504")),
            )
        assertEquals(expected, operations(json))
        assertEquals(
            setOf(
                "ErrorEnvelope", "WorkState", "WorkData", "StopReason", "WorkConstraints", "WorkItem", "WorkTabState", "WorkCursorEnvelope",
                "WorkException", "WorkEvent", "WorkStreamEnvelope", "WorkPeriod",
            ),
            json.getJSONObject("components").getJSONObject("schemas").keys().asSequence().toSet(),
        )
    }

    /** A query parameter, never required, or a path parameter, with its [schema] as the members of the JSON object. */
    private data class Parameter(val name: String, val schema: Map<String, String>, val location: String) {
        constructor(name: String, schema: String, location: String = "query") : this(name, members(JSONObject(schema)), location)
    }

    /** An operation, with the JSON Schema of its required JSON body, or `null` for no body. */
    private data class Operation(
        val method: String,
        val path: String,
        val operationId: String,
        val parameters: List<Parameter>,
        val body: Any?,
        val content: List<String>,
        val statuses: List<String>,
    )

    private fun operations(document: JSONObject): List<Operation> {
        val paths = document.getJSONObject("paths")
        // org.json keeps no member order, so the order is the declarations' in the document's text.
        val order = Regex("\"/api/v1/work/([^\"]*)\": \\{").findAll(this.document).map { it.groupValues[1] }.toList()
        return order.flatMap { path ->
            val item = paths.getJSONObject("/api/v1/work/$path")
            listOf("get", "post").filter { item.has(it) }.map { method -> operation(method, path, item.getJSONObject(method)) }
        }
    }

    private fun operation(method: String, path: String, operation: JSONObject): Operation {
        val parameters = operation.optJSONArray("parameters")
        val list =
            (0 until (parameters?.length() ?: 0)).map { i ->
                val p = parameters!!.getJSONObject(i)
                assertEquals(p.getString("name"), p.getString("in") == "path", p.getBoolean("required"))
                Parameter(p.getString("name"), members(p.getJSONObject("schema")), p.getString("in"))
            }
        val body =
            operation.optJSONObject("requestBody")?.let { requestBody ->
                assertEquals(path, true, requestBody.getBoolean("required"))
                val content = requestBody.getJSONObject("content")
                assertEquals(path, listOf("application/json"), content.keys().asSequence().toList())
                normalized(content.getJSONObject("application/json").getJSONObject("schema"))
            }
        val responses = operation.getJSONObject("responses")
        val content = responses.getJSONObject("200").optJSONObject("content")?.keys()?.asSequence()?.toList().orEmpty()
        return Operation(method.uppercase(), path, operation.getString("operationId"), list, body, content, responses.keys().asSequence().sorted().toList())
    }

    private companion object {
        // The body of the hand-written document: exactly one of the three members.
        val CANCEL_BODY =
            normalized(
                JSONObject(
                    """
                    {"type": "object", "properties": {"id": {"type": "string", "format": "uuid"}, "name": {"type": "string", "minLength": 1},
                    "tag": {"type": "string", "minLength": 1}}, "minProperties": 1, "maxProperties": 1, "additionalProperties": false}
                    """,
                ),
            )

        // org.json keeps no member order, so objects compare as sorted maps.
        fun normalized(value: Any?): Any? =
            when (value) {
                is JSONObject -> value.keys().asSequence().sorted().associateWith { normalized(value.get(it)) }
                is org.json.JSONArray -> (0 until value.length()).map { normalized(value.get(it)) }
                else -> value
            }

        // org.json keeps no member order.
        fun members(json: JSONObject): Map<String, String> = json.keys().asSequence().associateWith { json.get(it).toString() }
    }
}
