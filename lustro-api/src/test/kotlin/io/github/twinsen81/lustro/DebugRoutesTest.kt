@file:OptIn(ExperimentalDebugRoutes::class)

package io.github.twinsen81.lustro

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugRoutesTest {
    @Test
    fun `query parameters arrive with their types, defaults, and nulls`() {
        val seen = ArrayList<Any?>()
        val tab =
            tab { routes ->
                routes.get("list") {
                    val text = query.string("text")
                    val limit = query.int("limit") { min = 1; max = 500 }.withDefault(100)
                    val from = query.long("from")
                    val all = query.boolean("all")
                    handle { call ->
                        seen.addAll(listOf(call[text], call[limit], call[from], call[all]))
                        DebugResponse.ok("{}")
                    }
                }
            }

        assertEquals(200, get(tab, "list", "text" to "a b", "limit" to "7", "from" to "1792419740010", "all" to "true")?.status)
        assertEquals(listOf("a b", 7, 1_792_419_740_010L, true), seen)
        seen.clear()
        assertEquals(200, get(tab, "list")?.status)
        assertEquals(listOf(null, 100, null, null), seen)
        seen.clear()
        // An empty int, long, or boolean is absent; an empty string is a string.
        assertEquals(200, get(tab, "list", "text" to "", "limit" to "", "from" to "", "all" to "")?.status)
        assertEquals(listOf("", 100, null, null), seen)
    }

    @Test
    fun `a missing or wrong parameter gets a 400 that names it`() {
        val tab =
            tab { routes ->
                routes.get("list") {
                    query.string("file") { minLength = 1 }.required()
                    query.string("dir") { values = listOf("asc", "desc") }
                    query.int("limit") { min = 1; max = 500 }
                    query.boolean("all")
                    handle { DebugResponse.ok("{}") }
                }
            }
        val cases =
            listOf(
                listOf<Pair<String, String>>() to ("file" to "file is required"),
                listOf("file" to "") to ("file" to "file must not be empty"),
                listOf("file" to "f", "dir" to "up") to ("dir" to "dir must be one of asc, desc"),
                listOf("file" to "f", "limit" to "x") to ("limit" to "limit must be a whole number, was x"),
                listOf("file" to "f", "limit" to "0") to ("limit" to "limit must be from 1 to 500, was 0"),
                listOf("file" to "f", "limit" to "99999999999") to ("limit" to "limit must be from 1 to 500, was 99999999999"),
                listOf("file" to "f", "all" to "yes") to ("all" to "all must be true or false, was yes"),
            )
        for ((query, expected) in cases) {
            val response = get(tab, "list", *query.toTypedArray())!!
            assertEquals(query.toString(), 400, response.status)
            val error = response.json()
            assertEquals("bad_request", error.getString("error"))
            assertEquals(expected.first, error.getString("field"))
            assertEquals(expected.second, error.getString("message"))
        }
    }

    @Test
    fun `path parameters match one segment, and a literal segment wins`() {
        val seen = ArrayList<Any?>()
        val tab =
            tab { routes ->
                routes.get("items/{id}") {
                    val id = path.string("id")
                    handle { call -> DebugResponse.ok("{}").also { seen += call[id] } }
                }
                routes.get("items/new") { handle { DebugResponse.ok("{}").also { seen += "literal" } } }
                routes.get("events/{seq}/payload") {
                    val seq = path.long("seq") { min = 0 }
                    handle { call -> DebugResponse.ok("{}").also { seen += call[seq] } }
                }
            }

        assertEquals(200, get(tab, "items/42")?.status)
        assertEquals(200, get(tab, "items/new")?.status)
        assertEquals(200, get(tab, "events/7/payload")?.status)
        assertEquals(listOf<Any?>("42", "literal", 7L), seen)
        assertEquals("seq", get(tab, "events/x/payload")!!.json().getString("field"))
        assertEquals("seq", get(tab, "events/-1/payload")!!.json().getString("field"))
        assertNull(get(tab, "items"))
        assertNull(get(tab, "items/"))
        assertNull(get(tab, "items/1/2"))
    }

    @Test
    fun `a declared path with another method gets a 405 with Allow, in the server's format`() {
        val tab =
            tab { routes ->
                routes.post("items") { handle { DebugResponse.ok("{}") } }
                routes.get("items") { handle { DebugResponse.ok("{}") } }
                routes.delete("items/{id}") {
                    path.string("id")
                    handle { DebugResponse.ok("{}") }
                }
                routes.get("items/new") { handle { DebugResponse.ok("{}") } }
            }

        val wrong = tab.handle(DebugRequest("items", "PATCH"))!!
        assertEquals(405, wrong.status)
        assertEquals("GET, POST", wrong.headers.get("Allow"))
        assertEquals("method_not_allowed", wrong.json().getString("error"))
        assertEquals("t/items takes GET or POST, not PATCH", wrong.json().getString("message"))
        // The most literal path wins, whatever its methods.
        assertEquals("GET", tab.handle(DebugRequest("items/new", "DELETE"))!!.headers.get("Allow"))
        assertEquals(200, tab.handle(DebugRequest("items/7", "delete"))?.status)
    }

    @Test
    fun `body fields come from a JSON object, with strict types`() {
        val seen = ArrayList<Any?>()
        val tab =
            tab { routes ->
                routes.post("edit") {
                    val key = body.string("key").required()
                    val count = body.int("count")
                    val on = body.boolean("on")
                    val value = body.json("value")
                    handle { call ->
                        seen.addAll(listOf(call[key], call[count], call[on], call[value]))
                        DebugResponse.ok("{}")
                    }
                }
            }

        assertEquals(200, post(tab, "edit", """{"key":"k","count":3,"on":false,"value":["a",{"b":1.50}]}""")?.status)
        assertEquals(listOf("k", 3, false, """["a",{"b":1.50}]"""), seen)
        seen.clear()
        assertEquals(200, post(tab, "edit", """{"key":"k","count":null,"value":null,"extra":1}""")?.status)
        assertEquals(listOf("k", null, null, null), seen)

        val cases =
            listOf(
                """{"key":5}""" to ("key" to "key must be a JSON string"),
                """{"count":3}""" to ("key" to "key is required"),
                """{"key":"k","count":"3"}""" to ("count" to "count must be a whole number"),
                """{"key":"k","count":1.5}""" to ("count" to "count must be a whole number"),
                """{"key":"k","on":"true"}""" to ("on" to "on must be true or false"),
                "" to ("body" to "The body must be a JSON object"),
                "[1]" to ("body" to "The body is not a JSON object"),
                "{" to ("body" to "The body is not a JSON object: expected a member name at character 2"),
                "key=k" to ("body" to "The body is not a JSON object: unexpected character 'k' at character 1"),
            )
        for ((body, expected) in cases) {
            val response = post(tab, "edit", body)!!
            assertEquals(body, 400, response.status)
            assertEquals(body, expected.first, response.json().getString("field"))
            assertEquals(body, expected.second, response.json().getString("message"))
        }
    }

    @Test
    fun `a route that accepts a form reads a form body, and JSON otherwise`() {
        val seen = ArrayList<Any?>()
        val tab =
            tab { routes ->
                routes.post("toggle") {
                    acceptsForm = true
                    val id = body.string("id") { minLength = 1 }.required()
                    val on = body.boolean("on")
                    handle { call -> DebugResponse.ok("{}").also { seen.addAll(listOf(call[id], call[on])) } }
                }
            }

        assertEquals(200, post(tab, "toggle", "id=dark+mode&on=true", MediaType.parse("application/x-www-form-urlencoded"))?.status)
        assertEquals(200, post(tab, "toggle", """{"id":"offline"}""", null)?.status)
        assertEquals(listOf("dark mode", true, "offline", null), seen)
        val form = MediaType.parse("application/x-www-form-urlencoded")
        assertEquals("on", post(tab, "toggle", "id=a&on=1", form)!!.json().getString("field"))
        assertEquals("id", post(tab, "toggle", "on=true", form)!!.json().getString("field"))
        assertEquals("The body must be a JSON object or a form", post(tab, "toggle", "", form)!!.json().getString("message"))
    }

    @Test
    fun `an empty body is no fields when every field is optional`() {
        val tab =
            tab { routes ->
                routes.post("clear") {
                    val all = body.boolean("all").withDefault(false)
                    handle { call -> DebugResponse.ok("""{"all":${call[all]}}""") }
                }
            }
        assertEquals(false, post(tab, "clear", null)!!.json().getBoolean("all"))
        assertEquals(true, post(tab, "clear", """{"all":true}""")!!.json().getBoolean("all"))
    }

    @Test
    fun `a raw body needs a body, and the handler reads it`() {
        val tab =
            tab { routes ->
                routes.post("upload") {
                    rawBody("application/json", """{"type": "array"}""")
                    handle { call -> DebugResponse.ok("""{"length":${call.request.body!!.size}}""") }
                }
            }
        assertEquals(3, post(tab, "upload", "[1]")!!.json().getInt("length"))
        val empty = post(tab, "upload", null)!!
        assertEquals(400, empty.status)
        assertEquals("body", empty.json().getString("field"))
    }

    @Test
    fun `a handler ends the request with the response of a DebugResponseException`() {
        val tab =
            tab { routes ->
                routes.get("item") {
                    val name = query.string("name").required()
                    handle { call ->
                        if (call[name] != "known") throw DebugResponseException(DebugResponse.error("No item ${call[name]}", 404, field = "name"))
                        DebugResponse.ok("{}")
                    }
                }
            }
        val missing = get(tab, "item", "name" to "other")!!
        assertEquals(404, missing.status)
        assertEquals("name", missing.json().getString("field"))
        assertEquals(200, get(tab, "item", "name" to "known")?.status)
    }

    @Test
    fun `a handle of another route is a mistake`() {
        lateinit var other: DebugParam<String?>
        val tab =
            tab { routes ->
                routes.get("a") {
                    other = query.string("x")
                    handle { DebugResponse.ok("{}") }
                }
                routes.get("b") { handle { call -> DebugResponse.ok(call[other].orEmpty()) } }
            }
        assertThrows(IllegalArgumentException::class.java) { get(tab, "b") }
    }

    @Test
    fun `a tab without routes keeps handle and schema at null`() {
        val tab =
            object : DebugTab() {
                override val id = "plain"
                override val title = "Plain"
                override val icon = ""
            }
        assertNull(tab.handle(DebugRequest("anything", "GET")))
        assertNull(tab.schema())
    }

    @Test
    fun `routes runs once, and a tab can give its own document and route a path by hand`() {
        var calls = 0
        val tab =
            object : DebugTab() {
                override val id = "own"
                override val title = "Own"
                override val icon = ""

                override fun routes(routes: DebugRoutes) {
                    calls++
                    routes.version = "1.0"
                    routes.get("declared") { handle { DebugResponse.ok("""{"from":"declared"}""") } }
                }

                override fun schema(): String = """{"openapi":"3.1.0","paths":{}}"""

                override fun handle(request: DebugRequest): DebugResponse? =
                    if (request.path == "manual") DebugResponse.ok("""{"from":"manual"}""") else super.handle(request)
            }
        assertEquals("""{"openapi":"3.1.0","paths":{}}""", tab.schema())
        assertEquals("manual", tab.handle(DebugRequest("manual", "GET"))!!.json().getString("from"))
        assertEquals("declared", tab.handle(DebugRequest("declared", "GET"))!!.json().getString("from"))
        tab.handle(DebugRequest("declared", "GET"))
        assertEquals(1, calls)
    }

    @Test
    fun `a mistake in the declarations throws from the first call`() {
        val mistakes: List<Pair<String, (DebugRoutes) -> Unit>> =
            listOf(
                "set routes.version" to { routes ->
                    routes.version = null
                    routes.get("a") { handle { DebugResponse.ok("{}") } }
                },
                "two routes GET a" to { routes ->
                    routes.get("a") { handle { DebugResponse.ok("{}") } }
                    routes.get("a") { handle { DebugResponse.ok("{}") } }
                },
                "no handler" to { routes -> routes.get("a") {} },
                "declare the path parameter {id}" to { routes -> routes.get("a/{id}") { handle { DebugResponse.ok("{}") } } },
                "the path has no {id}" to { routes ->
                    routes.get("a") {
                        path.string("id")
                        handle { DebugResponse.ok("{}") }
                    }
                },
                "the server answers _schema itself" to { routes -> routes.get("_schema") { handle { DebugResponse.ok("{}") } } },
                "with no empty segment" to { routes -> routes.get("/a") { handle { DebugResponse.ok("{}") } } },
                "a GET route has no body" to { routes ->
                    routes.get("a") {
                        body.string("x")
                        handle { DebugResponse.ok("{}") }
                    }
                },
                "a form can't carry the JSON field v" to { routes ->
                    routes.post("a") {
                        acceptsForm = true
                        body.json("v")
                        handle { DebugResponse.ok("{}") }
                    }
                },
                "a raw body and body fields" to { routes ->
                    routes.post("a") {
                        rawBody("application/json", "{}")
                        body.string("x")
                        handle { DebugResponse.ok("{}") }
                    }
                },
                "two query parameters named x" to { routes ->
                    routes.get("a") {
                        query.string("x")
                        query.int("x")
                    }
                },
                "has a min above its max" to { routes ->
                    routes.get("a") {
                        query.int("x") { min = 5; max = 1 }
                        handle { DebugResponse.ok("{}") }
                    }
                },
                "is not valid JSON" to { routes -> routes.component("Thing", "{") },
                "names no schema" to { routes ->
                    routes.get("a") {
                        response("A thing.", DebugRoutes.ref("Thing"))
                        handle { DebugResponse.ok("{}") }
                    }
                },
                "is the shared error envelope" to { routes -> routes.component("ErrorEnvelope", "{}") },
                "two operations with the operationId same" to { routes ->
                    routes.get("a") {
                        operationId = "same"
                        handle { DebugResponse.ok("{}") }
                    }
                    routes.get("b") {
                        operationId = "same"
                        handle { DebugResponse.ok("{}") }
                    }
                },
                "is not an error status" to { routes -> routes.get("a") { errors(200) } },
                "items/{id} and items/{name} are the same path" to { routes ->
                    routes.get("items/{id}") {
                        path.string("id")
                        handle { DebugResponse.ok("{}") }
                    }
                    routes.delete("items/{name}") {
                        path.string("name")
                        handle { DebugResponse.ok("{}") }
                    }
                },
                "the default of limit is not a value that it takes: limit must be from 1 to 50, was 0" to { routes ->
                    routes.get("a") {
                        query.int("limit") { min = 1; max = 50 }.withDefault(0)
                        handle { DebugResponse.ok("{}") }
                    }
                },
                "the default of dir is not a value that it takes" to { routes ->
                    routes.get("a") {
                        query.string("dir") { values = listOf("asc", "desc") }.withDefault("up")
                        handle { DebugResponse.ok("{}") }
                    }
                },
            )
        for ((message, declare) in mistakes) {
            val tab =
                tab { routes ->
                    declare(routes)
                    if (routes.declared.isEmpty() && routes.components.isNotEmpty()) routes.get("z") { handle { DebugResponse.ok("{}") } }
                }
            val thrown = assertThrows(message, IllegalStateException::class.java) { tab.schema() }
            assertTrue("$message: ${thrown.message}", thrown.message.orEmpty().contains(message))
        }
    }

    @Test
    fun `routes can't be declared after the tab read them, and routes must not read the tab`() {
        lateinit var kept: DebugRoutes
        val tab =
            tab { routes ->
                kept = routes
                routes.get("a") { handle { DebugResponse.ok("{}") } }
            }
        tab.schema()
        assertThrows(IllegalStateException::class.java) { kept.get("b") { handle { DebugResponse.ok("{}") } } }

        val reentrant =
            object : DebugTab() {
                override val id = "loop"
                override val title = "Loop"
                override val icon = ""

                override fun routes(routes: DebugRoutes) {
                    schema()
                }
            }
        assertThrows(IllegalStateException::class.java) { reentrant.schema() }
    }

    private fun tab(declare: (DebugRoutes) -> Unit): DebugTab =
        object : DebugTab() {
            override val id = "t"
            override val title = "Test"
            override val icon = ""

            override fun routes(routes: DebugRoutes) {
                routes.version = "1.0"
                declare(routes)
            }
        }

    private fun get(tab: DebugTab, path: String, vararg query: Pair<String, String>): DebugResponse? =
        tab.handle(DebugRequest(path, "GET", queryParams = query.groupBy({ it.first }, { it.second })))

    private fun post(tab: DebugTab, path: String, body: String?, type: MediaType? = MediaType.JSON): DebugResponse? =
        tab.handle(DebugRequest(path, "POST", body = body?.toByteArray(), contentType = type))

    private fun DebugResponse.json(): JSONObject = JSONObject(String(body, Charsets.UTF_8))
}
