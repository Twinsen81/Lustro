@file:OptIn(ExperimentalDebugRoutes::class)

package io.github.twinsen81.lustro

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenApiDocumentTest {
    private val tab =
        object : DebugTab() {
            override val id = "shop"
            override val title = "Shop"
            override val icon = ""

            override fun routes(routes: DebugRoutes) {
                routes.version = "2.1"
                routes.description = "The shop's items."
                routes.component("Item", """{"type": "object", "properties": {"id": {"type": "string"}}}""")
                routes.component("ItemList", """{"type": "array", "items": ${DebugRoutes.ref("Item")}}""")
                routes.get("items") {
                    summary = "List the items."
                    val sort = query.string("sort") { values = listOf("name", "price"); description = "The order." }
                    val limit = query.int("limit") { min = 1; max = 50 }.withDefault(20)
                    query.boolean("all")
                    response("The items.", DebugRoutes.ref("ItemList"))
                    handle { call -> DebugResponse.ok("${call[sort]}${call[limit]}") }
                }
                routes.get("items/{id}") {
                    operationId = "getItem"
                    path.string("id") { description = "The item's id." }
                    errors(404)
                    handle { DebugResponse.ok("{}") }
                }
                routes.post("items") {
                    acceptsForm = true
                    bodyDescription = "The item to add."
                    body.string("name") { minLength = 1; maxLength = 80; description = "The item's name." }.required()
                    body.long("price") { min = 0 }
                    body.boolean("hidden") { schema = """{"const": false}"""; description = "Must be false." }
                    handle { DebugResponse.ok("{}") }
                }
                routes.put("items/{id}/tags") {
                    path.long("id")
                    body.json("tags") { schema = """{"type": "array", "items": {"type": "string"}}""" }.required()
                    handle { DebugResponse.ok("{}") }
                }
                routes.post("import") {
                    rawBody("text/csv", """{"type": "string"}""")
                    handle { DebugResponse.ok("{}") }
                }
                routes.post("reset") { handle { DebugResponse.ok("{}") } }
            }
        }

    private val document = JSONObject(tab.schema()!!)

    @Test
    fun `the document has the info, the server, and the shared error envelope`() {
        assertEquals("3.1.0", document.getString("openapi"))
        val info = document.getJSONObject("info")
        assertEquals("Shop debug tab", info.getString("title"))
        assertEquals("2.1", info.getString("version"))
        assertEquals("The shop's items.", info.getString("description"))
        assertEquals("/", document.getJSONArray("servers").getJSONObject(0).getString("url"))
        val components = document.getJSONObject("components")
        assertEquals(
            "#/components/schemas/ErrorEnvelope",
            components.getJSONObject("responses").getJSONObject("Error").getJSONObject("content")
                .getJSONObject("application/json").getJSONObject("schema").getString("\$ref"),
        )
        assertEquals(listOf("ErrorEnvelope", "Item", "ItemList"), keys("components", "schemas"))
        assertEquals("#/components/schemas/Item", components.getJSONObject("schemas").getJSONObject("ItemList").getJSONObject("items").getString("\$ref"))
    }

    @Test
    fun `the paths are under the tab, in the order of the declarations`() {
        assertEquals(
            listOf("/api/v1/shop/items", "/api/v1/shop/items/{id}", "/api/v1/shop/items/{id}/tags", "/api/v1/shop/import", "/api/v1/shop/reset"),
            keys("paths"),
        )
        assertEquals(listOf("get", "post"), keys("paths", "/api/v1/shop/items"))
        assertEquals("getItems", operation("/api/v1/shop/items", "get").getString("operationId"))
        assertEquals("getItem", operation("/api/v1/shop/items/{id}", "get").getString("operationId"))
        assertEquals("putItemsByIdTags", operation("/api/v1/shop/items/{id}/tags", "put").getString("operationId"))
    }

    @Test
    fun `parameters carry their location, whether they are required, their schema, and their description`() {
        val list = operation("/api/v1/shop/items", "get")
        assertEquals("List the items.", list.getString("summary"))
        val parameters = list.getJSONArray("parameters")
        assertParameter(parameters.getJSONObject(0), "sort", "query", false, """{"type":"string","enum":["name","price"]}""", "The order.")
        assertParameter(parameters.getJSONObject(1), "limit", "query", false, """{"type":"integer","format":"int32","minimum":1,"maximum":50,"default":20}""", null)
        assertParameter(parameters.getJSONObject(2), "all", "query", false, """{"type":"boolean"}""", null)
        val item = operation("/api/v1/shop/items/{id}", "get").getJSONArray("parameters").getJSONObject(0)
        assertParameter(item, "id", "path", true, """{"type":"string"}""", "The item's id.")
    }

    @Test
    fun `body fields are an object schema, for JSON and a form when the route accepts one`() {
        val body = operation("/api/v1/shop/items", "post").getJSONObject("requestBody")
        assertTrue(body.getBoolean("required"))
        assertEquals("The item to add.", body.getString("description"))
        val content = body.getJSONObject("content")
        assertEquals(listOf("application/json", "application/x-www-form-urlencoded"), keys("paths", "/api/v1/shop/items", "post", "requestBody", "content"))
        val schema = content.getJSONObject("application/json").getJSONObject("schema")
        assertJson(
            """{"type":"object","required":["name"],"properties":{
              "name":{"type":"string","minLength":1,"maxLength":80,"description":"The item's name."},
              "price":{"type":"integer","format":"int64","minimum":0},
              "hidden":{"const":false,"description":"Must be false."}}}""",
            schema,
        )
        assertJson(schema.toString(), content.getJSONObject("application/x-www-form-urlencoded").getJSONObject("schema"))

        val tags = operation("/api/v1/shop/items/{id}/tags", "put").getJSONObject("requestBody").getJSONObject("content")
        assertEquals(listOf("application/json"), tags.keys().asSequence().toList())
        assertJson(
            """{"type":"object","required":["tags"],"properties":{"tags":{"type":"array","items":{"type":"string"}}}}""",
            tags.getJSONObject("application/json").getJSONObject("schema"),
        )
    }

    @Test
    fun `a raw body has its content type and schema`() {
        val body = operation("/api/v1/shop/import", "post").getJSONObject("requestBody")
        assertTrue(body.getBoolean("required"))
        assertJson("""{"type":"string"}""", body.getJSONObject("content").getJSONObject("text/csv").getJSONObject("schema"))
        assertFalse(operation("/api/v1/shop/reset", "post").has("requestBody"))
    }

    @Test
    fun `responses list the success, 400 for inputs, the server's statuses, and the declared errors`() {
        val list = operation("/api/v1/shop/items", "get").getJSONObject("responses")
        assertEquals(listOf("200", "400", "401", "403", "413", "503"), keys("paths", "/api/v1/shop/items", "get", "responses"))
        assertEquals("The items.", list.getJSONObject("200").getString("description"))
        assertEquals(
            "#/components/schemas/ItemList",
            list.getJSONObject("200").getJSONObject("content").getJSONObject("application/json").getJSONObject("schema").getString("\$ref"),
        )
        assertEquals("#/components/responses/Error", list.getJSONObject("400").getString("\$ref"))
        val item = operation("/api/v1/shop/items/{id}", "get").getJSONObject("responses")
        assertEquals(listOf("200", "400", "401", "403", "404", "413", "503"), keys("paths", "/api/v1/shop/items/{id}", "get", "responses"))
        val reset = operation("/api/v1/shop/reset", "post").getJSONObject("responses")
        assertEquals(listOf("200", "401", "403", "413", "503"), keys("paths", "/api/v1/shop/reset", "post", "responses"))
        assertEquals("Success.", reset.getJSONObject("200").getString("description"))
    }

    @Test
    fun `the document is built once and is the same string each time`() {
        assertSame(tab.schema(), tab.schema())
        assertTrue(tab.schema()!!.startsWith("{\n  \"openapi\": \"3.1.0\",\n  \"info\": {\n"))
    }

    @Test
    fun `the title can be set`() {
        val titled =
            object : DebugTab() {
                override val id = "t"
                override val title = "T"
                override val icon = ""

                override fun routes(routes: DebugRoutes) {
                    routes.title = "Own title"
                    routes.version = "1"
                    routes.get("a") { handle { DebugResponse.ok("{}") } }
                }
            }
        assertEquals("Own title", JSONObject(titled.schema()!!).getJSONObject("info").getString("title"))
    }

    private fun operations(path: String): JSONObject = document.getJSONObject("paths").getJSONObject(path)

    // The member order as the document writes it: org.json's JSONObject does not keep it.
    private fun keys(vararg path: String): List<String> =
        path.fold(Json.parse(tab.schema()!!)) { value, key -> (value as Map<*, *>)[key]!! }.let { (it as Map<*, *>).keys.map(Any?::toString) }

    private fun operation(path: String, method: String): JSONObject = operations(path).getJSONObject(method)

    private fun assertParameter(parameter: JSONObject, name: String, location: String, required: Boolean, schema: String, description: String?) {
        assertEquals(name, parameter.getString("name"))
        assertEquals(location, parameter.getString("in"))
        assertEquals(name, required, parameter.getBoolean("required"))
        assertJson(schema, parameter.getJSONObject("schema"))
        assertEquals(description, parameter.optString("description").takeIf { parameter.has("description") })
    }

    private fun assertJson(expected: String, actual: Any) {
        assertEquals(normal(JSONObject(expected)), normal(actual))
    }

    // org.json's toString() of an object does not keep the member order, so compare sorted.
    private fun normal(value: Any?): Any? =
        when (value) {
            is JSONObject -> value.keys().asSequence().sorted().associateWith { normal(value.get(it)) }
            is JSONArray -> (0 until value.length()).map { normal(value.get(it)) }
            else -> value
        }
}
