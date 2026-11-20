package io.github.twinsen81.lustro.screen

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
 * `openapi/screen.openapi.json`, which the wire-schema artifact and the CLI copy, must be that
 * document: run the tests with `LUSTRO_UPDATE_OPENAPI=1` in the environment to write it.
 */
@RunWith(RobolectricTestRunner::class)
class ScreenOpenApiDocumentTest {
    private val document = ScreenDebugTab.create(ApplicationProvider.getApplicationContext<Application>()).schema()!!

    @Test
    fun `the committed document is the generated one`() {
        val file = File("openapi/screen.openapi.json")
        if (System.getenv("LUSTRO_UPDATE_OPENAPI") == "1") {
            file.parentFile?.mkdirs()
            file.writeText(document + "\n")
        }
        assertEquals("Run the tests with LUSTRO_UPDATE_OPENAPI=1 to write ${file.path}", file.readText(), document + "\n")
    }

    @Test
    fun `the generated document has the route, parameters, and schemas of the hand-written one`() {
        val json = JSONObject(document)
        assertEquals("1.0", json.getJSONObject("info").getString("version"))
        assertEquals("Lustro Screen debug tab", json.getJSONObject("info").getString("title"))
        val paths = json.getJSONObject("paths")
        assertEquals(listOf("/api/v1/screen/tree"), paths.keys().asSequence().toList())
        val item = paths.getJSONObject("/api/v1/screen/tree")
        assertEquals(listOf("get"), item.keys().asSequence().toList())
        val tree = item.getJSONObject("get")
        assertEquals("getScreenTree", tree.getString("operationId"))
        val parameters = tree.getJSONArray("parameters")
        assertEquals(2, parameters.length())
        // As the hand-written document had them, and an int is int32.
        assertParameter(parameters.getJSONObject(0), "merged", """{"type":"boolean","default":true}""")
        assertParameter(parameters.getJSONObject(1), "settle", """{"type":"integer","format":"int32","minimum":0,"maximum":10000,"default":0}""")
        val responses = tree.getJSONObject("responses")
        // The hand-written document listed 405, which the server answers for any route, and not 403, 413, and 503.
        assertEquals(listOf("200", "400", "401", "403", "413", "500", "503", "504"), responses.keys().asSequence().sorted().toList())
        assertEquals(
            "#/components/schemas/ScreenTree",
            responses.getJSONObject("200").getJSONObject("content").getJSONObject("application/json").getJSONObject("schema").getString("\$ref"),
        )
        assertEquals(
            setOf("ErrorEnvelope", "ScreenTree", "ScreenWindow", "ScreenNode", "Rect", "ScrollRange", "Settle"),
            json.getJSONObject("components").getJSONObject("schemas").keys().asSequence().toSet(),
        )
    }

    private fun assertParameter(parameter: JSONObject, name: String, schema: String) {
        assertEquals(name, parameter.getString("name"))
        assertEquals(name, "query", parameter.getString("in"))
        assertEquals(name, false, parameter.getBoolean("required"))
        assertEquals(name, members(JSONObject(schema)), members(parameter.getJSONObject("schema")))
    }

    // org.json keeps no member order.
    private fun members(json: JSONObject): Map<String, String> = json.keys().asSequence().associateWith { json.get(it).toString() }
}
