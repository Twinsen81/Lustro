package io.github.twinsen81.lustro.logs

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The tab's OpenAPI document comes from its route declarations. The committed
 * `openapi/logs.openapi.json`, which the wire-schema artifact and the CLI copy, must be that
 * document: run the tests with `LUSTRO_UPDATE_OPENAPI=1` in the environment to write it.
 */
@RunWith(RobolectricTestRunner::class)
class LogsOpenApiDocumentTest {
    private val document = LogsDebugTab.create().schema()!!

    @Test
    fun `the committed document is the generated one`() {
        val file = File("openapi/logs.openapi.json")
        if (System.getenv("LUSTRO_UPDATE_OPENAPI") == "1") {
            file.parentFile?.mkdirs()
            file.writeText(document + "\n")
        }
        assertEquals("Run the tests with LUSTRO_UPDATE_OPENAPI=1 to write ${file.path}", file.readText(), document + "\n")
    }

    @Test
    fun `the generated document has the operations, parameters, and bodies of the hand-written one`() {
        val json = JSONObject(document)
        assertEquals("1.1", json.getJSONObject("info").getString("version"))
        assertEquals("Lustro Logs debug tab", json.getJSONObject("info").getString("title"))
        // The hand-written document, with an int as int32.
        val filters =
            listOf(
                Parameter("cursor", """{"type":"string"}"""),
                Parameter("level", """{"type":"string","enum":["V","D","I","W","E","F"]}"""),
                Parameter("tag", """{"type":"string","maxLength":200}"""),
                Parameter("q", """{"type":"string"}"""),
                Parameter("mq", """{"type":"string","maxLength":200}"""),
                Parameter("from", """{"type":"integer","format":"int64"}"""),
                Parameter("to", """{"type":"integer","format":"int64"}"""),
            )
        val expected =
            listOf(
                Operation(
                    "GET", "entries", "listLogEntries",
                    filters + Parameter("limit", """{"type":"integer","format":"int32","minimum":1,"maximum":5000,"default":500}"""),
                    null, listOf("application/json"), STATUSES_400,
                ),
                Operation(
                    "GET", "text", "exportLogText", filters + Parameter("limit", """{"type":"integer","format":"int32","minimum":1}"""),
                    null, listOf("text/plain"), STATUSES_400,
                ),
                Operation("POST", "clear", "clearLog", emptyList(), null, listOf("application/json"), STATUSES),
                Operation("POST", "pause", "pauseLog", emptyList(), mapOf("paused" to "boolean"), listOf("application/json"), STATUSES_400),
                Operation("POST", "compact", "setLogCompact", emptyList(), mapOf("compact" to "boolean"), listOf("application/json"), STATUSES_400),
            )
        assertEquals(expected, operations(json))
        assertEquals(
            setOf("ErrorEnvelope", "LogStreamEnvelope", "LogEntry", "LogState"),
            json.getJSONObject("components").getJSONObject("schemas").keys().asSequence().toSet(),
        )
    }

    /** A query parameter, never required, with its [schema] as the members of the JSON object. */
    private data class Parameter(val name: String, val schema: Map<String, String>) {
        constructor(name: String, schema: String) : this(name, members(JSONObject(schema)))
    }

    /**
     * An operation, with the fields of its body, which is optional, by name and type, or `null`
     * for no body.
     */
    private data class Operation(
        val method: String,
        val path: String,
        val operationId: String,
        val parameters: List<Parameter>,
        val body: Map<String, String>?,
        val content: List<String>,
        val statuses: List<String>,
    )

    private fun operations(document: JSONObject): List<Operation> {
        val paths = document.getJSONObject("paths")
        // org.json keeps no member order, so the order is the declarations' in the document's text.
        val order = Regex("\"/api/v1/logs/([^\"]*)\": \\{").findAll(this.document).map { it.groupValues[1] }.toList()
        return order.flatMap { path ->
            val item = paths.getJSONObject("/api/v1/logs/$path")
            listOf("get", "post").filter { item.has(it) }.map { method -> operation(method, path, item.getJSONObject(method)) }
        }
    }

    private fun operation(method: String, path: String, operation: JSONObject): Operation {
        val parameters = operation.optJSONArray("parameters")
        val list =
            (0 until (parameters?.length() ?: 0)).map { i ->
                val p = parameters!!.getJSONObject(i)
                assertEquals(p.getString("name"), "query", p.getString("in"))
                assertEquals(p.getString("name"), false, p.getBoolean("required"))
                Parameter(p.getString("name"), members(p.getJSONObject("schema")))
            }
        val body =
            operation.optJSONObject("requestBody")?.let { requestBody ->
                assertEquals(path, false, requestBody.getBoolean("required"))
                val content = requestBody.getJSONObject("content")
                assertEquals(path, listOf("application/json"), content.keys().asSequence().toList())
                val properties = content.getJSONObject("application/json").getJSONObject("schema").getJSONObject("properties")
                properties.keys().asSequence().associateWith { properties.getJSONObject(it).getString("type") }
            }
        val responses = operation.getJSONObject("responses")
        val content = responses.getJSONObject("200").optJSONObject("content")?.keys()?.asSequence()?.toList().orEmpty()
        return Operation(method.uppercase(), path, operation.getString("operationId"), list, body, content, responses.keys().asSequence().sorted().toList())
    }

    private companion object {
        val STATUSES = listOf("200", "401", "403", "413", "503")
        val STATUSES_400 = listOf("200", "400", "401", "403", "413", "503")

        // org.json keeps no member order.
        fun members(json: JSONObject): Map<String, String> = json.keys().asSequence().associateWith { json.get(it).toString() }
    }
}
