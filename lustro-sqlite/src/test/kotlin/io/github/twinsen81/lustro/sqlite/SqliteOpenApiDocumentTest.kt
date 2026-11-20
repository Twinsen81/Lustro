package io.github.twinsen81.lustro.sqlite

import io.github.twinsen81.lustro.DebugRequest
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The tab's OpenAPI document comes from its route declarations. The committed
 * `openapi/sqlite.openapi.json`, which the wire-schema artifact and the CLI copy, must be that
 * document: run the tests with `LUSTRO_UPDATE_OPENAPI=1` in the environment to write it.
 */
@RunWith(RobolectricTestRunner::class)
class SqliteOpenApiDocumentTest {
    private val tab = SqliteDebugTab.create()
    private val document = tab.schema()!!

    @Test
    fun `the committed document is the generated one`() {
        val file = File("openapi/sqlite.openapi.json")
        if (System.getenv("LUSTRO_UPDATE_OPENAPI") == "1") {
            file.parentFile?.mkdirs()
            file.writeText(document + "\n")
        }
        assertEquals("Run the tests with LUSTRO_UPDATE_OPENAPI=1 to write ${file.path}", file.readText(), document + "\n")
    }

    @Test
    fun `the generated document has the operations, parameters, and bodies of the hand-written one`() {
        val json = JSONObject(document)
        assertEquals("1.2", json.getJSONObject("info").getString("version"))
        assertEquals("Lustro SQLite debug tab", json.getJSONObject("info").getString("title"))
        val db = query("db", "string", required = true)
        val table = query("table", "string", required = true)
        val row = setOf("db", "table", "rowid", "key")
        val expected =
            listOf(
                Operation("GET", "databases", "listSqliteDatabases", emptyList(), null, STATUSES_DATABASES),
                Operation("GET", "tables", "listSqliteTables", listOf(db), null, STATUSES),
                Operation("GET", "schema", "getSqliteSchema", listOf(db, table), null, STATUSES),
                Operation(
                    "GET", "rows", "listSqliteRows",
                    listOf(
                        db,
                        table,
                        query("cursor", "string"),
                        query("limit", "integer"),
                        query("filter", "string"),
                        query("sort", "string"),
                        query("dir", "string"),
                    ),
                    null, STATUSES,
                ),
                Operation("POST", "query", "runSqliteQuery", emptyList(), Body(setOf("db", "sql"), setOf("db", "sql")), STATUSES),
                Operation("POST", "exec", "execSqliteStatement", emptyList(), Body(setOf("db", "sql"), setOf("db", "sql")), STATUSES),
                Operation("POST", "edit", "editSqliteRow", emptyList(), Body(row + "values", setOf("db", "table", "values")), STATUSES),
                Operation("POST", "delete", "deleteSqliteRow", emptyList(), Body(row, setOf("db", "table")), STATUSES),
            )
        assertEquals(expected, operations(json))
        assertEquals(
            listOf(
                "ErrorEnvelope", "SqliteSource", "SqliteValuePolicy", "SqliteDatabase", "SqliteTableType", "SqliteTable", "SqliteColumn",
                "SqliteForeignKey", "SqliteSchema", "SqliteCell", "SqliteRow", "SqliteRowsPage", "SqliteValue", "SqliteQueryResult",
                "SqliteExecResult", "SqliteWriteOk",
            ),
            Regex("\n {6}\"(\\w+)\": \\{").findAll(document.substringAfter("\"schemas\": {")).map { it.groupValues[1] }.toList(),
        )
        // rowid takes a whole number or a string of one, as GET rows gives it: the handler reads its JSON.
        val rowid = json.getJSONObject("paths").getJSONObject("/api/v1/sqlite/edit").getJSONObject("post").getJSONObject("requestBody").getJSONObject("content")
            .getJSONObject("application/json").getJSONObject("schema").getJSONObject("properties").getJSONObject("rowid")
        assertEquals("[\"string\",\"integer\"]", rowid.getJSONArray("type").toString())
    }

    @Test
    fun `a declared path answers 405 with Allow for another method, and another path is not the tab's`() {
        val routes = mapOf("databases" to "GET", "tables" to "GET", "schema" to "GET", "rows" to "GET", "query" to "POST", "exec" to "POST", "edit" to "POST", "delete" to "POST")
        for ((path, allowed) in routes) {
            for (method in listOf("GET", "POST", "PUT", "DELETE", "PATCH").filter { it != allowed }) {
                val response = tab.handle(DebugRequest(path, method))
                assertEquals("$method $path", 405, response?.status)
                assertEquals("$method $path", allowed, response!!.headers.get("Allow"))
            }
        }
        for (path in listOf("nope", "rows/x", "databases/", "")) assertNull(path, tab.handle(DebugRequest(path, "GET")))
    }

    private data class Parameter(val location: String, val name: String, val required: Boolean, val type: String)

    /** A required JSON body, with its [fields] and [required] fields. */
    private data class Body(val fields: Set<String>, val required: Set<String>)

    private data class Operation(
        val method: String,
        val path: String,
        val operationId: String,
        val parameters: List<Parameter>,
        val body: Body?,
        val statuses: List<String>,
    )

    private fun query(name: String, type: String, required: Boolean = false): Parameter = Parameter("query", name, required, type)

    private fun operations(document: JSONObject): List<Operation> {
        val paths = document.getJSONObject("paths")
        // org.json keeps no member order, so the order is the declarations' in the document's text.
        val order = Regex("\"/api/v1/sqlite/([^\"]*)\": \\{").findAll(this.document).map { it.groupValues[1] }.toList()
        return order.flatMap { path ->
            val item = paths.getJSONObject("/api/v1/sqlite/$path")
            listOf("get", "post").filter { item.has(it) }.map { method -> operation(method, path, item.getJSONObject(method)) }
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
                val required = schema.getJSONArray("required")
                Body(schema.getJSONObject("properties").keys().asSequence().toSet(), (0 until required.length()).mapTo(HashSet()) { required.getString(it) })
            }
        val responses = operation.getJSONObject("responses")
        assertEquals(path, listOf("application/json"), responses.getJSONObject("200").getJSONObject("content").keys().asSequence().toList())
        return Operation(method.uppercase(), path, operation.getString("operationId"), list, body, responses.keys().asSequence().sorted().toList())
    }

    private companion object {
        val STATUSES_DATABASES = listOf("200", "401", "403", "413", "503", "504")
        val STATUSES = listOf("200", "400", "401", "403", "404", "413", "500", "503", "504")
    }
}
