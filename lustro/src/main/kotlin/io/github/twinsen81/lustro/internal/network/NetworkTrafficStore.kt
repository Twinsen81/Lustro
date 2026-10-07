@file:Suppress("TooGenericExceptionCaught")

package io.github.twinsen81.lustro.internal.network

import android.util.Log
import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.MediaType
import io.github.twinsen81.lustro.network.CapturedBody
import io.github.twinsen81.lustro.network.CapturedResponse
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
import kotlin.random.Random
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.utf8Size

/**
 * Thread-safe in-memory store for captured HTTP transactions and mock rules.
 * It IMPLEMENTS the [NetworkCaptureSink] SPI so HTTP-client adapters report
 * into it.
 *
 * Notable behavior:
 * - Mock rules persist through an injected [MockRuleStorage] instead of
 *   direct SharedPreferences.
 * - The transaction ring cap comes from `DebugConfig.maxCaptureTransactions`.
 * - Capture calls return right away: redaction with the [Redactor],
 *   classification with the [NetworkClassifier], and storing run later on a
 *   [CaptureWorker]. A transaction is stored only once it's redacted.
 * - A body reported as [CapturedBody.bytes] is stored as it arrived: the
 *   [Redactor] reads text only.
 * - The polling cursor pairs the transaction list's change [sequence] with
 *   this store's random [epoch]. Only list changes advance the sequence:
 *   control state goes out with every poll, and rules have their own route.
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
    private val worker: CaptureWorker = CaptureWorker(),
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

    /** Tells this store's polling cursors apart from those of earlier stores, e.g. before an app restart. */
    val epoch: Long = Random.nextLong()

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

    // Hands out NetworkTransaction.startOrder, so captures stored out of order
    // can still be told apart by which request started first.
    private val startOrder = AtomicLong(0)

    // Bumped by clear() under captureLock, so a request that started before a
    // clear is never stored after it.
    @Volatile
    private var clearCount = 0L

    // Stream progress still waiting for the worker, by transaction id.
    private val pendingProgress = ConcurrentHashMap<String, CapturedResponse>()

    init {
        // A stored rule is checked again on the way in: any MockRuleStorage can
        // hand back a rule the interceptor could not build a response from, and
        // such a rule would throw inside the app's own call every time it matched.
        storage?.load()?.forEach { rule ->
            val rejection = MockRuleCodec.validate(rule)
            if (rejection == null) {
                mockRules[rule.id] = rule.toImpl()
            } else {
                Log.w(TAG, "Ignoring stored mock rule '${rule.id}': ${rejection.message}")
            }
        }
    }

    // TransactionId's constructor/value are @RestrictTo(LIBRARY_GROUP); :lustro and
    // :lustro-api share the io.github.twinsen81 group, so these calls are legitimate.
    // Android Lint's RestrictedApi check can't resolve the group across local project
    // modules, so suppress it precisely at the sink boundary rather than project-wide.
    // The OkHttp interceptor reports to the store itself. The other adapters
    // report through sinkFor, or name their source.
    override fun beginRequest(
        url: String,
        method: String,
        headers: Headers,
        requestBody: CapturedBody?,
        contentType: MediaType?,
    ): TransactionId = beginRequest(url, method, headers, requestBody, contentType, CaptureSource.OKHTTP)

    @Suppress("RestrictedApi")
    fun beginRequest(
        url: String,
        method: String,
        headers: Headers,
        requestBody: CapturedBody?,
        contentType: MediaType?,
        source: CaptureSource,
    ): TransactionId {
        val id = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        val order = startOrder.incrementAndGet()
        val clears = clearCount
        worker.submit(id, requestBody.backlogWeight()) {
            val redactedUrl = redactor.redactUrl(url)
            val transaction =
                NetworkTransaction(
                    id = id,
                    startedAt = startedAt,
                    startOrder = order,
                    method = method,
                    url = redactedUrl,
                    categories = safeClassify(redactedUrl),
                    requestHeaders = redactHeaders(headers),
                    // Redact the captured text in place; truncation/byte-size flags are
                    // preserved verbatim from the CapturedBody so badges/sizes stay accurate.
                    requestBody = requestBody?.text?.let { redactBody(it, contentType) },
                    requestBinaryBody = requestBody.binaryBytes(),
                    requestBodyTruncated = requestBody?.truncated ?: false,
                    requestContentType = contentType?.let { redactContentType(it) },
                    requestBodyBytes = requestBody?.byteSize,
                    source = source,
                )
            synchronized(captureLock) {
                if (clearCount == clears) recordRequest(transaction)
            }
        }
        return TransactionId(id)
    }

    /** This store as the adapter for [source] reports to it: each request it begins is from [source]. */
    fun sinkFor(source: CaptureSource): NetworkCaptureSink =
        object : NetworkCaptureSink by this {
            override fun beginRequest(
                url: String,
                method: String,
                headers: Headers,
                requestBody: CapturedBody?,
                contentType: MediaType?,
            ): TransactionId = this@NetworkTrafficStore.beginRequest(url, method, headers, requestBody, contentType, source)
        }

    override fun findMockRule(url: String, method: String): MockRule? =
        mockRules.values.firstOrNull { it.matches(url, method) }

    @Suppress("RestrictedApi") // id.value is @RestrictTo(LIBRARY_GROUP); same-group call (see beginRequest).
    override fun completeRequest(id: TransactionId, response: CapturedResponse) {
        val key = id.value
        if (response.isComplete) {
            // Supersedes any stream progress still waiting to be recorded. The
            // completion time is read now: the capture thread can run behind.
            pendingProgress.remove(key)
            submitResponse(key, response, completedAt = System.currentTimeMillis()) { response }
        } else if (pendingProgress.put(key, response) == null) {
            // A stream reports progress on every read. The queued update records
            // whatever progress is latest when it runs, so at most one waits per stream.
            submitResponse(key, response, completedAt = null) { pendingProgress.remove(key) }
        }
    }

    @Suppress("RestrictedApi") // id.value is @RestrictTo(LIBRARY_GROUP); same-group call (see beginRequest).
    override fun failRequest(id: TransactionId, durationMs: Long, error: String) {
        val completedAt = System.currentTimeMillis() // Now, as in completeRequest.
        worker.submit(id.value, 0L) { updateWithError(id.value, durationMs, error, completedAt) }
    }

    /**
     * Records that the interceptor holds the request [id] for [delayMs] before
     * sending it. Queued behind the request's own capture, so it finds it stored.
     */
    @Suppress("RestrictedApi") // id.value is @RestrictTo(LIBRARY_GROUP); same-group call (see beginRequest).
    fun recordThrottle(id: TransactionId, delayMs: Long) {
        worker.submit(id.value, 0L) {
            val updated = transactionMap.computeIfPresent(id.value) { _, tx -> tx.copy(throttledMs = delayMs) }
            if (updated != null) sequence.incrementAndGet()
        }
    }

    /**
     * Records the [body] of the request [id], which began with its size only
     * because the adapter captures the body as it is sent. Queued behind the
     * request's own capture, so it finds it stored.
     */
    @Suppress("RestrictedApi") // id.value is @RestrictTo(LIBRARY_GROUP); same-group call (see beginRequest).
    fun recordRequestBody(id: TransactionId, body: CapturedBody, contentType: MediaType?) {
        worker.submit(id.value, body.backlogWeight()) {
            val updated =
                transactionMap.computeIfPresent(id.value) { _, tx ->
                    val updated =
                        tx.copy(
                            requestBody = body.text?.let { redactBody(it, contentType) },
                            requestBinaryBody = body.binaryBytes(),
                            requestBodyTruncated = body.truncated,
                            requestBodyBytes = body.byteSize,
                        )
                    capturedBytes.addAndGet(transactionBytes(updated) - transactionBytes(tx))
                    updated
                }
            val evicted = trimToBudget()
            if (updated != null || evicted) sequence.incrementAndGet()
        }
    }

    /** Waits up to [timeoutMs] until the captures reported so far are stored. For tests. */
    fun awaitCaptures(timeoutMs: Long = 5_000L): Boolean = worker.awaitIdle(timeoutMs)

    private fun submitResponse(
        id: String,
        response: CapturedResponse,
        completedAt: Long?,
        take: () -> CapturedResponse?,
    ) {
        worker.submit(id, response.body.backlogWeight()) {
            take()?.let { recordResponse(id, it, completedAt) }
        }
    }

    private fun recordResponse(id: String, response: CapturedResponse, completedAt: Long?) {
        val contentType = MediaType.parse(response.headers.get(CONTENT_TYPE).orEmpty())
        updateWithResponse(
            id = id,
            statusCode = response.statusCode,
            durationMs = response.durationMs,
            responseHeaders = redactHeaders(response.headers),
            responseBody = response.body?.text?.let { redactBody(it, contentType) },
            responseBinaryBody = response.body.binaryBytes(),
            responseBodyTruncated = response.body?.truncated ?: false,
            responseContentType = contentType?.let { redactContentType(it) },
            responseBodyBytes = response.body?.byteSize,
            responseComplete = response.isComplete,
            completedAt = completedAt,
            protocol = response.protocol,
            isMocked = response.isMocked,
            finalUrl = response.finalUrl?.let { redactor.redactUrl(it) },
            priorResponses = response.priorResponses.map { PriorResponse(redactor.redactUrl(it.url), it.statusCode) },
        )
    }

    fun recordRequest(transaction: NetworkTransaction) {
        if (overwriteMode.get()) {
            // A capture taken on the calling thread is stored while an earlier one is
            // still waiting for the capture thread. Overwrite mode keeps the request
            // that started last, so this one is stale and isn't stored at all.
            if (supersededSamePath(transaction)) return
            evictPriorCompletedSamePath(transaction)
        }
        transactionMap[transaction.id] = transaction
        insertionOrder.addFirst(transaction.id)
        capturedBytes.addAndGet(transactionBytes(transaction))
        trimToMaxSize()
        trimToBudget()
        sequence.incrementAndGet()
    }

    /** Whether a completed capture of the same method and path started after [incoming]. */
    private fun supersededSamePath(incoming: NetworkTransaction): Boolean {
        val identity = identityKey(incoming.method, incoming.url)
        return transactionMap.values.any { existing ->
            (existing.responseComplete || existing.error != null) &&
                existing.startOrder > incoming.startOrder &&
                identityKey(existing.method, existing.url) == identity
        }
    }

    private fun evictPriorCompletedSamePath(incoming: NetworkTransaction) {
        val identity = identityKey(incoming.method, incoming.url)
        // Snapshot the matching entries first so we don't mutate the map while
        // iterating it. We evict every completed match, not just the first — when
        // overwrite mode is toggled on after duplicates have already piled up, a
        // single eviction would leave older copies behind on the next request.
        // Only requests that started EARLIER are evicted: a capture taken on the
        // calling thread while the capture thread is behind is stored before
        // earlier ones, and must not drop the newer request it finds there.
        val victims =
            transactionMap.values
                .filter { existing ->
                    (existing.responseComplete || existing.error != null) &&
                        existing.startOrder < incoming.startOrder &&
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
     * request body plus the response body, as kept. The budget bounds memory, so
     * a body counts what the store holds of it, never its size on the wire: a
     * 60 MB download whose bytes aren't kept costs nothing, a truncated body
     * costs its kept prefix, and a compressed body kept inflated costs the
     * inflated text.
     */
    private fun transactionBytes(tx: NetworkTransaction): Long =
        keptBytes(tx.requestBody, tx.requestBinaryBody) + keptBytes(tx.responseBody, tx.responseBinaryBody)

    private fun keptBytes(text: String?, bytes: ByteArray?): Long = text?.utf8Size() ?: bytes?.size?.toLong() ?: 0L

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
        completedAt: Long? = null,
        protocol: String? = null,
        isMocked: Boolean = false,
        responseBinaryBody: ByteArray? = null,
        finalUrl: String? = null,
        priorResponses: List<PriorResponse> = emptyList(),
    ) {
        val updated =
            transactionMap.computeIfPresent(id) { _, tx ->
                val updated =
                    tx.copy(
                        statusCode = statusCode,
                        durationMs = durationMs,
                        requestHeaders = requestHeaders.ifEmpty { tx.requestHeaders },
                        responseHeaders = responseHeaders,
                        responseBody = responseBody,
                        responseBinaryBody = responseBinaryBody,
                        responseBodyTruncated = responseBodyTruncated,
                        responseContentType = responseContentType,
                        responseBodyBytes = responseBodyBytes,
                        responseComplete = responseComplete,
                        completedAt = completedAt,
                        protocol = protocol,
                        isMocked = isMocked,
                        finalUrl = finalUrl,
                        priorResponses = priorResponses,
                    )
                // The response body now counts toward the budget; reconcile the delta
                // atomically inside computeIfPresent so concurrent updates can't race.
                capturedBytes.addAndGet(transactionBytes(updated) - transactionBytes(tx))
                updated
            }
        // A response can still arrive for a transaction that was cleared or
        // evicted, e.g. every chunk of a long stream; that changes nothing.
        val evicted = trimToBudget()
        if (updated != null || evicted) sequence.incrementAndGet()
    }

    fun updateWithError(id: String, durationMs: Long, error: String, completedAt: Long) {
        val updated =
            transactionMap.computeIfPresent(id) { _, tx ->
                tx.copy(durationMs = durationMs, responseComplete = true, completedAt = completedAt, error = error)
            }
        if (updated != null) sequence.incrementAndGet()
    }

    // A body the redactor fails on, such as JSON nested deeper than the stack
    // allows, is dropped: never stored unredacted, and the rest of its capture kept.
    private fun redactBody(text: String, contentType: MediaType?): String? =
        try {
            redactor.redactBody(text, contentType)
        } catch (t: Throwable) {
            logCaptureFailure("Could not redact a captured body; dropped it", t)
            null
        }

    // A header the redactor fails on is masked, not stored as it is.
    private fun redactHeaderValue(name: String, value: String): String =
        try {
            redactor.redactHeaderValue(name, value)
        } catch (t: Throwable) {
            logCaptureFailure("Could not redact a captured header; masked it", t)
            PLACEHOLDER
        }

    // The content type is a header value, so the Redactor sees it before it's
    // stored on its own. The unredacted MediaType still picks how a body is redacted.
    private fun redactContentType(contentType: MediaType): String =
        redactHeaderValue(CONTENT_TYPE, contentType.toString())

    private fun redactHeaders(headers: Headers): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        headers.forEach { name, value ->
            val redacted = redactHeaderValue(name, value)
            // Join duplicate header values (e.g. multiple Set-Cookie).
            val existing = out[name]
            out[name] = if (existing != null) "$existing, $redacted" else redacted
        }
        return out
    }

    // Text wins when an adapter sets both: it is the form the Redactor has seen.
    // No bytes, as in the reply to a HEAD, is no body: there is nothing to show.
    private fun CapturedBody?.binaryBytes(): ByteArray? = this?.bytes?.takeIf { text == null && it.isNotEmpty() }

    private fun CapturedBody?.backlogWeight(): Long =
        this?.text?.length?.toLong() ?: binaryBytes()?.size?.toLong() ?: 0L

    private fun safeClassify(url: String): List<String> =
        try {
            classifier.classify(url)
        } catch (_: Throwable) {
            emptyList()
        }

    fun getTransactions(search: String? = null): List<NetworkTransaction> {
        val transactions = insertionOrder.mapNotNull { transactionMap[it] }
        if (search.isNullOrBlank()) return transactions
        val needle = search.foldCase()
        return transactions.filter { it.matchesSearch(needle) }
    }

    fun getTransaction(id: String): NetworkTransaction? = transactionMap[id]

    fun addMockRule(rule: MockRuleImpl) {
        mockRules[rule.id] = rule
        persistRules()
    }

    fun removeMockRule(id: String) {
        mockRules.remove(id)
        persistRules()
    }

    fun toggleMockRule(id: String) {
        mockRules.computeIfPresent(id) { _, rule -> rule.copy(enabled = !rule.enabled) }
        persistRules()
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
    }

    fun getMockRules(): List<MockRuleImpl> = mockRules.values.toList()

    fun incrementHitCount(ruleId: String) {
        mockRules.computeIfPresent(ruleId) { _, rule ->
            rule.copy(hitCount = rule.hitCount + 1)
        }
    }

    private fun persistRules() {
        storage?.save(mockRules.values.toList())
    }

    fun isPaused(): Boolean = paused.get()

    fun setPaused(value: Boolean) {
        paused.set(value)
    }

    fun isOverwriteMode(): Boolean = overwriteMode.get()

    fun setOverwriteMode(value: Boolean) {
        overwriteMode.set(value)
    }

    fun getThrottleDelayMs(): Int = throttleDelayMs.get()

    fun setThrottleDelayMs(value: Int) {
        throttleDelayMs.set(value.coerceAtLeast(0))
    }

    fun getSequence(): Long = sequence.get()

    fun clear() {
        synchronized(captureLock) {
            clearCount++
            transactionMap.clear()
            insertionOrder.clear()
            capturedBytes.set(0)
        }
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
     * Returns whether anything was evicted.
     */
    private fun trimToBudget(): Boolean {
        var evicted = false
        synchronized(captureLock) {
            while (capturedBytes.get() > captureBudgetBytes && insertionOrder.size > 1) {
                if (evictOneOldest() == null) break
                evicted = true
            }
        }
        return evicted
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
        private const val TAG = "Lustro"
        private const val PLACEHOLDER = "[REDACTED]"
        private const val CONTENT_TYPE = "Content-Type"

        // The built-in default; the runtime overrides this with DebugConfig
        // .captureBudgetBytes via applyConfig so create() works standalone.
        private const val DEFAULT_CAPTURE_BUDGET_BYTES = 50L * 1024 * 1024
    }
}
