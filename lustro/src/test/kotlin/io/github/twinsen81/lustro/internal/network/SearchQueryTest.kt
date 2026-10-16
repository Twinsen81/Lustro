package io.github.twinsen81.lustro.internal.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The search syntax and what each kind of term matches. The parse cases are in
 * `search-queries.json`, which the console's JavaScript tests read too, so the
 * two parsers can't drift apart.
 */
class SearchQueryTest {
    private val fixture = JSONObject(javaClass.getResource("/search-queries.json")!!.readText())

    private val budget get() = SearchBudget(60_000)

    private fun tx(
        url: String = "https://example.com/v1/podcasts/featured.json",
        method: String = "GET",
        statusCode: Int? = 200,
        requestHeaders: Map<String, String> = mapOf("User-Agent" to "PocketCasts/7.0"),
        responseHeaders: Map<String, String>? = mapOf("Cache-Control" to "no-cache", "Set-Cookie" to "[REDACTED]"),
        requestBody: String? = null,
        responseBody: String? = """{"podcast_list":[{"title":"Ärger"}]}""",
        finalUrl: String? = null,
    ): NetworkTransaction =
        NetworkTransaction(
            id = "1",
            startedAt = 0,
            method = method,
            url = url,
            statusCode = statusCode,
            requestHeaders = requestHeaders,
            responseHeaders = responseHeaders,
            requestBody = requestBody,
            responseBody = responseBody,
            finalUrl = finalUrl,
        )

    private fun matches(query: String, tx: NetworkTransaction = tx()): Boolean = SearchQuery.parse(query).matches(tx, budget)

    @Test
    fun `parses each fixture query as the fixture says`() {
        val cases = fixture.getJSONArray("parse")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val query = case.getString("query")
            val parsed = SearchQuery.parse(query, fields = case.optBoolean("fields", true))
            assertEquals(query, case.getString("terms"), parsed.describe())
        }
    }

    @Test
    fun `rejects each fixture error`() {
        val errors = fixture.getJSONArray("errors")
        for (i in 0 until errors.length()) {
            assertParseError(errors.getString(i))
        }
    }

    @Test
    fun `a regex that doesn't compile is an error that says why`() {
        for (query in listOf("/feat(/", "regex:[a", "url:/(/", "-regex:*")) {
            val message = assertParseError(query)
            assertTrue(message, message.startsWith("The regex /"))
            assertTrue(message, message.contains(" is not valid: "))
        }
    }

    @Test
    fun `a status that isn't a code says how to search for the text`() {
        assertEquals(
            "status: takes a code such as 404 or a class such as 4xx, not \"ok\". " +
                "To search for the text, put all of it in quotes: \"status:ok\"",
            assertParseError("status:ok"),
        )
    }

    @Test
    fun `plain text finds what a substring search found`() {
        assertTrue(matches("podcasts/featured"))
        assertTrue(matches("FEATURED.JSON"))
        assertTrue(matches("\"podcast_list\""))
        assertTrue(matches("get"))
        assertTrue(matches("ärger"))
        assertFalse(matches("popular"))
    }

    @Test
    fun `spaces mean and, a bar or OR means or, and a minus means not`() {
        assertTrue(matches("featured json"))
        assertFalse(matches("featured popular"))
        assertTrue(matches("popular | featured"))
        assertTrue(matches("popular OR featured"))
        assertFalse(matches("popular | trending"))
        assertTrue(matches("podcasts -analytics"))
        assertFalse(matches("podcasts -featured"))
        assertTrue(matches("-analytics"))
        assertFalse(matches("NOT featured"))
        // The bar binds closer than the space.
        assertTrue(matches("podcasts popular | featured"))
        assertFalse(matches("analytics popular | featured"))
    }

    @Test
    fun `a regex is found anywhere and ignores case`() {
        assertTrue(matches("/featured\\.JSON$/"))
        assertTrue(matches("regex:v\\d+/podcasts"))
        assertFalse(matches("/v\\d{2}/"))
        assertTrue(matches("/ärger/"))
        assertTrue(matches("/ÄRGER/"))
    }

    @Test
    fun `search finds the headers by name, by value, and as the line Name colon value`() {
        assertTrue(matches("cache-control"))
        assertTrue(matches("no-cache"))
        assertTrue(matches("\"Cache-Control: no-cache\""))
        assertTrue(matches("pocketcasts/7"))
        // The redacted value is what is stored, so it is what search finds.
        assertTrue(matches("[REDACTED]"))
        assertTrue(matches("header:/^set-cookie:\\s\\[redacted]$/"))
        assertFalse(matches("max-age"))
        assertTrue(matches("cache-control", tx(responseHeaders = null, requestHeaders = mapOf("Cache-Control" to "x"))))
    }

    @Test
    fun `a field prefix looks at that field only`() {
        val tx =
            tx(
                url = "https://example.com/start",
                finalUrl = "https://cdn.example.com/feed",
                requestBody = "query=start",
                responseBody = "the feed",
            )
        assertTrue(matches("url:cdn.example", tx))
        assertTrue(matches("url:start", tx))
        assertFalse(matches("url:query", tx))
        assertTrue(matches("body:query", tx))
        assertFalse(matches("body:cdn", tx))
        assertTrue(matches("header:no-cache", tx))
        assertFalse(matches("header:feed", tx))
        assertTrue(matches("-body:cdn", tx))
        assertFalse(matches("-url:feed", tx))
    }

    @Test
    fun `method and status name the whole value, and x in a status is any digit`() {
        val post = tx(method = "POST", statusCode = 404)
        assertTrue(matches("method:post", post))
        assertFalse(matches("method:pos", post))
        assertTrue(matches("method:/^p/", post))
        assertTrue(matches("status:404", post))
        assertTrue(matches("status:4xx", post))
        assertTrue(matches("status:40X", post))
        assertFalse(matches("status:5xx", post))
        assertTrue(matches("status:2xx | status:4xx", post))
        assertTrue(matches("-status:2xx", post))
        // Plain text doesn't look at the status.
        assertFalse(matches("404", post))
    }

    @Test
    fun `a transaction without a status matches no status term, and every negated one`() {
        val pending = tx(statusCode = null)
        assertFalse(matches("status:2xx", pending))
        assertFalse(matches("status:/./", pending))
        assertTrue(matches("-status:2xx", pending))
    }

    @Test
    fun `a query of operators alone matches everything`() {
        for (query in listOf("|", "OR", "NOT", "AND |")) {
            val parsed = SearchQuery.parse(query)
            assertTrue(query, parsed.isEmpty)
        }
    }

    @Test
    fun `a message search treats a field prefix as text, and an entry without text never matches`() {
        val query = SearchQuery.parse("status:ok", fields = false)
        assertTrue(query.matchesText("{\"status:ok\"}", budget))
        assertFalse(query.matchesText("status: ok", budget))
        assertFalse(SearchQuery.parse("-ping", fields = false).matchesText(null, budget))
        assertTrue(SearchQuery.parse("-ping", fields = false).matchesText("pong", budget))
    }

    @Test
    fun `a search stops when it passes its budget, or its thread is interrupted`() {
        val query = SearchQuery.parse("/featured/")
        try {
            query.matches(tx(), SearchBudget(0))
            fail("no timeout")
        } catch (e: SearchTimeoutException) {
            assertTrue(e.message!!, e.message!!.startsWith("The search took longer than 0 s and stopped."))
        }
        Thread.currentThread().interrupt()
        try {
            query.matches(tx(), budget)
            fail("no timeout")
        } catch (_: SearchTimeoutException) {
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `a transaction remembers its result for the last query`() {
        val tx = tx()
        val query = SearchQuery.parse("featured")
        assertTrue(tx.matchesSearch(query, budget))
        // A budget that has passed would throw if the cached result weren't used.
        assertTrue(tx.matchesSearch(SearchQuery.parse("featured"), SearchBudget(0)))
        assertFalse(tx.matchesSearch(SearchQuery.parse("popular"), budget))
    }

    private fun assertParseError(query: String): String =
        try {
            SearchQuery.parse(query)
            fail("parsed: $query")
            ""
        } catch (e: SearchQueryException) {
            e.message!!
        }
}
