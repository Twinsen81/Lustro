package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.MediaType
import io.github.twinsen81.lustro.network.CapturedBody
import io.github.twinsen81.lustro.network.DefaultRedactor
import io.github.twinsen81.lustro.network.MockRule
import io.github.twinsen81.lustro.network.MockRuleStorage
import io.github.twinsen81.lustro.network.NetworkClassifier
import io.github.twinsen81.lustro.network.NoOpNetworkClassifier
import io.github.twinsen81.lustro.network.Redactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the internal [NetworkTrafficStore]: mock-rule CRUD/sync, overwrite
 * eviction, pause (capture-only), throttle/pause state, classifier categories,
 * redaction-at-capture, and the monotonic sequence that backs the polling cursor.
 *
 * Pure JVM (no Base64/SharedPrefs), so plain JUnit4.
 */
class NetworkTrafficStoreTest {
    private object IdentityRedactor : Redactor {
        override fun redactUrl(url: String): String = url

        override fun redactHeaderValue(name: String, value: String): String = value

        override fun redactBody(body: String, contentType: MediaType?): String = body
    }

    private fun store(
        redactor: Redactor = IdentityRedactor,
        classifier: NetworkClassifier = NoOpNetworkClassifier,
        maxTransactions: Int = 1000,
        storage: MockRuleStorage? = null,
        captureBudgetBytes: Long = 50L * 1024 * 1024,
    ): NetworkTrafficStore =
        NetworkTrafficStore(
            maxTransactions = maxTransactions,
            redactor = redactor,
            classifier = classifier,
            storage = storage,
            captureBudgetBytes = captureBudgetBytes,
        )

    /** Helper: wrap a text body in a [CapturedBody] (full size = its UTF-8 bytes). */
    private fun captured(text: String): CapturedBody =
        CapturedBody(text = text, truncated = false, byteSize = text.toByteArray(Charsets.UTF_8).size.toLong())

    private fun rule(
        id: String,
        urlPattern: String = "example.com",
        method: String? = null,
        enabled: Boolean = true,
    ) = MockRuleImpl(id = id, name = id, urlPattern = urlPattern, method = method, enabled = enabled)

    // region sequence / cursor backing

    @Test
    fun `beginRequest advances the sequence`() {
        val store = store()
        val before = store.getSequence()
        store.beginRequest("https://example.com/a", "GET", Headers.EMPTY, null, null)
        assertTrue(store.getSequence() > before)
    }

    @Test
    fun `completeRequest advances the sequence`() {
        val store = store()
        val id = store.beginRequest("https://example.com/a", "GET", Headers.EMPTY, null, null)
        val after = store.getSequence()
        store.completeRequest(id, 200, Headers.EMPTY, captured("hi"), 5, isMocked = false)
        assertTrue(store.getSequence() > after)
    }

    @Test
    fun `state mutations each advance the sequence`() {
        val store = store()
        var seq = store.getSequence()
        store.setPaused(true)
        assertTrue(store.getSequence() > seq); seq = store.getSequence()
        store.setOverwriteMode(true)
        assertTrue(store.getSequence() > seq); seq = store.getSequence()
        store.setThrottleDelayMs(500)
        assertTrue(store.getSequence() > seq); seq = store.getSequence()
        store.clear()
        assertTrue(store.getSequence() > seq)
    }

    // endregion

    // region capture + ordering

    @Test
    fun `transactions are returned in reverse-chronological order`() {
        val store = store()
        store.beginRequest("https://example.com/first", "GET", Headers.EMPTY, null, null)
        store.beginRequest("https://example.com/second", "GET", Headers.EMPTY, null, null)
        val urls = store.getTransactions().map { it.url }
        assertEquals(listOf("https://example.com/second", "https://example.com/first"), urls)
    }

    @Test
    fun `getTransactions search filters by url, method and body`() {
        val store = store()
        store.beginRequest("https://example.com/users", "GET", Headers.EMPTY, null, null)
        store.beginRequest(
            "https://example.com/posts",
            "POST",
            Headers.EMPTY,
            captured("needle"),
            MediaType.TEXT,
        )

        assertEquals(1, store.getTransactions(search = "users").size)
        assertEquals(1, store.getTransactions(search = "POST").size)
        assertEquals(1, store.getTransactions(search = "needle").size)
        assertEquals(2, store.getTransactions(search = null).size)
    }

    @Test
    fun `ring buffer evicts oldest beyond max size`() {
        val store = store(maxTransactions = 2)
        store.beginRequest("https://example.com/1", "GET", Headers.EMPTY, null, null)
        store.beginRequest("https://example.com/2", "GET", Headers.EMPTY, null, null)
        store.beginRequest("https://example.com/3", "GET", Headers.EMPTY, null, null)
        val urls = store.getTransactions().map { it.url }
        assertEquals(listOf("https://example.com/3", "https://example.com/2"), urls)
    }

    @Test
    fun `ring cap eviction skips the oldest in-flight transaction and evicts an older completed one`() {
        val store = store(maxTransactions = 2)
        // /1 is the OLDEST and stays in-flight (a long stream). /2 completes. /3
        // pushes over the cap: the oldest COMPLETED (/2) must be evicted, not the
        // older-but-in-flight /1.
        val inFlight = store.beginRequest("https://example.com/1", "GET", Headers.EMPTY, null, null)
        val completed = store.beginRequest("https://example.com/2", "GET", Headers.EMPTY, null, null)
        store.completeRequest(completed, 200, Headers.EMPTY, captured("done"), 5, isMocked = false)

        store.beginRequest("https://example.com/3", "GET", Headers.EMPTY, null, null)

        assertNull("the completed entry is the eviction victim", store.getTransaction(completed.value))
        assertNotNull("the in-flight oldest entry survives", store.getTransaction(inFlight.value))

        // Its later completion still lands (it was not evicted out from under it).
        store.updateWithResponse(
            id = inFlight.value,
            statusCode = 200,
            durationMs = 7,
            responseHeaders = emptyMap(),
            responseBody = "late",
            responseBodyTruncated = false,
            responseContentType = null,
            responseComplete = true,
        )
        val landed = store.getTransaction(inFlight.value)
        assertNotNull(landed)
        assertEquals(200, landed!!.statusCode)
        assertEquals("late", landed.responseBody)
    }

    @Test
    fun `ring cap eviction falls back to the oldest when everything is in-flight`() {
        // Safety valve: with no completed entry to evict, the cap must still bound
        // growth by dropping the oldest in-flight entry.
        val store = store(maxTransactions = 2)
        val oldest = store.beginRequest("https://example.com/1", "GET", Headers.EMPTY, null, null)
        store.beginRequest("https://example.com/2", "GET", Headers.EMPTY, null, null)
        store.beginRequest("https://example.com/3", "GET", Headers.EMPTY, null, null)

        assertEquals(2, store.getTransactions().size)
        assertNull("oldest in-flight dropped as the last resort", store.getTransaction(oldest.value))
    }

    // endregion

    // region capture budget

    @Test
    fun `capture budget evicts oldest transactions once the byte total is exceeded`() {
        // 250-byte budget; each captured body is 100 bytes (request) so 3 bodies =
        // 300 bytes pushes over budget and the oldest is evicted down to 2 retained.
        val store = store(captureBudgetBytes = 250)
        val body = "x".repeat(100)
        store.beginRequest("https://example.com/1", "POST", Headers.EMPTY, captured(body), MediaType.TEXT)
        store.beginRequest("https://example.com/2", "POST", Headers.EMPTY, captured(body), MediaType.TEXT)
        assertEquals(2, store.getTransactions().size)
        assertEquals(200L, store.capturedBytes())

        store.beginRequest("https://example.com/3", "POST", Headers.EMPTY, captured(body), MediaType.TEXT)
        // Oldest (/1) evicted; total back under budget.
        val urls = store.getTransactions().map { it.url }
        assertEquals(listOf("https://example.com/3", "https://example.com/2"), urls)
        assertEquals(200L, store.capturedBytes())
    }

    @Test
    fun `capture budget counts response bodies and evicts on response completion`() {
        val store = store(captureBudgetBytes = 250)
        val body = "y".repeat(100)
        // Three request-only transactions cost 0 bytes; budget untouched.
        val id1 = store.beginRequest("https://example.com/1", "GET", Headers.EMPTY, null, null)
        val id2 = store.beginRequest("https://example.com/2", "GET", Headers.EMPTY, null, null)
        val id3 = store.beginRequest("https://example.com/3", "GET", Headers.EMPTY, null, null)
        assertEquals(0L, store.capturedBytes())

        // Attaching 100-byte response bodies one at a time: after the third the total
        // (300) exceeds the 250 budget and the oldest transaction is evicted.
        store.completeRequest(id1, 200, Headers.EMPTY, captured(body), 5, isMocked = false)
        store.completeRequest(id2, 200, Headers.EMPTY, captured(body), 5, isMocked = false)
        store.completeRequest(id3, 200, Headers.EMPTY, captured(body), 5, isMocked = false)

        assertTrue("total must be held within budget", store.capturedBytes() <= 250)
        assertTrue("at least one transaction evicted", store.getTransactions().size < 3)
        // The oldest (/1) is the eviction victim.
        assertNull(store.getTransaction(id1.value))
    }

    @Test
    fun `clear resets the captured byte total`() {
        val store = store(captureBudgetBytes = 1000)
        store.beginRequest("https://example.com/1", "POST", Headers.EMPTY, captured("z".repeat(50)), MediaType.TEXT)
        assertEquals(50L, store.capturedBytes())
        store.clear()
        assertEquals(0L, store.capturedBytes())
    }

    @Test
    fun `a single body larger than the whole budget is retained (not infinitely evicted)`() {
        val store = store(captureBudgetBytes = 10)
        store.beginRequest("https://example.com/big", "POST", Headers.EMPTY, captured("q".repeat(500)), MediaType.TEXT)
        // Keeps at least one transaction even though it alone exceeds the budget.
        assertEquals(1, store.getTransactions().size)
    }

    // endregion

    // region overwrite-mode eviction

    @Test
    fun `overwrite mode evicts prior completed same method and path`() {
        val store = store()
        store.setOverwriteMode(true)
        val firstId =
            store.beginRequest("https://example.com/data?x=1", "GET", Headers.EMPTY, null, null)
        store.completeRequest(firstId, 200, Headers.EMPTY, captured("old"), 5, isMocked = false)

        // Same method + path (query differs) -> the prior completed one is evicted.
        store.beginRequest("https://example.com/data?x=2", "GET", Headers.EMPTY, null, null)

        val txs = store.getTransactions()
        assertEquals(1, txs.size)
        assertEquals("https://example.com/data?x=2", txs.single().url)
    }

    @Test
    fun `overwrite mode does not evict in-flight requests`() {
        val store = store()
        store.setOverwriteMode(true)
        // First request never completes (in-flight).
        store.beginRequest("https://example.com/data", "GET", Headers.EMPTY, null, null)
        store.beginRequest("https://example.com/data", "GET", Headers.EMPTY, null, null)

        assertEquals(2, store.getTransactions().size)
    }

    @Test
    fun `overwrite mode does not evict different path`() {
        val store = store()
        store.setOverwriteMode(true)
        val id = store.beginRequest("https://example.com/a", "GET", Headers.EMPTY, null, null)
        store.completeRequest(id, 200, Headers.EMPTY, null, 5, isMocked = false)
        store.beginRequest("https://example.com/b", "GET", Headers.EMPTY, null, null)
        assertEquals(2, store.getTransactions().size)
    }

    // endregion

    // region pause (capture-only) + throttle state

    @Test
    fun `pause state toggles and reports`() {
        val store = store()
        assertFalse(store.isPaused())
        store.setPaused(true)
        assertTrue(store.isPaused())
    }

    @Test
    fun `pause is capture-only - mock matching still works while paused`() {
        // Pause lives in the store but the store still answers findMockRule; the
        // interceptor gates capture on !isPaused. Here we assert the store keeps
        // serving mocks regardless of paused state.
        val store = store()
        store.addMockRule(rule(id = "r", urlPattern = "example.com/x"))
        store.setPaused(true)
        assertEquals("r", store.findMockRule("https://example.com/x", "GET")?.id)
    }

    @Test
    fun `overwrite mode state toggles and reports`() {
        val store = store()
        assertFalse(store.isOverwriteMode())
        store.setOverwriteMode(true)
        assertTrue(store.isOverwriteMode())
    }

    @Test
    fun `throttle state clamps negatives to zero`() {
        val store = store()
        store.setThrottleDelayMs(750)
        assertEquals(750, store.getThrottleDelayMs())
        store.setThrottleDelayMs(-5)
        assertEquals(0, store.getThrottleDelayMs())
    }

    // endregion

    // region mock rules CRUD + atomic replace + matching

    @Test
    fun `add, toggle, and delete mock rules`() {
        val store = store()
        store.addMockRule(rule(id = "r1"))
        assertEquals(listOf("r1"), store.getMockRules().map { it.id })
        assertTrue(store.getMockRules().single().enabled)

        store.toggleMockRule("r1")
        assertFalse(store.getMockRules().single().enabled)

        store.removeMockRule("r1")
        assertTrue(store.getMockRules().isEmpty())
    }

    @Test
    fun `replaceMockRules atomically swaps the rule set`() {
        val store = store()
        store.addMockRule(rule(id = "old1"))
        store.addMockRule(rule(id = "old2"))
        store.replaceMockRules(listOf(rule(id = "new1"), rule(id = "old1")))
        assertEquals(setOf("new1", "old1"), store.getMockRules().map { it.id }.toSet())
    }

    @Test
    fun `findMockRule respects enabled, method and substring or regex pattern`() {
        val store = store()
        store.addMockRule(rule(id = "disabled", urlPattern = "example.com", enabled = false))
        assertNull(store.findMockRule("https://example.com/x", "GET"))

        store.replaceMockRules(
            listOf(
                rule(id = "substr", urlPattern = "/users"),
                MockRuleImpl(id = "post-only", name = "p", urlPattern = "/posts", method = "POST"),
                MockRuleImpl(id = "rx", name = "r", urlPattern = "regex:.*/v2/.*"),
            ),
        )
        assertEquals("substr", store.findMockRule("https://api/users/1", "GET")?.id)
        assertNull(store.findMockRule("https://api/posts", "GET")) // method mismatch
        assertEquals("post-only", store.findMockRule("https://api/posts", "POST")?.id)
        assertEquals("rx", store.findMockRule("https://api/v2/items", "GET")?.id)
    }

    @Test
    fun `an invalid regex pattern is a stable no-match`() {
        // The pattern is compiled once and the failure cached; matching must never
        // throw and must consistently miss across repeated lookups.
        val store = store()
        store.addMockRule(MockRuleImpl(id = "bad", name = "b", urlPattern = "regex:[unterminated"))
        assertNull(store.findMockRule("https://api/anything", "GET"))
        assertNull(store.findMockRule("https://api/anything", "GET"))
    }

    @Test
    fun `incrementHitCount bumps the matching rule`() {
        val store = store()
        store.addMockRule(rule(id = "r1"))
        store.incrementHitCount("r1")
        store.incrementHitCount("r1")
        assertEquals(2, store.getMockRules().single().hitCount)
    }

    @Test
    fun `incrementHitCount advances the cursor so a poller observes a delta`() {
        val store = store()
        store.addMockRule(rule(id = "r1"))
        val before = store.getSequence()
        store.incrementHitCount("r1")
        // A poller holding the prior cursor must now see a change, not "unchanged".
        assertTrue(store.getSequence() > before)
    }

    @Test
    fun `mock mutations persist through storage seam`() {
        val storage = InMemoryMockRuleStorage()
        val store = store(storage = storage)
        store.addMockRule(rule(id = "r1"))
        assertEquals(listOf("r1"), storage.saved.map { it.id })

        store.replaceMockRules(listOf(rule(id = "a"), rule(id = "b")))
        assertEquals(setOf("a", "b"), storage.saved.map { it.id }.toSet())

        store.removeMockRule("a")
        assertEquals(setOf("b"), storage.saved.map { it.id }.toSet())
    }

    @Test
    fun `rules are loaded from storage at construction`() {
        val storage = InMemoryMockRuleStorage()
        storage.saved = listOf(rule(id = "persisted"))
        val store = store(storage = storage)
        assertEquals(listOf("persisted"), store.getMockRules().map { it.id })
    }

    // endregion

    // region classifier + redaction at capture

    @Test
    fun `classifier categories are applied to captured transactions`() {
        val classifier = NetworkClassifier { url -> if ("sync" in url) listOf("sync", "api") else emptyList() }
        val store = store(classifier = classifier)
        store.beginRequest("https://example.com/sync/pull", "GET", Headers.EMPTY, null, null)
        assertEquals(listOf("sync", "api"), store.getTransactions().single().categories)
    }

    @Test
    fun `a throwing classifier is tolerated and yields no categories`() {
        val classifier = NetworkClassifier { error("boom") }
        val store = store(classifier = classifier)
        store.beginRequest("https://example.com/x", "GET", Headers.EMPTY, null, null)
        assertEquals(emptyList<String>(), store.getTransactions().single().categories)
    }

    @Test
    fun `redaction is applied at capture - stored values are already redacted`() {
        // Real DefaultRedactor wired into the store.
        val store = store(redactor = DefaultRedactor)
        val headers =
            Headers.Builder()
                .add("Authorization", "Bearer super-secret-token")
                .add("Accept", "application/json")
                .build()
        val id =
            store.beginRequest(
                url = "https://example.com/login?access_token=LEAKED&page=1",
                method = "POST",
                headers = headers,
                requestBody = captured("""{"password":"hunter2","user":"alice"}"""),
                contentType = MediaType.JSON,
            )
        store.completeRequest(
            id = id,
            statusCode = 200,
            responseHeaders = Headers.of("Set-Cookie" to "sid=abc123"),
            responseBody = captured("""{"token":"RESPSECRET","ok":true}"""),
            durationMs = 12,
            isMocked = false,
        )

        val tx = store.getTransaction(id.value)!!
        // URL query token masked.
        assertTrue(tx.url.contains("access_token=%5BREDACTED%5D"))
        assertTrue(tx.url.contains("page=1"))
        assertFalse(tx.url.contains("LEAKED"))
        // Authorization header redacted; non-sensitive header preserved.
        assertEquals("[REDACTED]", tx.requestHeaders["Authorization"])
        assertEquals("application/json", tx.requestHeaders["Accept"])
        // Request JSON field redacted.
        val requestBody = tx.requestBody!!
        assertTrue(requestBody.contains("[REDACTED]"))
        assertFalse(requestBody.contains("hunter2"))
        assertTrue(requestBody.contains("alice"))
        // Response Set-Cookie header + response JSON token redacted.
        assertEquals("[REDACTED]", tx.responseHeaders!!["Set-Cookie"])
        val responseBody = tx.responseBody!!
        assertTrue(responseBody.contains("[REDACTED]"))
        assertFalse(responseBody.contains("RESPSECRET"))
    }

    // endregion

    /** Simple in-memory [MockRuleStorage] fake. */
    private class InMemoryMockRuleStorage : MockRuleStorage {
        var saved: List<MockRule> = emptyList()

        override fun load(): List<MockRule> = saved

        override fun save(rules: List<MockRule>) {
            saved = rules
        }
    }
}
