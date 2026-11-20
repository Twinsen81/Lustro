package io.github.twinsen81.lustro.sample

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The tab's OpenAPI document comes from its route declarations. It has the paths, methods,
 * parameters, and body fields that the hand-written `flags.openapi.json` had before the
 * declarations replaced it. The toggle route now also lists the form body that it took.
 */
@RunWith(RobolectricTestRunner::class)
class SampleFlagsTabDocumentTest {
    private val document = JSONObject(SampleFlagsTab(ApplicationProvider.getApplicationContext<Application>()).schema()!!)

    @Test
    fun `the generated document has the routes of the hand-written one`() {
        val expected =
            listOf(
                Operation("GET", "/api/v1/flags/snapshot", listOf(Parameter("query", "cursor", false)), null),
                Operation("POST", "/api/v1/flags/toggle", emptyList(), Body(true, listOf("id"), listOf("id"))),
                Operation("POST", "/api/v1/flags/upload", emptyList(), Body(true, null, null)),
            )
        assertEquals(expected, operations())
    }

    @Test
    fun `the document keeps the version, the schemas, and the response schemas`() {
        assertEquals("1.0", document.getJSONObject("info").getString("version"))
        val schemas = document.getJSONObject("components").getJSONObject("schemas")
        assertEquals(setOf("ErrorEnvelope", "Flag", "FlagCursorEnvelope"), schemas.keys().asSequence().toSet())
        val snapshot = document.getJSONObject("paths").getJSONObject("/api/v1/flags/snapshot").getJSONObject("get")
        assertEquals(
            "#/components/schemas/FlagCursorEnvelope",
            snapshot.getJSONObject("responses").getJSONObject("200").getJSONObject("content")
                .getJSONObject("application/json").getJSONObject("schema").getString("\$ref"),
        )
        val toggle = document.getJSONObject("paths").getJSONObject("/api/v1/flags/toggle").getJSONObject("post")
        val types = toggle.getJSONObject("requestBody").getJSONObject("content").keys().asSequence().toSet()
        assertEquals(setOf("application/json", "application/x-www-form-urlencoded"), types)
        assertTrue(toggle.getJSONObject("responses").has("404"))
    }

    private data class Parameter(val location: String, val name: String, val required: Boolean)

    private data class Body(val required: Boolean, val fields: List<String>?, val requiredFields: List<String>?)

    private data class Operation(val method: String, val path: String, val parameters: List<Parameter>, val body: Body?)

    private fun operations(): List<Operation> {
        val paths = document.getJSONObject("paths")
        return paths.keys().asSequence().sorted().flatMap { path ->
            val item = paths.getJSONObject(path)
            item.keys().asSequence().sorted().map { method -> operation(method, path, item.getJSONObject(method)) }
        }.toList()
    }

    private fun operation(method: String, path: String, operation: JSONObject): Operation {
        val parameters = operation.optJSONArray("parameters")
        val list =
            (0 until (parameters?.length() ?: 0)).map { i ->
                val p = parameters!!.getJSONObject(i)
                Parameter(p.getString("in"), p.getString("name"), p.optBoolean("required"))
            }
        val body =
            operation.optJSONObject("requestBody")?.let { requestBody ->
                val schema = requestBody.getJSONObject("content").getJSONObject("application/json").getJSONObject("schema")
                val fields = schema.optJSONObject("properties")?.keys()?.asSequence()?.sorted()?.toList()
                val required = schema.optJSONArray("required")?.let { array -> (0 until array.length()).map { array.getString(it) }.sorted() }
                Body(requestBody.optBoolean("required"), fields, if (fields == null) null else required.orEmpty())
            }
        return Operation(method.uppercase(), path, list, body)
    }
}
