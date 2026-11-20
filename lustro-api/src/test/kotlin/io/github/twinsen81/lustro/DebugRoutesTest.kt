@file:OptIn(ExperimentalDebugRoutes::class)

package io.github.twinsen81.lustro

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
        // An empty int, long, or boolean takes its default, and without one is refused; an empty string is a string.
        assertEquals(200, get(tab, "list", "text" to "", "limit" to "")?.status)
        assertEquals(listOf("", 100, null, null), seen)
        for ((name, message) in listOf("from" to "from must be a whole number, was empty", "all" to "all must be true or false, was empty")) {
            val refused = get(tab, "list", name to "")!!
            assertEquals(name, 400, refused.status)
            assertEquals(name, refused.json().getString("field"))
            assertEquals(message, refused.json().getString("message"))
        }
    }

    @Test
    fun `a repeated parameter arrives as every value, from the query, a form, or a JSON array`() {
        val seen = ArrayList<Any?>()
        val tab =
            tab { routes ->
                routes.get("export") {
                    val ids = query.strings("ids")
                    val kinds = query.strings("kind") { values = listOf("http", "ws") }.withDefault(listOf("http"))
                    handle { call -> DebugResponse.ok("{}").also { seen.addAll(listOf(call[ids], call[kinds])) } }
                }
                routes.post("tag") {
                    acceptsForm = true
                    val tags = body.strings("tag") { minLength = 1 }.required()
                    handle { call -> DebugResponse.ok("{}").also { seen.add(call[tags]) } }
                }
            }

        assertEquals(200, get(tab, "export", "ids" to "a", "ids" to "b,c", "kind" to "ws", "kind" to "http")?.status)
        assertEquals(200, get(tab, "export", "ids" to "")?.status)
        assertEquals(200, get(tab, "export")?.status)
        assertEquals(listOf(listOf("a", "b,c"), listOf("ws", "http"), listOf(""), listOf("http"), null, listOf("http")), seen)
        val kind = get(tab, "export", "kind" to "ws", "kind" to "ftp")!!
        assertEquals(400, kind.status)
        assertEquals("kind", kind.json().getString("field"))
        seen.clear()

        val form = MediaType.parse("application/x-www-form-urlencoded")
        assertEquals(200, post(tab, "tag", "tag=a&tag=b", form)?.status)
        assertEquals(200, post(tab, "tag", """{"tag":["c"]}""")?.status)
        assertEquals(listOf(listOf("a", "b"), listOf("c")), seen)
        val cases =
            listOf(
                """{"tag":"c"}""" to "tag must be a JSON array of strings, was \"c\"",
                """{"tag":["c",1]}""" to "tag must be a JSON array of strings, has 1",
                """{"tag":[""]}""" to "tag must not be empty",
                """{}""" to "tag is required",
            )
        for ((body, message) in cases) {
            val response = post(tab, "tag", body)!!
            assertEquals(body, 400, response.status)
            assertEquals(body, "tag", response.json().getString("field"))
            assertEquals(body, message, response.json().getString("message"))
        }
    }

    @Test
    fun `a missing or wrong parameter gets a 400 that names it`() {
        val tab =
            tab { routes ->
                routes.get("list") {
                    query.string("file") { minLength = 1 }.required()
                    query.string("dir") { values = listOf("asc", "desc") }
                    query.int("limit") { min = 1; max = 500 }
                    query.int("page") { min = 1 }
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
                // An int's own limit is in the message only when the number passed it.
                listOf("file" to "f", "page" to "0") to ("page" to "page must be at least 1, was 0"),
                listOf("file" to "f", "page" to "99999999999") to ("page" to "page must be at most 2147483647, was 99999999999"),
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
                routes.get("items/{id}/body/{direction}") {
                    path.string("id")
                    val direction = path.string("direction") { values = listOf("request", "response") }
                    handle { call -> DebugResponse.ok("{}").also { seen += call[direction] } }
                }
            }

        assertEquals(200, get(tab, "items/42")?.status)
        assertEquals(200, get(tab, "items/new")?.status)
        assertEquals(200, get(tab, "events/7/payload")?.status)
        assertEquals(200, get(tab, "items/42/body/request")?.status)
        assertEquals(listOf<Any?>("42", "literal", 7L, "request"), seen)
        // A segment that its parameter does not take names nothing: no route, so the server answers 404.
        assertNull(get(tab, "events/x/payload"))
        assertNull(get(tab, "events/-1/payload"))
        assertNull(get(tab, "items/42/body/both"))
        // The method comes first, as in the server's lookup of the document.
        assertEquals("GET", tab.handle(DebugRequest("events/x/payload", "POST"))!!.headers.get("Allow"))
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
                """{"key":5}""" to ("key" to "key must be a JSON string, was 5"),
                """{"count":3}""" to ("key" to "key is required"),
                """{"key":"k","count":"3"}""" to ("count" to "count must be a whole number, was \"3\""),
                """{"key":"k","count":1.5}""" to ("count" to "count must be a whole number, was 1.5"),
                """{"key":"k","on":"true"}""" to ("on" to "on must be true or false, was \"true\""),
                """{"key":{"a":"${"x".repeat(50)}"}}""" to ("key" to "key must be a JSON string, was {\"a\":\"${"x".repeat(34)}..."),
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
                "names nothing in the schema Thing" to { routes ->
                    routes.component("Thing", """{"type": "object", "properties": {"a": {"type": "string"}}}""")
                    routes.get("a") {
                        response("A thing.", """{"${'$'}ref": "#/components/schemas/Thing/properties/b"}""")
                        handle { DebugResponse.ok("{}") }
                    }
                },
                "two responses" to { routes ->
                    routes.get("a") {
                        response("One.")
                        response("Two.")
                    }
                },
                "image/ is not a content type" to { routes ->
                    routes.get("a") { response("Bytes.") { content("image/") } }
                },
                "two responses of IMAGE/PNG" to { routes ->
                    routes.get("a") {
                        response("Bytes.") {
                            content("image/png")
                            content("IMAGE/PNG")
                        }
                    }
                },
                "two responses of application/json" to { routes ->
                    routes.get("a") { response("JSON.", "{}") { content("application/json") } }
                },
                "declare the Content-Type with content()" to { routes ->
                    routes.get("a") { response("Bytes.") { header("content-type", "The type.") } }
                },
                "two response headers named ETag" to { routes ->
                    routes.get("a") {
                        response("Bytes.") {
                            header("Etag", "The version.")
                            header("ETag", "The version.")
                        }
                    }
                },
                "the header X-Count is not valid JSON" to { routes ->
                    routes.get("a") {
                        response("Bytes.") { header("X-Count", "How many.", "{") }
                        handle { DebugResponse.ok("{}") }
                    }
                },
                "the default of ids is not a value that it takes: ids must not be empty" to { routes ->
                    routes.get("a") {
                        query.strings("ids") { minLength = 1 }.withDefault(listOf("a", ""))
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
                "the default of tags is not a value that it takes: unexpected character 'a' at character 2" to { routes ->
                    routes.post("a") {
                        body.json("tags").withDefault("[a]")
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
    fun `a failed declaration runs routes once, and every later call fails with it`() {
        var calls = 0
        val tab =
            object : DebugTab() {
                override val id = "broken"
                override val title = "Broken"
                override val icon = ""

                override fun routes(routes: DebugRoutes) {
                    calls++
                    routes.get("a") { handle { DebugResponse.ok("{}") } }
                }
            }
        val first = assertThrows(IllegalStateException::class.java) { tab.schema() }
        val again = assertThrows(IllegalStateException::class.java) { tab.handle(DebugRequest("a", "GET")) }
        assertSame(first, again.cause)
        assertTrue(again.message.orEmpty().contains("set routes.version"))
        assertEquals(1, calls)
    }

    @Test
    fun `a response can't be declared after its block`() {
        lateinit var kept: DebugResponseSpec
        val tab =
            tab { routes ->
                routes.get("a") {
                    response("Bytes.") { kept = this }
                    handle { DebugResponse.ok("{}") }
                }
            }
        tab.schema()
        assertThrows(IllegalStateException::class.java) { kept.content("text/plain") }
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
