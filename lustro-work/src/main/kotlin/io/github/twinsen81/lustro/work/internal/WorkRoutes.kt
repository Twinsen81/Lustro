package io.github.twinsen81.lustro.work.internal

import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import java.util.UUID
import java.util.concurrent.ExecutionException
import org.json.JSONException
import org.json.JSONObject

/** The routes of the WorkManager tab, under `/api/v1/work/`. */
internal class WorkRoutes(private val source: WorkSource) {
    fun handle(request: DebugRequest): DebugResponse? {
        val route = if (request.path.startsWith(ITEM_PREFIX)) ITEM else request.path
        val expected = METHODS[route] ?: return null
        if (request.method.uppercase() != expected) {
            return DebugResponse.error("${request.path} takes $expected", status = 405)
        }
        source.unsupported?.let {
            return DebugResponse.error(it, status = 503, code = "work_unsupported", hint = "Add androidx.work:work-runtime ${WorkSupport.MIN_VERSION} or newer")
        }
        return try {
            when (route) {
                "items" -> items(request, workManager())
                ITEM -> item(request.path.removePrefix(ITEM_PREFIX), workManager())
                "events" -> events(request)
                "metrics" -> metrics(request)
                "cancel" -> cancel(body(request), workManager())
                "prune" -> prune(workManager())
                else -> null
            }
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

    private fun items(request: DebugRequest, workManager: WorkManager): DebugResponse {
        val states = states(request.queryParam("state"))
        val tag = request.queryParam("tag")?.takeIf { it.isNotEmpty() }
        val name = request.queryParam("name")?.takeIf { it.isNotEmpty() }
        val workerClass = request.queryParam("class")?.takeIf { it.isNotEmpty() }
        val query = WorkQuery.Builder.fromStates(states)
        if (tag != null) query.addTags(listOf(tag))
        if (name != null) query.addUniqueWorkNames(listOf(name))
        val infos = workManager.getWorkInfos(query.build()).get().filter { matchesClass(it.workerClassName, workerClass) }
        val names = uniqueNames(workManager)
        val items =
            buildString {
                infos.forEachIndexed { index, info ->
                    if (index > 0) append(',')
                    WorkJson.appendItem(this, info, names[info.id] ?: name)
                }
            }
        // The list is the snapshot of one moment, so the cursor is the hash of what it
        // holds: a poll is `unchanged` while every item stays the same.
        return DebugResponse.cursorEnvelope(fnv64(items), request.queryParam("cursor"), state = source.stateJson(workManager)) {
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
        val name = if (info == null) null else uniqueNames(workManager)[id]
        return DebugResponse.json {
            append("{\"item\":")
            if (info == null) append("null") else WorkJson.appendItem(this, info, name)
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

    private fun events(request: DebugRequest): DebugResponse {
        val limit = limit(request.queryParam("limit"))
        val id = request.queryParam("id")?.takeIf { it.isNotEmpty() }?.let { uuid(it, field = "id").toString() }
        val workerClass = request.queryParam("class")?.takeIf { it.isNotEmpty() }
        val types = types(request.queryParam("type"))
        val matches = { event: WorkEvent ->
            (id == null || event.id == id) && matchesClass(event.workerClass, workerClass) && (types == null || event.type in types)
        }
        val cursor = request.queryParam("cursor")
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

    private fun metrics(request: DebugRequest): DebugResponse {
        val metrics =
            source.metrics ?: throw RouteException(
                "The app passed no WorkMetricsInfoRepository to WorkDebugTab.create(), so the tab has no history",
                status = 404,
                code = "metrics_off",
                hint = "Add androidx.work:work-analytics, set its repository as the app's listeners, and pass it as metrics",
            )
        val ids = request.queryParam("id")?.takeIf { it.isNotEmpty() }?.let { listOf(uuid(it, field = "id")) }.orEmpty()
        val tags = request.queryParam("tag")?.takeIf { it.isNotEmpty() }?.let { listOf(it) }.orEmpty()
        val names = WorkMetrics.stateNames()
        val states =
            request.queryParam("state")?.takeIf { it.isNotEmpty() }?.split(',')?.map { given ->
                val state = given.trim().uppercase()
                if (state !in names) throw RouteException("state must be one or more of ${names.joinToString()}", field = "state")
                state
            }.orEmpty()
        val from = millis(request.queryParam("from"), "from")
        val to = millis(request.queryParam("to"), "to")
        val workerClass = request.queryParam("class")?.takeIf { it.isNotEmpty() }
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
    private fun cancel(body: JSONObject, workManager: WorkManager): DebugResponse {
        val (key, value) = cancelTarget(body)
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

    // The one key of the body, and its value.
    private fun cancelTarget(body: JSONObject): Pair<String, String> {
        val given = KEYS.filter { body.has(it) }
        if (given.size != 1) throw RouteException("The body names exactly one of ${KEYS.joinToString()}", field = given.getOrNull(1))
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

    // The registered unique names of the work, by id. Each name is one query.
    private fun uniqueNames(workManager: WorkManager): Map<UUID, String> {
        val names = HashMap<UUID, String>()
        for (name in source.uniqueNames) {
            for (info in workManager.getWorkInfosForUniqueWork(name).get()) names[info.id] = name
        }
        return names
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

    private fun limit(text: String?): Int {
        if (text.isNullOrEmpty()) return DEFAULT_LIMIT
        return text.toIntOrNull()?.takeIf { it in 1..MAX_LIMIT }
            ?: throw RouteException("limit must be a whole number from 1 to $MAX_LIMIT", field = "limit")
    }

    private fun millis(text: String?, field: String): Long? {
        if (text.isNullOrEmpty()) return null
        return text.toLongOrNull()?.takeIf { it >= 0 } ?: throw RouteException("$field must be a time in milliseconds since the epoch", field = field)
    }

    private fun uuid(text: String, field: String): UUID =
        try {
            UUID.fromString(text).takeIf { it.toString().equals(text, ignoreCase = true) } ?: throw IllegalArgumentException(text)
        } catch (_: IllegalArgumentException) {
            throw RouteException("$field must be a work id, a UUID such as 3f1c2a4e-8b9d-4e2f-a6c1-0d7e5b3a9f12", field = field)
        }

    private fun body(request: DebugRequest): JSONObject {
        val text = request.bodyAsString()?.takeIf { it.isNotBlank() } ?: throw RouteException("The body must be a JSON object")
        return try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw RouteException("The body is not a JSON object: ${e.message}")
        }
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

        private const val ITEM_PREFIX = "items/"
        private const val ITEM = "items/{id}"

        private val METHODS =
            mapOf(
                "items" to "GET",
                ITEM to "GET",
                "events" to "GET",
                "metrics" to "GET",
                "cancel" to "POST",
                "prune" to "POST",
            )

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
