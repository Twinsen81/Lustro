package io.github.twinsen81.lustro.network

import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.internal.DeclaredQueryParameters
import io.github.twinsen81.lustro.internal.Versions
import java.io.File
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The tab's OpenAPI document comes from its route declarations. The committed
 * `openapi/network.openapi.json`, which the wire-schema artifact and the CLI copy, must be that
 * document, made by a tab with a sender: run the tests with `LUSTRO_UPDATE_OPENAPI=1` in the
 * environment to write it.
 */
class NetworkOpenApiDocumentTest {
    private val tab = NetworkDebugTab.create(senderClient = OkHttpClient())
    private val document = tab.schema()!!

    @Test
    fun `the committed document is the generated one`() {
        val file = File("openapi/network.openapi.json")
        if (System.getenv("LUSTRO_UPDATE_OPENAPI") == "1") {
            file.parentFile?.mkdirs()
            file.writeText(document + "\n")
        }
        assertEquals("Run the tests with LUSTRO_UPDATE_OPENAPI=1 to write ${file.path}", file.readText(), document + "\n")
    }

    @Test
    fun `the generated document has the operations, parameters, and bodies of the hand-written one`() {
        val json = JSONObject(document)
        assertEquals(Versions.PROTOCOL_VERSION, json.getJSONObject("info").getString("version"))
        assertEquals("Lustro Network debug tab", json.getJSONObject("info").getString("title"))
        // The hand-written document, with four differences: regex is a boolean and ids an array
        // of strings, as the router reads them; senders lists 413, which the server answers for
        // any route; and send has no 404, because the route exists only when the app gave a sender.
        val expected =
            listOf(
                Operation(
                    "GET", "transactions", "listTransactions",
                    listOf(query("cursor", "string"), query("search", "string"), query("regex", "boolean"), query("since", "integer")),
                    null, listOf("application/json"), listOf("200", "400", "401", "403", "413", "503"),
                ),
                Operation("GET", "transactions/{id}", "getTransaction", listOf(path("id", "string")), null, listOf("application/json"), STATUSES_404),
                Operation(
                    "GET", "transactions/{id}/body/{direction}", "getTransactionBody", listOf(path("id", "string"), path("direction", "string")),
                    null, listOf("*/*"), STATUSES_404,
                ),
                Operation(
                    "GET", "transactions/_/export", "exportTransactions", listOf(query("format", "string"), query("ids", "array")),
                    null, listOf("application/json"), listOf("200", "400", "401", "403", "413", "503"),
                ),
                Operation("GET", "websockets", "listWebSockets", listOf(query("cursor", "string")), null, listOf("application/json"), STATUSES),
                Operation("GET", "websockets/{id}", "getWebSocket", listOf(path("id", "string")), null, listOf("application/json"), STATUSES_404),
                Operation(
                    "GET", "websockets/{id}/events", "listWebSocketEvents",
                    listOf(
                        path("id", "string"),
                        query("cursor", "string"),
                        query("limit", "integer"),
                        query("direction", "string"),
                        query("search", "string"),
                        query("regex", "boolean"),
                    ),
                    null, listOf("application/json"), listOf("200", "400", "401", "403", "404", "413", "503"),
                ),
                Operation(
                    "GET", "websockets/{id}/events/{seq}/payload", "getWebSocketPayload", listOf(path("id", "string"), path("seq", "integer")),
                    null, listOf("*/*"), STATUSES_404,
                ),
                Operation("POST", "clear", "clearTransactions", emptyList(), null, listOf("application/json"), STATUSES),
                Operation("GET", "rules", "listMockRules", emptyList(), null, listOf("application/json"), STATUSES),
                Operation("POST", "rules", "addMockRule", emptyList(), RAW_BODY, listOf("application/json"), STATUSES_400),
                Operation("POST", "rules/_/sync", "syncMockRules", emptyList(), RAW_BODY, listOf("application/json"), STATUSES_400),
                Operation(
                    "POST", "rules/delete", "deleteMockRule", emptyList(), Body(setOf("id"), setOf("id")),
                    listOf("application/json"), listOf("200", "400", "401", "403", "404", "413", "503"),
                ),
                Operation(
                    "POST", "rules/toggle", "toggleMockRule", emptyList(), Body(setOf("id"), setOf("id")),
                    listOf("application/json"), listOf("200", "400", "401", "403", "404", "413", "503"),
                ),
                Operation("POST", "pause", "togglePause", emptyList(), null, listOf("application/json"), STATUSES),
                Operation("POST", "overwrite-mode", "toggleOverwriteMode", emptyList(), null, listOf("application/json"), STATUSES),
                Operation("POST", "throttle", "setThrottle", emptyList(), Body(setOf("delayMs"), setOf("delayMs")), listOf("application/json"), STATUSES_400),
                Operation("GET", "senders", "listSenders", emptyList(), null, listOf("application/json"), STATUSES),
                Operation(
                    "POST", "send", "sendRequest", emptyList(), Body(setOf("url", "sender", "method", "headers", "body"), setOf("url")),
                    listOf("application/json"), listOf("200", "400", "401", "403", "413", "503", "504"),
                ),
            )
        assertEquals(expected, operations(json))
        assertEquals(
            setOf(
                "ErrorEnvelope", "TransactionCursorEnvelope", "Transaction", "PriorResponse", "WebSocketCursorEnvelope", "WebSocketConnection",
                "WebSocketEventStreamEnvelope", "WebSocketEvent", "HarDocument", "HarEntry", "HarNameValue", "MockRule", "MockRuleInput",
            ),
            json.getJSONObject("components").getJSONObject("schemas").keys().asSequence().toSet(),
        )
    }

    @Test
    fun `without a sender, the document has no send route and is otherwise the same`() {
        val noSender = NetworkDebugTab.create()
        for (method in listOf("GET", "POST")) assertNull(method, noSender.handle(DebugRequest("send", method)))
        val withoutSender = JSONObject(noSender.schema()!!)
        val withSender = JSONObject(document)
        assertFalse(withoutSender.getJSONObject("paths").has("/api/v1/network/send"))
        withSender.getJSONObject("paths").remove("/api/v1/network/send")
        assertEquals(withSender.toString(), withoutSender.toString())
    }

    @Test
    fun `the router answers 405 and 404 as the server's lookup of the document does`() {
        val declared = DeclaredQueryParameters.parse("network", document)!!
        val paths =
            listOf(
                "transactions", "transactions/x", "transactions/_", "transactions/_/export", "transactions/x/export", "transactions/x/body/request",
                "transactions/x/body/both", "transactions/a/b", "transactions/a/b/c/d", "websockets", "websockets/x", "websockets/x/events",
                "websockets/x/events/1/payload", "websockets/x/events/abc/payload", "rules", "rules/_/sync", "rules/x/sync", "rules/delete",
                "rules/toggle", "rules/x", "clear", "pause", "overwrite-mode", "throttle", "senders", "send", "nope", "transactions/",
            )
        for (path in paths) {
            val allowed = declared.methods(path)
            for (method in listOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD")) {
                val response = tab.handle(DebugRequest(path, method))
                val where = "$method $path"
                if (allowed.isEmpty() || method in allowed) {
                    // The server answers 404 when the tab has no answer: never a 405 here.
                    if (response != null) assertNotEquals(where, 405, response.status)
                } else {
                    assertEquals(where, 405, response?.status)
                    assertEquals(where, allowed.joinToString(", "), response!!.headers.get("Allow"))
                }
            }
        }
    }

    private data class Parameter(val location: String, val name: String, val required: Boolean, val type: String)

    /** A required JSON body, with its [fields] and [required] fields, or `null` for a raw body. */
    private data class Body(val fields: Set<String>?, val required: Set<String>?)

    private data class Operation(
        val method: String,
        val path: String,
        val operationId: String,
        val parameters: List<Parameter>,
        val body: Body?,
        val content: List<String>,
        val statuses: List<String>,
    )

    private fun query(name: String, type: String): Parameter = Parameter("query", name, false, type)

    private fun path(name: String, type: String): Parameter = Parameter("path", name, true, type)

    private fun operations(document: JSONObject): List<Operation> {
        val paths = document.getJSONObject("paths")
        // org.json keeps no member order, so the order is the declarations' in the document's text.
        val order = Regex("\"/api/v1/network/([^\"]*)\": \\{").findAll(this.document).map { it.groupValues[1] }.toList()
        return order.flatMap { path ->
            val item = paths.getJSONObject("/api/v1/network/$path")
            METHODS.filter { item.has(it) }.map { method -> operation(method, path, item.getJSONObject(method)) }
        }
    }

    private fun operation(method: String, path: String, operation: JSONObject): Operation {
        val parameters = operation.optJSONArray("parameters")
        val list =
            (0 until (parameters?.length() ?: 0)).map { i ->
                val p = parameters!!.getJSONObject(i)
                Parameter(p.getString("in"), p.getString("name"), p.getBoolean("required"), p.getJSONObject("schema").getString("type"))
            }
        val body =
            operation.optJSONObject("requestBody")?.let { requestBody ->
                assertEquals(path, true, requestBody.getBoolean("required"))
                val content = requestBody.getJSONObject("content")
                assertEquals(path, listOf("application/json"), content.keys().asSequence().toList())
                val schema = content.getJSONObject("application/json").getJSONObject("schema")
                if (schema.optString("type") != "object") return@let RAW_BODY
                val required = schema.getJSONArray("required")
                Body(schema.getJSONObject("properties").keys().asSequence().toSet(), (0 until required.length()).mapTo(HashSet()) { required.getString(it) })
            }
        val responses = operation.getJSONObject("responses")
        val content = responses.getJSONObject("200").optJSONObject("content")?.keys()?.asSequence()?.toList().orEmpty()
        return Operation(method.uppercase(), path, operation.getString("operationId"), list, body, content, responses.keys().asSequence().sorted().toList())
    }

    private companion object {
        val METHODS = listOf("get", "post")
        val RAW_BODY = Body(null, null)
        val STATUSES = listOf("200", "401", "403", "413", "503")
        val STATUSES_400 = listOf("200", "400", "401", "403", "413", "503")
        val STATUSES_404 = listOf("200", "401", "403", "404", "413", "503")
    }
}
