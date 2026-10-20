package io.github.twinsen81.lustro.logs.internal

import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.escapeForJson
import org.json.JSONException
import org.json.JSONObject

/** The routes of the Logs tab, under `/api/v1/logs/`. */
internal class LogRoutes(private val buffer: LogBuffer, private val reader: LogcatReader) {
    fun handle(request: DebugRequest): DebugResponse? {
        val method = request.method.uppercase()
        val expected = METHODS[request.path] ?: return null
        if (method != expected) {
            return DebugResponse.error("${request.path} takes $expected", status = 405)
        }
        return when (request.path) {
            "entries" -> entries(request)
            "text" -> text(request)
            "clear" -> clear()
            "pause" -> setFlag(request, "paused", { buffer.paused }) { buffer.paused = it }
            "compact" -> setFlag(request, "compact", { buffer.compact }) { buffer.compact = it }
            else -> null
        }
    }

    private fun entries(request: DebugRequest): DebugResponse {
        val limit =
            when (val given = request.queryParam("limit")?.takeIf { it.isNotEmpty() }) {
                null -> DEFAULT_LIMIT
                else -> given.toIntOrNull()?.takeIf { it in 1..MAX_LIMIT }
                    ?: return DebugResponse.error("limit must be a whole number from 1 to $MAX_LIMIT", field = "limit")
            }
        val filter =
            try {
                LogFilter.from(request)
            } catch (e: BadParameterException) {
                return DebugResponse.error(e.message.orEmpty(), field = e.field)
            }
        val cursor = request.queryParam("cursor")
        val snapshot = buffer.snapshot(cursor, issuesCursor = true)
        val budget = FilterBudget()
        val format = ThreadtimeFormat()
        return try {
            DebugResponse.streamEnvelope(
                total = snapshot.total,
                retained = snapshot.retained,
                clientCursor = cursor,
                state = stateJson(),
                epoch = snapshot.epoch,
            ) { from, reset ->
                if (reset) {
                    // A new client starts with the last lines that pass the filters.
                    val picked = ArrayList<LogEntry>()
                    var index = snapshot.retained - 1
                    while (index >= snapshot.first && picked.size < limit) {
                        val entry = snapshot[index--] ?: continue
                        if (filter.matches(entry, budget)) picked += entry
                    }
                    for (pick in picked.indices.reversed()) {
                        if (pick < picked.size - 1) append(',')
                        appendEntry(picked[pick], format)
                    }
                    snapshot.retained
                } else {
                    // A long backlog arrives in pages: the next poll goes on after the last line of this one.
                    var written = 0
                    var index = from
                    while (index < snapshot.retained && written < limit) {
                        val entry = snapshot[index++] ?: continue
                        if (!filter.matches(entry, budget)) continue
                        if (written++ > 0) append(',')
                        appendEntry(entry, format)
                    }
                    index
                }
            }
        } catch (e: FilterTimeoutException) {
            DebugResponse.error(e.message.orEmpty(), field = filter.regexField)
        }
    }

    private fun text(request: DebugRequest): DebugResponse {
        val limit =
            when (val given = request.queryParam("limit")?.takeIf { it.isNotEmpty() }) {
                null -> Int.MAX_VALUE
                else -> given.toIntOrNull()?.takeIf { it >= 1 }
                    ?: return DebugResponse.error("limit must be a whole number, 1 or more", field = "limit")
            }
        val filter =
            try {
                LogFilter.from(request)
            } catch (e: BadParameterException) {
                return DebugResponse.error(e.message.orEmpty(), field = e.field)
            }
        val snapshot = buffer.snapshot(request.queryParam("cursor"), issuesCursor = false)
        val budget = FilterBudget()
        val picked = ArrayDeque<LogEntry>()
        try {
            for (index in snapshot.first until snapshot.retained) {
                val entry = snapshot[index] ?: continue
                if (!filter.matches(entry, budget)) continue
                picked.addLast(entry)
                if (picked.size > limit) picked.removeFirst()
            }
        } catch (e: FilterTimeoutException) {
            return DebugResponse.error(e.message.orEmpty(), field = filter.regexField)
        }
        val format = ThreadtimeFormat()
        val body = StringBuilder()
        for (entry in picked) body.appendThreadtime(entry, format).append('\n')
        return DebugResponse.text(body.toString())
    }

    private fun clear(): DebugResponse {
        val cursor = buffer.clear()
        return DebugResponse.json { append("{\"status\":\"ok\",\"cursor\":\"").append(cursor.escapeForJson()).append("\"}") }
    }

    // Sets the flag from an optional {"<key>": true|false} body, or toggles it without the key.
    private inline fun setFlag(
        request: DebugRequest,
        key: String,
        current: () -> Boolean,
        set: (Boolean) -> Unit,
    ): DebugResponse {
        val given =
            try {
                flagIn(request, key)
            } catch (e: BadParameterException) {
                return DebugResponse.error(e.message.orEmpty(), field = e.field)
            }
        val value = given ?: !current()
        set(value)
        return DebugResponse.json { append("{\"status\":\"ok\",\"").append(key).append("\":").append(value).append('}') }
    }

    private fun flagIn(request: DebugRequest, key: String): Boolean? {
        val text = request.bodyAsString()?.takeIf { it.isNotBlank() } ?: return null
        val json =
            try {
                JSONObject(text)
            } catch (e: JSONException) {
                throw BadParameterException("The body is not a JSON object: ${e.message}", key)
            }
        if (!json.has(key)) return null
        return json.get(key) as? Boolean ?: throw BadParameterException("$key must be true or false", key)
    }

    private fun stateJson(): String =
        buildString {
            append("{\"reader\":\"").append(reader.status.wireName).append('"')
            reader.error?.let { append(",\"readerError\":\"").append(it.escapeForJson()).append('"') }
            append(",\"paused\":").append(buffer.paused)
            append(",\"compact\":").append(buffer.compact)
            append('}')
        }

    private fun StringBuilder.appendEntry(entry: LogEntry, format: ThreadtimeFormat) {
        append("{\"seq\":").append(entry.seq)
        append(",\"at\":").append(entry.at)
        append(",\"timestamp\":\"").append(format.format(entry.at)).append('"')
        append(",\"pid\":").append(entry.pid)
        append(",\"tid\":").append(entry.tid)
        append(",\"level\":\"").append(entry.level).append('"')
        append(",\"tag\":\"").append(entry.tag.escapeForJson()).append('"')
        append(",\"message\":\"").append(entry.message.escapeForJson()).append('"')
        if (entry.count > 1) {
            append(",\"count\":").append(entry.count)
            append(",\"lastAt\":").append(entry.lastAt)
        }
        append('}')
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 500
        const val MAX_LIMIT: Int = 5_000

        private val METHODS = mapOf(
            "entries" to "GET",
            "text" to "GET",
            "clear" to "POST",
            "pause" to "POST",
            "compact" to "POST",
        )

        /** Appends [entry] as logcat's threadtime format prints it, with ` [×N]` for a repeated line. */
        fun StringBuilder.appendThreadtime(entry: LogEntry, format: ThreadtimeFormat): StringBuilder {
            append(format.format(entry.at)).append(' ')
            appendPadded(entry.pid.toString(), PID_WIDTH).append(' ')
            appendPadded(entry.tid.toString(), PID_WIDTH).append(' ')
            append(entry.level).append(' ')
            append(entry.tag)
            repeat(TAG_WIDTH - entry.tag.length) { append(' ') }
            append(": ").append(entry.message)
            if (entry.count > 1) append(" [×").append(entry.count).append(']')
            return this
        }

        private fun StringBuilder.appendPadded(value: String, width: Int): StringBuilder {
            repeat(width - value.length) { append(' ') }
            return append(value)
        }

        private const val PID_WIDTH = 5
        private const val TAG_WIDTH = 8
    }
}
