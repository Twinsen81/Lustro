package io.github.twinsen81.lustro.logs.internal

import io.github.twinsen81.lustro.DebugCall
import io.github.twinsen81.lustro.DebugParam
import io.github.twinsen81.lustro.DebugParams
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugResponseException
import io.github.twinsen81.lustro.DebugRoutes
import io.github.twinsen81.lustro.DebugRoutes.Companion.ref
import io.github.twinsen81.lustro.ExperimentalDebugRoutes
import io.github.twinsen81.lustro.escapeForJson

/**
 * The routes of the Logs tab, under `/api/v1/logs/`. [declare] declares them, and the tab's
 * OpenAPI document comes from the declarations, with the text of [LogsDocument].
 */
@OptIn(ExperimentalDebugRoutes::class)
internal class LogRoutes(private val buffer: LogBuffer, private val reader: LogcatReader) {
    fun declare(routes: DebugRoutes) {
        routes.title = LogsDocument.TITLE
        routes.version = "1.1"
        routes.description = LogsDocument.DESCRIPTION
        for ((name, schema) in LOGS_SCHEMAS) routes.component(name, schema)

        routes.get("entries") {
            operationId = "listLogEntries"
            summary = LogsDocument.ENTRIES_SUMMARY
            description = LogsDocument.ENTRIES_DESCRIPTION
            val filters = query.filters()
            val limit =
                query.int("limit") {
                    min = 1
                    max = MAX_LIMIT.toLong()
                    description = LogsDocument.ENTRIES_LIMIT
                }.withDefault(DEFAULT_LIMIT)
            response(LogsDocument.ENTRIES_RESPONSE, ref("LogStreamEnvelope"))
            handle { call -> entries(call[filters.cursor], filters.read(call), call[limit]) }
        }
        routes.get("text") {
            operationId = "exportLogText"
            summary = LogsDocument.TEXT_SUMMARY
            description = LogsDocument.TEXT_DESCRIPTION
            val filters = query.filters()
            val limit =
                query.int("limit") {
                    min = 1
                    description = LogsDocument.TEXT_LIMIT
                }
            response(LogsDocument.TEXT_RESPONSE) { content("text/plain", """{"type": "string"}""") }
            handle { call -> text(call[filters.cursor], filters.read(call), call[limit] ?: Int.MAX_VALUE) }
        }
        routes.post("clear") {
            operationId = "clearLog"
            summary = LogsDocument.CLEAR_SUMMARY
            description = LogsDocument.CLEAR_DESCRIPTION
            response(LogsDocument.CLEAR_RESPONSE, LogsDocument.CLEAR_RESPONSE_SCHEMA)
            handle { clear() }
        }
        routes.post("pause") {
            operationId = "pauseLog"
            summary = LogsDocument.PAUSE_SUMMARY
            description = LogsDocument.PAUSE_DESCRIPTION
            bodyDescription = LogsDocument.PAUSE_BODY
            val paused = body.boolean("paused") { description = LogsDocument.PAUSE_FIELD }
            response(LogsDocument.PAUSE_RESPONSE, LogsDocument.PAUSE_RESPONSE_SCHEMA)
            handle { call -> setFlag("paused", call[paused], { buffer.paused }) { buffer.paused = it } }
        }
        routes.post("compact") {
            operationId = "setLogCompact"
            summary = LogsDocument.COMPACT_SUMMARY
            description = LogsDocument.COMPACT_DESCRIPTION
            bodyDescription = LogsDocument.COMPACT_BODY
            val compact = body.boolean("compact") { description = LogsDocument.COMPACT_FIELD }
            response(LogsDocument.COMPACT_RESPONSE, LogsDocument.COMPACT_RESPONSE_SCHEMA)
            handle { call -> setFlag("compact", call[compact], { buffer.compact }) { buffer.compact = it } }
        }
    }

    /** The parameters that `GET entries` and `GET text` share: the cursor and the filters. */
    private class FilterParams(
        val cursor: DebugParam<String?>,
        val level: DebugParam<String?>,
        val tag: DebugParam<String?>,
        val q: DebugParam<String?>,
        val mq: DebugParam<String?>,
        val from: DebugParam<Long?>,
        val to: DebugParam<Long?>,
    ) {
        /** The filters of [call]: the checks that the declarations can't make, such as a regex that compiles. */
        fun read(call: DebugCall): LogFilter =
            try {
                LogFilter.from(call[level], call[tag], call[q], call[mq], call[from] ?: Long.MIN_VALUE, call[to] ?: Long.MAX_VALUE)
            } catch (e: BadParameterException) {
                fail(e.message.orEmpty(), e.field)
            }
    }

    private fun DebugParams.filters(): FilterParams =
        FilterParams(
            cursor = string("cursor") { description = LogsDocument.CURSOR },
            // A lowercase letter works too, and an empty one is no filter, so LogFilter checks the value.
            level =
                string("level") {
                    description = LogsDocument.LEVEL
                    schema = LogsDocument.LEVEL_SCHEMA
                },
            tag = regex("tag", LogsDocument.TAG),
            q = string("q") { description = LogsDocument.Q },
            mq = regex("mq", LogsDocument.MQ),
            from = long("from") { description = LogsDocument.FROM },
            to = long("to") { description = LogsDocument.TO },
        )

    private fun DebugParams.regex(name: String, description: String): DebugParam<String?> =
        string(name) {
            maxLength = LogFilter.MAX_REGEX_LENGTH
            this.description = description
        }

    private fun entries(cursor: String?, filter: LogFilter, limit: Int): DebugResponse {
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

    private fun text(cursor: String?, filter: LogFilter, limit: Int): DebugResponse {
        val snapshot = buffer.snapshot(cursor, issuesCursor = false)
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

    // Sets the flag to the body's value, or toggles it without one.
    private inline fun setFlag(
        key: String,
        given: Boolean?,
        current: () -> Boolean,
        set: (Boolean) -> Unit,
    ): DebugResponse {
        val value = given ?: !current()
        set(value)
        return DebugResponse.json { append("{\"status\":\"ok\",\"").append(key).append("\":").append(value).append('}') }
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

        /**
         * Appends [entry] as logcat's threadtime format prints it, with ` [×N]` for a
         * repeated line. A message with line breaks, which only an appended line
         * has, prints each of its lines with the prefix, as logcat does.
         */
        fun StringBuilder.appendThreadtime(entry: LogEntry, format: ThreadtimeFormat): StringBuilder {
            val time = format.format(entry.at)
            val message = entry.message
            var start = 0
            while (true) {
                appendPrefix(entry, time)
                val end = message.indexOf('\n', start)
                if (end < 0) break
                append(message, start, end).append('\n')
                start = end + 1
            }
            append(message, start, message.length)
            if (entry.count > 1) append(" [×").append(entry.count).append(']')
            return this
        }

        private fun StringBuilder.appendPrefix(entry: LogEntry, time: String) {
            append(time).append(' ')
            appendPadded(entry.pid.toString(), PID_WIDTH).append(' ')
            appendPadded(entry.tid.toString(), PID_WIDTH).append(' ')
            append(entry.level).append(' ')
            append(entry.tag)
            repeat(TAG_WIDTH - entry.tag.length) { append(' ') }
            append(": ")
        }

        private fun StringBuilder.appendPadded(value: String, width: Int): StringBuilder {
            repeat(width - value.length) { append(' ') }
            return append(value)
        }

        private const val PID_WIDTH = 5
        private const val TAG_WIDTH = 8
    }
}

@OptIn(ExperimentalDebugRoutes::class)
private fun fail(message: String, field: String): Nothing = throw DebugResponseException(DebugResponse.error(message, field = field))
