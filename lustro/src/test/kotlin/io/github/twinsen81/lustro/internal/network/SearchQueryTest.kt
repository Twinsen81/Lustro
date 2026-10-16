package io.github.twinsen81.lustro.internal.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** What the plain search and the regex search find in a transaction, and when a search can't run. */
class SearchQueryTest {
    private val budget get() = SearchBudget(60_000)

    private fun tx(
        url: String = "https://example.com/v1/podcasts/featured.json",
        method: String = "GET",
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
            requestHeaders = requestHeaders,
            responseHeaders = responseHeaders,
            requestBody = requestBody,
            responseBody = responseBody,
            finalUrl = finalUrl,
        )

    private fun matches(text: String, regex: Boolean = false, tx: NetworkTransaction = tx()): Boolean =
        SearchQuery.parse(text, regex).matches(tx, budget)

    @Test
    fun `plain text is found as typed, ignoring case, with no syntax`() {
        assertTrue(matches("podcasts/featured"))
        assertTrue(matches("FEATURED.JSON"))
        assertTrue(matches("\"podcast_list\""))
        assertTrue(matches("get"))
        assertTrue(matches("ärger"))
        assertFalse(matches("popular"))
        // Characters that mean something in a regex are text here.
        assertFalse(matches("featured|popular"))
        assertFalse(matches("v\\d"))
        assertTrue(matches("/v1/"))
        assertTrue(matches("featured.json", tx = tx(url = "https://example.com/featured.json")))
        assertFalse(matches("featured.json", tx = tx(url = "https://example.com/featuredXjson")))
    }

    @Test
    fun `a regex is found anywhere in a field and ignores case`() {
        assertTrue(matches("featured|popular", regex = true))
        assertTrue(matches("v\\d+/podcasts", regex = true))
        assertTrue(matches("\\.JSON$", regex = true))
        assertFalse(matches("v\\d{2}/", regex = true))
        assertTrue(matches("ÄRGER", regex = true))
        assertTrue(matches("^get$", regex = true))
    }

    @Test
    fun `search finds the headers by name, by value, and as the line Name colon value`() {
        for (regex in listOf(false, true)) {
            assertTrue(matches("cache-control", regex))
            assertTrue(matches("no-cache", regex))
            assertTrue(matches("pocketcasts/7", regex))
            assertFalse(matches("max-age", regex))
        }
        assertTrue(matches("Cache-Control: no-cache"))
        assertTrue(matches("^set-cookie: \\[redacted]$", regex = true))
        // The redacted value is what is stored, so it is what search finds.
        assertTrue(matches("[REDACTED]"))
        assertTrue(matches("cache-control", tx = tx(responseHeaders = null, requestHeaders = mapOf("Cache-Control" to "x"))))
    }

    @Test
    fun `search finds the final URL and both bodies`() {
        val tx = tx(url = "https://example.com/start", finalUrl = "https://cdn.example.com/feed", requestBody = "query=start", responseBody = null)
        assertTrue(matches("cdn.example", tx = tx))
        assertTrue(matches("query=", tx = tx))
        assertTrue(matches("^query", regex = true, tx = tx))
    }

    @Test
    fun `a regex that doesn't compile is an error on the search field that says why`() {
        for (text in listOf("feat(", "[a", "*")) {
            try {
                SearchQuery.parse(text, regex = true)
                fail("compiled: $text")
            } catch (e: SearchQueryException) {
                assertTrue(e.message!!, e.message!!.startsWith("The regex is not valid: "))
                assertEquals("search", e.field)
            }
        }
        // As plain text, the same characters are just text.
        assertFalse(matches("feat("))
    }

    @Test
    fun `the parameters choose the search, and a blank one leaves nothing out`() {
        assertNull(SearchQuery.fromParams(null, null))
        assertNull(SearchQuery.fromParams("  ", "true"))
        assertFalse(SearchQuery.fromParams("a|b", null)!!.regex)
        assertFalse(SearchQuery.fromParams("a|b", "false")!!.regex)
        assertTrue(SearchQuery.fromParams("a|b", "true")!!.regex)
        try {
            SearchQuery.fromParams("a", "yes")
            fail("accepted regex=yes")
        } catch (e: SearchQueryException) {
            assertEquals("regex", e.field)
        }
    }

    @Test
    fun `a message search looks at the text, and an entry without text never matches`() {
        assertTrue(SearchQuery.parse("PING").matchesText("{\"type\":\"ping\"}", budget))
        assertTrue(SearchQuery.parse("^\\{.*ping", regex = true).matchesText("{\"type\":\"ping\"}", budget))
        assertFalse(SearchQuery.parse("ping").matchesText(null, budget))
    }

    @Test
    fun `a search stops when it passes its budget, or its thread is interrupted`() {
        val query = SearchQuery.parse("featured", regex = true)
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
    fun `a transaction remembers its result for the last search, text and mode`() {
        val tx = tx()
        assertFalse(tx.matchesSearch(SearchQuery.parse("featured|popular"), budget))
        // The same text as a regex is another search, so it is matched again.
        assertTrue(tx.matchesSearch(SearchQuery.parse("featured|popular", regex = true), budget))
        // A budget that has passed would throw if the cached result weren't used.
        assertTrue(tx.matchesSearch(SearchQuery.parse("featured|popular", regex = true), SearchBudget(0)))
    }
}
