package io.github.twinsen81.lustro.prefs

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
 * `openapi/prefs.openapi.json`, which the wire-schema artifact and the CLI copy, must be that
 * document: run the tests with `LUSTRO_UPDATE_OPENAPI=1` in the environment to write it.
 */
@RunWith(RobolectricTestRunner::class)
class PrefsOpenApiDocumentTest {
    private val document = PrefsDebugTab.create(ApplicationProvider.getApplicationContext<Application>()).schema()!!

    @Test
    fun `the committed document is the generated one`() {
        val file = File("openapi/prefs.openapi.json")
        if (System.getenv("LUSTRO_UPDATE_OPENAPI") == "1") {
            file.parentFile?.mkdirs()
            file.writeText(document + "\n")
        }
        assertEquals("Run the tests with LUSTRO_UPDATE_OPENAPI=1 to write ${file.path}", file.readText(), document + "\n")
    }

    @Test
    fun `the generated document has the routes, parameters, and body fields of the hand-written one`() {
        val store = Parameter("query", "store", false)
        val file = Parameter("query", "file", true)
        val expected =
            listOf(
                Operation("GET", "/api/v1/prefs/entries", listOf(store, file, Parameter("query", "cursor", false)), null),
                Operation("GET", "/api/v1/prefs/entry", listOf(store, file, Parameter("query", "key", true)), null),
                Operation("GET", "/api/v1/prefs/files", emptyList(), null),
                Operation("POST", "/api/v1/prefs/clear", emptyList(), Body(setOf("store", "file", "confirm"), setOf("file", "confirm"))),
                Operation("POST", "/api/v1/prefs/delete", emptyList(), Body(setOf("store", "file", "key"), setOf("file", "key"))),
                Operation("POST", "/api/v1/prefs/edit", emptyList(), Body(setOf("store", "file", "key", "type", "value"), setOf("file", "key", "type", "value"))),
            )
        assertEquals(expected, operations(JSONObject(document)))
        assertEquals("1.2", JSONObject(document).getJSONObject("info").getString("version"))
    }

    private data class Parameter(val location: String, val name: String, val required: Boolean)

    /** A body that is required, as JSON only, with its [fields] and [required] fields. */
    private data class Body(val fields: Set<String>, val required: Set<String>)

    private data class Operation(val method: String, val path: String, val parameters: List<Parameter>, val body: Body?)

    private fun operations(document: JSONObject): List<Operation> {
        val paths = document.getJSONObject("paths")
        return paths.keys().asSequence().flatMap { path ->
            val item = paths.getJSONObject(path)
            item.keys().asSequence().map { method -> operation(method, path, item.getJSONObject(method)) }
        }.sortedBy { "${it.method} ${it.path}" }.toList()
    }

    private fun operation(method: String, path: String, operation: JSONObject): Operation {
        val parameters = operation.optJSONArray("parameters")
        val list =
            (0 until (parameters?.length() ?: 0)).map { i ->
                val p = parameters!!.getJSONObject(i)
                Parameter(p.getString("in"), p.getString("name"), p.getBoolean("required"))
            }
        val body =
            operation.optJSONObject("requestBody")?.let { requestBody ->
                assertEquals(path, true, requestBody.getBoolean("required"))
                val content = requestBody.getJSONObject("content")
                assertEquals(path, listOf("application/json"), content.keys().asSequence().toList())
                val schema = content.getJSONObject("application/json").getJSONObject("schema")
                val required = schema.getJSONArray("required")
                Body(
                    schema.getJSONObject("properties").keys().asSequence().toSet(),
                    (0 until required.length()).mapTo(HashSet()) { required.getString(it) },
                )
            }
        return Operation(method.uppercase(), path, list, body)
    }
}
