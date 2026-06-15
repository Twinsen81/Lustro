package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.MediaType
import io.github.twinsen81.lustro.network.CapturedBody
import io.github.twinsen81.lustro.network.MockRule
import io.github.twinsen81.lustro.network.MockRuleStorage
import io.github.twinsen81.lustro.network.NetworkCaptureSink
import io.github.twinsen81.lustro.network.NetworkClassifier
import io.github.twinsen81.lustro.network.Redactor
import io.github.twinsen81.lustro.network.TransactionId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Thread-safe in-memory store for captured HTTP transactions and mock rules.
 * It IMPLEMENTS the [NetworkCaptureSink] SPI so HTTP-client adapters report
 * into it.
 *
 * Notable behavior:
 * - Mock rules persist through an injected [MockRuleStorage] instead of
 *   direct SharedPreferences.
 * - The transaction ring cap comes from `DebugConfig.maxCaptureTransactions`.
 * - The [Redactor] is applied at capture time so stored values are
 *   pre-redacted; URLs are classified via the injected [NetworkClassifier].
 * - A monotonic [sequence] backs the opaque polling cursor.
 *
 * Uses ConcurrentHashMap for O(1) lookups/updates and a ConcurrentLinkedDeque
 * for reverse-chronological ordering. Updates via computeIfPresent preserve
 * insertion order (no remove-and-reinsert).
 */
internal class NetworkTrafficStore(
    maxTransactions: Int,
    private val redactor: Redactor,
    private val classifier: NetworkClassifier,
    private val storage: MockRuleStorage?,
    captureBudgetBytes: Long = DEFAULT_CAPTURE_BUDGET_BYTES,
) : NetworkCaptureSink {
    // Mutable so the runtime can push DebugConfig.maxCaptureTransactions into the
    // tab's store after construction (the built-in default is set by the factory).
    // A new smaller cap trims on the next recordRequest.
    @Volatile
    var maxTransactions: Int = maxTransactions

    // Total in-memory capture budget (request+response body bytes). Mutable so the
    // runtime can push DebugConfig.captureBudgetBytes in via applyConfig; a new
    // smaller budget evicts on the next capture (see [trimToBudget]).
    @Volatile
    var captureBudgetBytes: Long = captureBudgetBytes
    private val transactionMap = ConcurrentHashMap<String, NetworkTransaction>()
    private val insertionOrder = ConcurrentLinkedDeque<String>()
    private val mockRules = ConcurrentHashMap<String, MockRuleImpl>()
    private val sequence = AtomicLong(0)
    private val paused = AtomicBoolean(false)
    private val overwriteMode = AtomicBoolean(false)
    private val throttleDelayMs = AtomicInteger(0)

    // Running total of captured body bytes (sum of request+response body byte
    // sizes across every retained transaction). Kept in lock-step with the map by
    // [recordRequest]/[updateWithResponse]/eviction so [trimToBudget] never walks
    // the whole map. Guarded by [captureLock] together with the byte total.
    private val capturedBytes = AtomicLong(0)
    private val captureLock = Any()

    init {
        storage?.load()?.forEach { rule -> mockRules[rule.id] = rule.toImpl() }
    }

    // TransactionId's constructor/value are @RestrictTo(LIBRARY_GROUP); :lustro and
    // :lustro-api share the io.github.twinsen81 group, so these calls are legitimate.
    // Android Lint's RestrictedApi check can't resolve the group across local project
    // modules, so suppress it precisely at the sink boundary rather than project-wide.
    @Suppress("RestrictedApi")
    override fun beginRequest(
        url: String,
        method: String,
        headers: Headers,
        requestBody: CapturedBody?,
        contentType: MediaType?,
    ): TransactionId {
        val id = UUID.randomUUID().toString()
        val redactedUrl = redactor.redactUrl(url)
        // Redact the captured text in place; truncation/byte-size flags are
        // preserved verbatim from the CapturedBody so badges/sizes stay accurate.
        val bodyText = requestBody?.text?.let { redactor.redactBody(it, contentType) }
        val transaction =
            NetworkTransaction(
                id = id,
                timestamp = System.currentTimeMillis(),
                method = method,
                url = redactedUrl,
                categories = safeClassify(redactedUrl),
                requestHeaders = redactHeaders(headers),
                requestBody = bodyText,
                requestBodyTruncated = requestBody?.truncated ?: false,
                requestContentType = contentType?.toString(),
                requestBodyBytes = requestBody?.byteSize,
            )
        recordRequest(transaction)
        return TransactionId(id)
    }

    override fun findMockRule(url: String, method: String): MockRule? =
        mockRules.values.firstOrNull { it.matches(url, method) }

    @Suppress("RestrictedApi") // id.value is @RestrictTo(LIBRARY_GROUP); same-group call (see beginRequest).
    override fun completeRequest(
        id: TransactionId,
        statusCode: Int,
        responseHeaders: Headers,
        responseBody: CapturedBody?,
        durationMs: Long,
        isMocked: Boolean,
        complete: Boolean,
    ) {
        val contentType = MediaType.parse(responseHeaders.get("Content-Type").orEmpty())
        val bodyText = responseBody?.text?.let { redactor.redactBody(it, contentType) }
        updateWithResponse(
            id = id.value,
            statusCode = statusCode,
            durationMs = durationMs,
            responseHeaders = redactHeaders(responseHeaders),
            responseBody = bodyText,
            responseBodyTruncated = responseBody?.truncated ?: false,
            responseContentType = contentType?.toString(),
            responseBodyBytes = responseBody?.byteSize,
            responseComplete = complete,
            isMocked = isMocked,
        )
    }

    @Suppress("RestrictedApi") // id.value is @RestrictTo(LIBRARY_GROUP); same-group call (see beginRequest).
    override fun failRequest(id: TransactionId, durationMs: Long, error: String) {
        updateWithError(id.value, durationMs, error)
    }

    fun recordRequest(transaction: NetworkTransaction) {
        if (overwriteMode.get()) {
            evictPriorCompletedSamePath(transaction)
        }
        transactionMap[transaction.id] = transaction
        insertionOrder.addFirst(transaction.id)
        capturedBytes.addAndGet(transactionBytes(transaction))
        trimToMaxSize()
        trimToBudget()
        sequence.incrementAndGet()
    }

    private fun evictPriorCompletedSamePath(incoming: NetworkTransaction) {
        val identity = identityKey(incoming.method, incoming.url)
        // Snapshot the matching entries first so we don't mutate the map while
        // iterating it. We evict every completed match, not just the first — when
        // overwrite mode is toggled on after duplicates have already piled up, a
        // single eviction would leave older copies behind on the next request.
        val victims =
            transactionMap.values
                .filter { existing ->
                    (existing.responseComplete || existing.error != null) &&
                        identityKey(existing.method, existing.url) == identity
                }
        for (victim in victims) {
            // Atomic key+value remove guards against a concurrent updateWithResponse
            // mutating this entry between our filter and the remove call. We only
            // want to drop the exact snapshot we matched on.
            if (transactionMap.remove(victim.id, victim)) {
                insertionOrder.remove(victim.id)
                capturedBytes.addAndGet(-transactionBytes(victim))
            }
        }
    }

    /**
     * Body bytes a single transaction contributes to the capture budget: the
     * request body plus the response body. Uses the recorded byte counts when
     * present (they reflect the true on-the-wire size even when the stored text
     * was truncated) and falls back to the UTF-8 length of the retained text.
     */
    private fun transactionBytes(tx: NetworkTransaction): Long {
        val request = tx.requestBodyBytes ?: tx.requestBody?.let { utf8Len(it) } ?: 0L
        val response = tx.responseBodyBytes ?: tx.responseBody?.let { utf8Len(it) } ?: 0L
        return request + response
    }

    private fun utf8Len(text: String): Long = text.toByteArray(Charsets.UTF_8).size.toLong()

    private fun identityKey(method: String, url: String): String {
        val path = url.toHttpUrlOrNull()?.encodedPath ?: url
        return "${method.uppercase()} $path"
    }

    fun updateWithResponse(
        id: String,
        statusCode: Int,
        durationMs: Long,
        requestHeaders: Map<String, String> = emptyMap(),
        responseHeaders: Map<String, String>,
        responseBody: String?,
        responseBodyTruncated: Boolean,
        responseContentType: String?,
        responseBodyBytes: Long? = null,
        responseComplete: Boolean = true,
        isMocked: Boolean = false,
    ) {
        transactionMap.computeIfPresent(id) { _, tx ->
            val updated =
                tx.copy(
                    statusCode = statusCode,
                    durationMs = durationMs,
                    requestHeaders = requestHeaders.ifEmpty { tx.requestHeaders },
                    responseHeaders = responseHeaders,
                    responseBody = responseBody,
                    responseBodyTruncated = responseBodyTruncated,
                    responseContentType = responseContentType,
                    responseBodyBytes = responseBodyBytes,
                    responseComplete = responseComplete,
                    isMocked = isMocked,
                )
            // The response body now counts toward the budget; reconcile the delta
            // atomically inside computeIfPresent so concurrent updates can't race.
            capturedBytes.addAndGet(transactionBytes(updated) - transactionBytes(tx))
            updated
        }
        trimToBudget()
        sequence.incrementAndGet()
    }

    fun updateWithError(id: String, durationMs: Long, error: String) {
        transactionMap.computeIfPresent(id) { _, tx ->
            tx.copy(durationMs = durationMs, responseComplete = true, error = error)
        }
        sequence.incrementAndGet()
    }

    private fun redactHeaders(headers: Headers): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        headers.forEach { name, value ->
            val redacted = redactor.redactHeaderValue(name, value)
            // Join duplicate header values (e.g. multiple Set-Cookie).
            val existing = out[name]
            out[name] = if (existing != null) "$existing, $redacted" else redacted
        }
        return out
    }

    private fun safeClassify(url: String): List<String> =
        try {
            classifier.classify(url)
        } catch (_: Exception) {
            emptyList()
        }

    fun getTransactions(search: String? = null): List<NetworkTransaction> {
        var result: Sequence<NetworkTransaction> =
            insertionOrder.asSequence()
                .mapNotNull { transactionMap[it] }
        if (!search.isNullOrBlank()) {
            val lower = search.lowercase()
            result =
                result.filter { tx ->
                    tx.url.lowercase().contains(lower) ||
                        tx.method.lowercase().contains(lower) ||
                        tx.requestBody?.lowercase()?.contains(lower) == true ||
                        tx.responseBody?.lowercase()?.contains(lower) == true
                }
        }
        return result.toList()
    }

    fun getTransaction(id: String): NetworkTransaction? = transactionMap[id]

    fun addMockRule(rule: MockRuleImpl) {
        mockRules[rule.id] = rule
        persistRules()
        sequence.incrementAndGet()
    }

    fun removeMockRule(id: String) {
        mockRules.remove(id)
        persistRules()
        sequence.incrementAndGet()
    }

    fun toggleMockRule(id: String) {
        mockRules.computeIfPresent(id) { _, rule -> rule.copy(enabled = !rule.enabled) }
        persistRules()
        sequence.incrementAndGet()
    }

    fun replaceMockRules(rules: List<MockRuleImpl>) {
        // Avoid a transient empty window: putAll the new set first (overwriting
        // any existing entries with matching ids), then retain only those keys
        // so dropped rules are removed last. The interceptor never sees a
        // moment with zero rules, just briefly sees the union.
        val newRules = rules.associateBy { it.id }
        mockRules.putAll(newRules)
        mockRules.keys.retainAll(newRules.keys)
        persistRules()
        sequence.incrementAndGet()
    }

    fun getMockRules(): List<MockRuleImpl> = mockRules.values.toList()

    fun incrementHitCount(ruleId: String) {
        mockRules.computeIfPresent(ruleId) { _, rule ->
            rule.copy(hitCount = rule.hitCount + 1)
        }
        sequence.incrementAndGet()
    }

    private fun persistRules() {
        storage?.save(mockRules.values.toList())
    }

    fun isPaused(): Boolean = paused.get()

    fun setPaused(value: Boolean) {
        paused.set(value)
        sequence.incrementAndGet()
    }

    fun isOverwriteMode(): Boolean = overwriteMode.get()

    fun setOverwriteMode(value: Boolean) {
        overwriteMode.set(value)
        sequence.incrementAndGet()
    }

    fun getThrottleDelayMs(): Int = throttleDelayMs.get()

    fun setThrottleDelayMs(value: Int) {
        throttleDelayMs.set(value.coerceAtLeast(0))
        sequence.incrementAndGet()
    }

    fun getSequence(): Long = sequence.get()

    fun clear() {
        transactionMap.clear()
        insertionOrder.clear()
        capturedBytes.set(0)
        sequence.incrementAndGet()
    }

    /** Current total of captured body bytes (request+response). Exposed for tests. */
    fun capturedBytes(): Long = capturedBytes.get()

    private fun trimToMaxSize() {
        while (insertionOrder.size > maxTransactions) {
            if (evictOneOldest() == null) break
        }
    }

    /**
     * Evicts the oldest transactions until the running [capturedBytes] total is
     * within [captureBudgetBytes] (in addition to the [maxTransactions] ring cap).
     * Serialized under [captureLock] so two concurrent captures cannot both walk
     * the deque and over-evict. We keep at least one transaction so a single body
     * larger than the whole budget still appears (truncation already bounds it).
     */
    private fun trimToBudget() {
        synchronized(captureLock) {
            while (capturedBytes.get() > captureBudgetBytes && insertionOrder.size > 1) {
                if (evictOneOldest() == null) break
            }
        }
    }

    /**
     * Evicts one entry under the cap/budget pressure and returns its id, or `null`
     * when nothing could be removed. PREFERS the oldest COMPLETED entry
     * ([NetworkTransaction.responseComplete] or [NetworkTransaction.error]) so a
     * long in-flight stream that has drifted to the oldest position is never
     * evicted out from under its pending completion. Only when EVERY retained
     * entry is still in-flight do we fall back to evicting the oldest overall
     * (safety valve against unbounded growth). Removes the id from both the deque
     * and the map and reconciles the byte total.
     */
    private fun evictOneOldest(): String? {
        val victimId = oldestCompletedId() ?: insertionOrder.peekLast() ?: return null
        if (!insertionOrder.remove(victimId)) return null
        transactionMap.remove(victimId)?.let { capturedBytes.addAndGet(-transactionBytes(it)) }
        return victimId
    }

    /**
     * Returns the id of the oldest COMPLETED transaction (walking the deque from
     * the tail, which holds the oldest entries), or `null` when no retained entry
     * has completed yet.
     */
    private fun oldestCompletedId(): String? =
        insertionOrder.descendingIterator().asSequence().firstOrNull { id ->
            transactionMap[id]?.let { it.responseComplete || it.error != null } == true
        }

    private fun MockRule.toImpl(): MockRuleImpl =
        this as? MockRuleImpl
            ?: MockRuleImpl(
                id = id,
                enabled = enabled,
                name = name,
                urlPattern = urlPattern,
                method = method,
                statusCode = statusCode,
                responseHeaders = responseHeaders,
                responseBody = responseBody,
                hitCount = hitCount,
            )

    private companion object {
        // The built-in default; the runtime overrides this with DebugConfig
        // .captureBudgetBytes via applyConfig so create() works standalone.
        private const val DEFAULT_CAPTURE_BUDGET_BYTES = 50L * 1024 * 1024
    }
}
