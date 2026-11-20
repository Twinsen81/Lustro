package io.github.twinsen81.lustro.work.internal

import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import io.github.twinsen81.lustro.DebugParam
import io.github.twinsen81.lustro.DebugParams
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugRoutes
import io.github.twinsen81.lustro.DebugRoutes.Companion.ref
import io.github.twinsen81.lustro.ExperimentalDebugRoutes
import java.util.UUID
import java.util.concurrent.ExecutionException
import org.json.JSONException
import org.json.JSONObject

/**
 * The routes of the WorkManager tab, under `/api/v1/work/`. [declare] declares them, and the
 * tab's OpenAPI document comes from the declarations, with the text of [WorkDocument].
 */
@OptIn(ExperimentalDebugRoutes::class)
internal class WorkRoutes(private val source: WorkSource) {
    fun declare(routes: DebugRoutes) {
        routes.title = WorkDocument.TITLE
        routes.version = "1.3"
        routes.description = WorkDocument.DESCRIPTION
        for ((name, schema) in WORK_SCHEMAS) routes.component(name, schema)

        routes.get("items") {
            operationId = "listWork"
            summary = WorkDocument.ITEMS_SUMMARY
            description = WorkDocument.ITEMS_DESCRIPTION
            val cursor = query.string("cursor") { description = WorkDocument.CURSOR }
            val state = query.string("state") { description = WorkDocument.ITEMS_STATE }
            val tag = query.string("tag") { description = WorkDocument.ITEMS_TAG }
            val workerClass = query.string("class") { description = WorkDocument.ITEMS_CLASS }
            val name = query.string("name") { description = WorkDocument.ITEMS_NAME }
            response(WorkDocument.ITEMS_RESPONSE, ref("WorkCursorEnvelope"))
            // The handler checks state.
            errors(400, 500, 504)
            handle { call -> guarded { items(call[cursor], call[state], call[tag], call[workerClass], call[name], workManager()) } }
        }
        routes.get("items/{id}") {
            operationId = "getWork"
            summary = WorkDocument.ITEM_SUMMARY
            description = WorkDocument.ITEM_DESCRIPTION
            // The handler checks the UUID, for a 400 that names the field, not a 404.
            val id =
                path.string("id") {
                    description = WorkDocument.ITEM_ID
                    schema = UUID_SCHEMA
                }
            response(WorkDocument.ITEM_RESPONSE, WorkDocument.ITEM_RESPONSE_SCHEMA)
            errors(400, 404, 500, 504)
            handle { call -> guarded { item(call[id], workManager()) } }
        }
        routes.get("events") {
            operationId = "listWorkEvents"
            summary = WorkDocument.EVENTS_SUMMARY
            description = WorkDocument.EVENTS_DESCRIPTION
            val cursor = query.string("cursor") { description = WorkDocument.CURSOR }
            val id = query.uuid(WorkDocument.EVENTS_ID)
            val workerClass = query.string("class") { description = WorkDocument.EVENTS_CLASS }
            val type = query.string("type") { description = WorkDocument.EVENTS_TYPE }
            val limit =
                query.int("limit") {
                    min = 1
                    max = MAX_LIMIT.toLong()
                    description = WorkDocument.EVENTS_LIMIT
                }.withDefault(DEFAULT_LIMIT)
            response(WorkDocument.EVENTS_RESPONSE, ref("WorkStreamEnvelope"))
            handle { call -> guarded { events(call[cursor], call[id], call[workerClass], call[type], call[limit]) } }
        }
        routes.get("metrics") {
            operationId = "listWorkMetrics"
            summary = WorkDocument.METRICS_SUMMARY
            description = WorkDocument.METRICS_DESCRIPTION
            val id = query.uuid(WorkDocument.METRICS_ID)
            val tag = query.string("tag") { description = WorkDocument.METRICS_TAG }
            val workerClass = query.string("class") { description = WorkDocument.METRICS_CLASS }
            val state = query.string("state") { description = WorkDocument.METRICS_STATE }
            val from =
                query.long("from") {
                    min = 0
                    description = WorkDocument.METRICS_FROM
                }
            val to =
                query.long("to") {
                    min = 0
                    description = WorkDocument.METRICS_TO
                }
            response(WorkDocument.METRICS_RESPONSE, WorkDocument.METRICS_RESPONSE_SCHEMA)
            errors(404, 500, 504)
            handle { call -> guarded { metrics(call[id], call[tag], call[workerClass], call[state], call[from], call[to]) } }
        }
        routes.post("cancel") {
            operationId = "cancelWork"
            summary = WorkDocument.CANCEL_SUMMARY
            description = WorkDocument.CANCEL_DESCRIPTION
            // The router refuses an empty body; the handler checks the rest of the schema, which fields can't say.
            rawBody("application/json", WorkDocument.CANCEL_BODY_SCHEMA)
            response(WorkDocument.CANCEL_RESPONSE, WorkDocument.CANCEL_RESPONSE_SCHEMA)
            errors(404, 500, 504)
            handle { call -> guarded { cancel(cancelTarget(body(call.request.bodyAsString().orEmpty())), workManager()) } }
        }
        routes.post("prune") {
            operationId = "pruneWork"
            summary = WorkDocument.PRUNE_SUMMARY
            description = WorkDocument.PRUNE_DESCRIPTION
            response(WorkDocument.PRUNE_RESPONSE, WorkDocument.PRUNE_RESPONSE_SCHEMA)
            errors(500, 504)
            handle { guarded { prune(workManager()) } }
        }
    }

    private fun DebugParams.uuid(description: String): DebugParam<String?> =
        string("id") {
            this.description = description
            schema = UUID_SCHEMA
        }

    // Without WorkManager 2.12, every route answers 503, after the declarations check the request.
    private inline fun guarded(answer: () -> DebugResponse): DebugResponse {
        source.unsupported?.let {
            return DebugResponse.error(it, status = 503, code = "work_unsupported", hint = "Add androidx.work:work-runtime ${WorkSupport.MIN_VERSION} or newer")
        }
        return try {
            answer()
        } catch (e: RouteException) {
            DebugResponse.error(e.message.orEmpty(), status = e.status, code = e.code, field = e.field, hint = e.hint)
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            DebugResponse.error(cause.message ?: cause.javaClass.name, status = 500, code = "work_error")
        } catch (_: InterruptedException) {
            // The runtime interrupts the thread when it gives up on the request.
            Thread.currentThread().interrupt()
            DebugResponse.error("The request timed out or the server stopped", status = 504)
        }
    }

    private fun items(
        cursor: String?,
        state: String?,
        tagText: String?,
        classText: String?,
        nameText: String?,
        workManager: WorkManager,
    ): DebugResponse {
        val states = states(state)
        val tag = tagText?.takeIf { it.isNotEmpty() }
        val name = nameText?.takeIf { it.isNotEmpty() }
        val workerClass = classText?.takeIf { it.isNotEmpty() }
        val query = WorkQuery.Builder.fromStates(states)
        if (tag != null) query.addTags(listOf(tag))
        if (name != null) query.addUniqueWorkNames(listOf(name))
        val infos = workManager.getWorkInfos(query.build()).get().filter { matchesClass(it.workerClassName, workerClass) }
        val records = source.records.of(workManager, infos)
        val items =
            buildString {
                infos.forEachIndexed { index, info ->
                    if (index > 0) append(',')
                    val record = records[info.id]
                    WorkJson.appendItem(this, info, record?.uniqueName ?: name, record?.prerequisites.orEmpty())
                }
            }
        // The list is the snapshot of one moment, so the cursor is the hash of what it
        // holds: a poll is `unchanged` while every item stays the same.
        return DebugResponse.cursorEnvelope(fnv64(items), cursor, state = source.stateJson(workManager)) {
            append(items)
        }
    }

    private fun item(text: String, workManager: WorkManager): DebugResponse {
        val id = uuid(text, field = "id")
        val info = workManager.getWorkInfoById(id).get()
        val events = source.events.of(id.toString())
        val periods = source.metrics?.of(id)
        if (info == null && events.isEmpty() && periods.isNullOrEmpty()) {
            throw RouteException("No work has the id $id, and the tab has no events of it", status = 404, field = "id")
        }
        val record = if (info == null) null else source.records.of(workManager, listOf(info))[id]
        return DebugResponse.json {
            append("{\"item\":")
            if (info == null) append("null") else WorkJson.appendItem(this, info, record?.uniqueName, record?.prerequisites.orEmpty())
            append(",\"events\":[")
            events.forEachIndexed { index, event ->
                if (index > 0) append(',')
                append(event.json)
            }
            append(']')
            if (periods != null) {
                append(",\"metrics\":[")
                periods.forEachIndexed { index, period ->
                    if (index > 0) append(',')
                    WorkMetrics.appendPeriod(this, period)
                }
                append(']')
            }
            append(",\"state\":").append(source.stateJson(workManager)).append('}')
        }
    }

    private fun events(cursor: String?, idText: String?, classText: String?, typeText: String?, limit: Int): DebugResponse {
        val id = idText?.takeIf { it.isNotEmpty() }?.let { uuid(it, field = "id").toString() }
        val workerClass = classText?.takeIf { it.isNotEmpty() }
        val types = types(typeText)
        val matches = { event: WorkEvent ->
            (id == null || event.id == id) && matchesClass(event.workerClass, workerClass) && (types == null || event.type in types)
        }
        val snapshot = source.events.snapshot(cursor)
        return DebugResponse.streamEnvelope(
            total = snapshot.total,
            retained = snapshot.retained,
            clientCursor = cursor,
            state = source.stateJson(source.workManager()),
            epoch = source.events.epoch,
        ) { from, reset ->
            if (reset) {
                // A new client starts with the last events that pass the filters.
                val picked = ArrayList<WorkEvent>()
                var index = snapshot.retained - 1
                while (index >= snapshot.first && picked.size < limit) {
                    val event = snapshot[index--]
                    if (matches(event)) picked += event
                }
                for (pick in picked.indices.reversed()) {
                    if (pick < picked.size - 1) append(',')
                    append(picked[pick].json)
                }
                snapshot.retained
            } else {
                // A long backlog arrives in pages: the next poll goes on after the last event of this one.
                var written = 0
                var index = from
                while (index < snapshot.retained && written < limit) {
                    val event = snapshot[index++]
                    if (!matches(event)) continue
                    if (written++ > 0) append(',')
                    append(event.json)
                }
                index
            }
        }
    }

    private fun metrics(
        idText: String?,
        tagText: String?,
        classText: String?,
        state: String?,
        from: Long?,
        to: Long?,
    ): DebugResponse {
        val metrics =
            source.metrics ?: throw RouteException(
                "The app passed no WorkMetricsInfoRepository to WorkDebugTab.create(), so the tab has no history",
                status = 404,
                code = "metrics_off",
                hint = "Add androidx.work:work-analytics, set its repository as the app's listeners, and pass it as metrics",
            )
        val ids = idText?.takeIf { it.isNotEmpty() }?.let { listOf(uuid(it, field = "id")) }.orEmpty()
        val tags = tagText?.takeIf { it.isNotEmpty() }?.let { listOf(it) }.orEmpty()
        val states = metricsStates(state)
        // WorkMetricsQuery.Builder.build() throws for a range that ends before it begins.
        if (from != null && to != null && from > to) throw RouteException("to must not be before from", field = "to")
        val workerClass = classText?.takeIf { it.isNotEmpty() }
        val periods = metrics.query(ids, tags, states, from, to).filter { matchesClass(it.workerClassName, workerClass) }
        return DebugResponse.json {
            append("{\"items\":[")
            periods.forEachIndexed { index, period ->
                if (index > 0) append(',')
                WorkMetrics.appendPeriod(this, period)
            }
            append("]}")
        }
    }

    // POST cancel with one of {"id"}, {"name"}, or {"tag"}: the work with the id, the unique
    // work with the name, or all work with the tag. Finished work stays as it is.
    private fun cancel(target: Pair<String, String>, workManager: WorkManager): DebugResponse {
        val (key, value) = target
        val id = if (key == "id") uuid(value, field = "id") else null
        val matched: List<WorkInfo> =
            when {
                id != null -> listOfNotNull(workManager.getWorkInfoById(id).get())
                key == "name" -> workManager.getWorkInfosForUniqueWork(value).get()
                else -> workManager.getWorkInfosByTag(value).get()
            }
        if (matched.isEmpty()) {
            val what = mapOf("id" to "the id", "name" to "the unique name", "tag" to "the tag").getValue(key)
            throw RouteException("No work has $what $value", status = 404, field = key)
        }
        val unfinished = matched.count { !it.state.isFinished }
        val operation =
            when {
                id != null -> workManager.cancelWorkById(id)
                key == "name" -> workManager.cancelUniqueWork(value)
                else -> workManager.cancelAllWorkByTag(value)
            }
        operation.result.get()
        return DebugResponse.json { append("{\"status\":\"ok\",\"cancelled\":").append(unfinished).append('}') }
    }

    // The one key of the body, and its value. An unknown key is refused, not ignored: a
    // misspelled one would otherwise cancel by the other key alone.
    private fun cancelTarget(body: JSONObject): Pair<String, String> {
        val given = body.keys().asSequence().toList()
        if (given.size != 1 || given.single() !in KEYS) {
            val field = given.firstOrNull { it !in KEYS } ?: given.getOrNull(1)
            throw RouteException("The body names exactly one of ${KEYS.joinToString()}, and nothing else", field = field)
        }
        val key = given.single()
        val value = (body.get(key) as? String)?.takeIf { it.isNotEmpty() } ?: throw RouteException("$key must be a non-empty string", field = key)
        return key to value
    }

    // pruneWork deletes finished work that no unfinished work depends on.
    private fun prune(workManager: WorkManager): DebugResponse {
        val finished = WorkQuery.fromStates(WorkInfo.State.values().filter { it.isFinished })
        val before = workManager.getWorkInfos(finished).get().size
        workManager.pruneWork().result.get()
        val after = workManager.getWorkInfos(finished).get().size
        return DebugResponse.json { append("{\"status\":\"ok\",\"pruned\":").append((before - after).coerceAtLeast(0)).append('}') }
    }

    private fun workManager(): WorkManager =
        source.workManager() ?: throw RouteException(
            "The app has not initialized WorkManager yet, and the tab does not initialize it",
            status = 503,
            code = "work_not_initialized",
            hint = "Open the part of the app that uses WorkManager, or call WorkManager.getInstance(context) in the app",
        )

    private fun metricsStates(text: String?): List<String> {
        if (text.isNullOrEmpty()) return emptyList()
        val names = WorkMetrics.stateNames()
        return text.split(',').map { given ->
            given.trim().uppercase().takeIf { it in names }
                ?: throw RouteException("state must be one or more of ${names.joinToString()}", field = "state")
        }
    }

    private fun states(text: String?): List<WorkInfo.State> {
        if (text.isNullOrEmpty()) return WorkInfo.State.values().toList()
        val all = WorkInfo.State.values()
        return text.split(',').map { given ->
            all.firstOrNull { it.name.equals(given.trim(), ignoreCase = true) }
                ?: throw RouteException("state must be one or more of ${all.joinToString { it.name }}", field = "state")
        }.distinct()
    }

    private fun types(text: String?): Set<String>? {
        if (text.isNullOrEmpty()) return null
        return text.split(',').map { given ->
            EVENT_TYPES.firstOrNull { it.equals(given.trim(), ignoreCase = true) }
                ?: throw RouteException("type must be one or more of ${EVENT_TYPES.joinToString()}", field = "type")
        }.toSet()
    }

    private fun uuid(text: String, field: String): UUID =
        try {
            UUID.fromString(text).takeIf { it.toString().equals(text, ignoreCase = true) } ?: throw IllegalArgumentException(text)
        } catch (_: IllegalArgumentException) {
            throw RouteException("$field must be a work id, a UUID such as 3f1c2a4e-8b9d-4e2f-a6c1-0d7e5b3a9f12", field = field)
        }

    // Read as org.json reads it, as before the declarations: the router only refuses an empty body.
    private fun body(text: String): JSONObject =
        try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw RouteException("The body is not a JSON object: ${e.message}", field = "body")
        }

    private class RouteException(
        message: String,
        val status: Int = 400,
        val code: String? = null,
        val field: String? = null,
        val hint: String? = null,
    ) : Exception(message)

    companion object {
        const val DEFAULT_LIMIT: Int = 500
        const val MAX_LIMIT: Int = 5_000

        private const val UUID_SCHEMA = """{"type": "string", "format": "uuid"}"""

        private val KEYS = listOf("id", "name", "tag")

        val EVENT_TYPES: List<String> =
            listOf("enqueued", "unblocked", "updated", "cancelled", "prerequisiteFailed", "started", "stopped", "finished", "exception")

        /** Whether [className] is [wanted], by its full name or its simple name; any class when [wanted] is `null`. */
        fun matchesClass(className: String?, wanted: String?): Boolean {
            if (wanted == null) return true
            if (className == null) return false
            return className == wanted || className.substringAfterLast('.') == wanted || className.substringAfterLast('$') == wanted
        }

        // FNV-1a over the UTF-16 units: a change anywhere in the list changes it.
        fun fnv64(text: String): Long {
            var hash = -0x340d631b7bdddcdbL
            for (char in text) {
                hash = hash xor char.code.toLong()
                hash *= 0x100000001b3L
            }
            return hash
        }
    }
}
