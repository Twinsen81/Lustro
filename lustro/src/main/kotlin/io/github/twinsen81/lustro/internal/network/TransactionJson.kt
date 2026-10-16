package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.escapeForJson
import io.github.twinsen81.lustro.internal.toDebugTimestamp

// The rules that could answer the request if it were made again: none for a
// platform request. They are matched as the interceptor matches them, but
// against the URL as captured, which is redacted.
private fun rulesFor(tx: NetworkTransaction, rules: List<MockRuleImpl>): List<MockRuleImpl> =
    if (tx.source == CaptureSource.PLATFORM) emptyList() else rules

/**
 * Writes [tx] as the Network tab's routes serve it: the list's brief form, or
 * with [brief] `false` the detail. [rules] are the mock rules, in the order they
 * are tried, which each transaction is matched against.
 */
internal fun StringBuilder.appendTransactionJson(
    tx: NetworkTransaction,
    brief: Boolean,
    webSocketId: String?,
    rules: List<MockRuleImpl>,
) {
    append("{")
    append("\"id\":\"${tx.id.escapeForJson()}\",")
    append("\"timestamp\":\"${tx.startedAt.toDebugTimestamp()}\",")
    append("\"startedAt\":${tx.startedAt},")
    append("\"completedAt\":${tx.completedAt ?: "null"},")
    append("\"method\":\"${tx.method.escapeForJson()}\",")
    append("\"url\":\"${tx.url.escapeForJson()}\",")
    append("\"finalUrl\":${tx.finalUrl.toJsonString()},")
    append("\"protocol\":${tx.protocol.toJsonString()},")
    append("\"statusCode\":${tx.statusCode ?: "null"},")
    append("\"durationMs\":${tx.durationMs ?: "null"},")
    append("\"throttledMs\":${tx.throttledMs ?: "null"},")
    append("\"categories\":[")
    tx.categories.forEachIndexed { i, cat ->
        if (i > 0) append(",")
        append("\"${cat.escapeForJson()}\"")
    }
    append("],")
    append("\"isMocked\":${tx.isMocked},")
    append("\"mockRuleId\":${tx.mockRuleId.toJsonString()},")
    append("\"activeMockRuleId\":${rulesFor(tx, rules).answering(tx.url, tx.method)?.id.toJsonString()},")
    append("\"source\":\"${tx.source.wireName}\",")
    append("\"replacedCount\":${tx.replacedCount},")
    append("\"requestContentType\":${tx.requestContentType.toJsonString()},")
    append("\"responseContentType\":${tx.responseContentType.toJsonString()},")
    append("\"requestBodyBytes\":${tx.requestBodyBytes ?: "null"},")
    append("\"responseBodyBytes\":${tx.responseBodyBytes ?: "null"},")
    append("\"responseComplete\":${tx.responseComplete},")
    // Set on the handshake of a socket from Lustro.webSocketFactory: its messages are under websockets/{id}.
    append("\"webSocketId\":${webSocketId.toJsonString()},")
    append("\"error\":${tx.error.toJsonString()}")
    if (!brief) {
        append(",")
        append("\"requestHeaders\":${headersToJson(tx.requestHeaders)},")
        append("\"requestBody\":${tx.requestBody.toJsonString()},")
        append("\"requestBodyTruncated\":${tx.requestBodyTruncated},")
        append("\"requestBodyBinary\":${tx.requestBinaryBody != null},")
        append("\"responseHeaders\":${tx.responseHeaders?.let { headersToJson(it) } ?: "null"},")
        append("\"responseBody\":${tx.responseBody.toJsonString()},")
        append("\"responseBodyTruncated\":${tx.responseBodyTruncated},")
        append("\"responseBodyBinary\":${tx.responseBinaryBody != null},")
        // Enabled or not, in the order they are tried.
        append("\"matchingMockRuleIds\":[")
        rulesFor(tx, rules).filter { it.matchesWhenEnabled(tx.url, tx.method) }.forEachIndexed { i, rule ->
            if (i > 0) append(",")
            append(rule.id.toJsonString())
        }
        append("],")
        append("\"priorResponses\":[")
        tx.priorResponses.forEachIndexed { i, prior ->
            if (i > 0) append(",")
            append("{\"url\":\"${prior.url.escapeForJson()}\",\"statusCode\":${prior.statusCode}}")
        }
        append("]")
    }
    append("}")
}

private fun String?.toJsonString(): String = this?.let { "\"${it.escapeForJson()}\"" } ?: "null"

private fun headersToJson(headers: Map<String, String>): String =
    buildString {
        append("{")
        headers.entries.forEachIndexed { index, (key, value) ->
            if (index > 0) append(",")
            append("\"${key.escapeForJson()}\":\"${value.escapeForJson()}\"")
        }
        append("}")
    }
