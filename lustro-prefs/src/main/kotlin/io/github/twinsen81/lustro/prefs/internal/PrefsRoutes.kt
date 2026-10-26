package io.github.twinsen81.lustro.prefs.internal

import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.escapeForJson
import io.github.twinsen81.lustro.prefs.ValuePolicy
import io.github.twinsen81.lustro.prefs.internal.PrefValues.appendItem
import org.json.JSONException
import org.json.JSONObject

/** The routes of the Preferences tab, under `/api/v1/prefs/`. */
internal class PrefsRoutes(private val registry: PrefsRegistry) {
    fun handle(request: DebugRequest): DebugResponse? {
        val expected = METHODS[request.path] ?: return null
        if (request.method.uppercase() != expected) {
            return DebugResponse.error("${request.path} takes $expected", status = 405)
        }
        return try {
            when (request.path) {
                "files" -> files()
                "entries" -> entries(request)
                "entry" -> entry(request)
                "edit" -> edit(body(request))
                "delete" -> delete(body(request))
                "clear" -> clear(body(request))
                else -> null
            }
        } catch (e: RouteException) {
            DebugResponse.error(e.message.orEmpty(), status = e.status, code = e.code, field = e.field)
        } catch (e: UnreadableFileException) {
            DebugResponse.error(e.message.orEmpty(), status = 500)
        } catch (e: WriteFailedException) {
            DebugResponse.error(e.message.orEmpty(), status = 500)
        }
    }

    private fun files(): DebugResponse {
        val files = registry.files()
        return DebugResponse.json {
            append("{\"items\":[")
            files.forEachIndexed { index, source ->
                if (index > 0) append(',')
                // The cursor before the read, so that a change during it moves the cursor again.
                val cursor = source.cursor()
                append("{\"name\":\"").append(source.name.escapeForJson()).append('"')
                append(",\"store\":\"").append(source.store.wireName).append('"')
                append(",\"registered\":").append(source.registered)
                try {
                    val contents = source.read()
                    append(",\"policy\":\"").append(contents.policy.wireName).append('"')
                    append(",\"readOnly\":").append(contents.readOnly)
                    append(",\"encrypted\":").append(contents.encrypted)
                    append(",\"keyCount\":").append(contents.entries.size)
                } catch (e: UnreadableFileException) {
                    append(",\"policy\":\"").append(source.policy.wireName).append('"')
                    append(",\"readOnly\":").append(source.readOnly)
                    append(",\"encrypted\":").append(source.decrypting)
                    append(",\"keyCount\":0")
                    append(",\"error\":\"").append(e.message.orEmpty().escapeForJson()).append('"')
                }
                append(",\"cursor\":\"").append(cursor.escapeForJson()).append("\"}")
            }
            append("]}")
        }
    }

    private fun entries(request: DebugRequest): DebugResponse {
        val source = source(store(request.queryParam("store")), request.queryParam("file"))
        // The sequence before the entries, so that a change while they are read moves the cursor past them.
        return DebugResponse.cursorEnvelope(
            currentSequence = source.currentSequence,
            clientCursor = request.queryParam("cursor"),
            epoch = source.epoch,
        ) {
            // Read only when the client lacks them. An unreadable file throws out of the envelope, to handle().
            val contents = source.read()
            contents.entries.keys.sorted().forEachIndexed { index, key ->
                if (index > 0) append(',')
                appendItem(key, contents.entries[key], contents.masked(key), PrefValues.MAX_VALUE_CHARS)
            }
        }
    }

    private fun entry(request: DebugRequest): DebugResponse {
        val source = source(store(request.queryParam("store")), request.queryParam("file"))
        val key = request.queryParam("key") ?: throw RouteException("key is required", field = "key")
        val contents = source.read()
        if (key !in contents.entries) throw RouteException("The file ${source.name} has no key \"$key\"", status = 404, field = "key")
        return DebugResponse.json {
            append("{\"item\":")
            appendItem(key, contents.entries[key], contents.masked(key))
            append('}')
        }
    }

    private fun edit(body: JSONObject): DebugResponse {
        val source = writableSource(body)
        val key = key(body)
        val type = type(body, source.store)
        val value = value(body, type)
        val before = source.read().entries[key]
        write(source, changed = !PrefValues.same(before, value), PrefChange.Put(key, type, value))
        val after = source.read()
        return DebugResponse.json {
            append("{\"status\":\"ok\",\"item\":")
            appendItem(key, after.entries[key], after.masked(key), PrefValues.MAX_VALUE_CHARS)
            append('}')
        }
    }

    private fun delete(body: JSONObject): DebugResponse {
        val source = writableSource(body)
        val key = key(body)
        val existed = key in source.read().entries
        write(source, changed = existed, PrefChange.Remove(key))
        return DebugResponse.json { append("{\"status\":\"ok\",\"deleted\":").append(existed).append('}') }
    }

    private fun clear(body: JSONObject): DebugResponse {
        val source = writableSource(body)
        if (body.opt("confirm") != true) {
            throw RouteException("confirm must be true: clear removes every key of the file", field = "confirm")
        }
        val removed = source.read().entries.size
        write(source, changed = removed > 0, PrefChange.Clear)
        return DebugResponse.json { append("{\"status\":\"ok\",\"removed\":").append(removed).append('}') }
    }

    // The write returns when the file on disk has the change, so that the response shows what the file holds.
    private fun write(source: PrefsSource, changed: Boolean, change: PrefChange) {
        try {
            source.write(change)
        } finally {
            // A SharedPreferences instance's memory holds the change even when the disk write fails.
            if (changed) source.changed()
        }
    }

    private fun body(request: DebugRequest): JSONObject {
        val text = request.bodyAsString()?.takeIf { it.isNotBlank() } ?: throw RouteException("The body must be a JSON object", field = "body")
        return try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw RouteException("The body is not a JSON object: ${e.message}", field = "body")
        }
    }

    private fun key(body: JSONObject): String = body.opt("key") as? String ?: throw RouteException("key is required, as a JSON string", field = "key")

    private fun type(body: JSONObject, store: PrefsStore): PrefType {
        val name = body.opt("type") as? String ?: throw RouteException("type is required, as a JSON string", field = "type")
        return PrefType.forWireName(name)?.takeIf { it in store.writeTypes }
            ?: throw RouteException("type must be one of ${store.writeTypes.joinToString { it.wireName }} for ${store.label}", field = "type")
    }

    private fun value(body: JSONObject, type: PrefType): Any {
        if (body.isNull("value")) throw RouteException("value is required; POST delete removes a key", field = "value")
        return try {
            PrefValues.parse(type, body.get("value"))
        } catch (e: BadValueException) {
            throw RouteException(e.message.orEmpty(), field = "value")
        }
    }

    private fun writableSource(body: JSONObject): PrefsSource {
        val storeName = if (body.isNull("store")) null else body.opt("store") as? String ?: throw RouteException("store must be a JSON string", field = "store")
        val source = source(store(storeName), body.opt("file") as? String)
        if (!source.writable()) {
            throw RouteException("The file ${source.name} is read-only", status = 403, code = "read_only", field = "file")
        }
        return source
    }

    // Without a store, a SharedPreferences file, as before there were others.
    private fun store(name: String?): PrefsStore =
        if (name == null) {
            PrefsStore.SHARED_PREFS
        } else {
            PrefsStore.forWireName(name)
                ?: throw RouteException("store must be one of ${PrefsStore.entries.joinToString { it.wireName }}", field = "store")
        }

    private fun source(store: PrefsStore, name: String?): PrefsSource =
        registry.find(store, fileName(name)) ?: throw RouteException("No file named $name in the ${store.wireName} store", status = 404, field = "file")

    private fun fileName(name: String?): String {
        if (name.isNullOrEmpty()) throw RouteException("file is required", field = "file")
        if ('/' in name || '\\' in name) throw RouteException("file must be a file name, without / or \\", field = "file")
        return name
    }

    private class RouteException(
        message: String,
        val status: Int = 400,
        val code: String? = null,
        val field: String? = null,
    ) : Exception(message)

    companion object {
        private val METHODS =
            mapOf(
                "files" to "GET",
                "entries" to "GET",
                "entry" to "GET",
                "edit" to "POST",
                "delete" to "POST",
                "clear" to "POST",
            )

        val ValuePolicy.wireName: String
            get() =
                when (this) {
                    ValuePolicy.VISIBLE -> "visible"
                    ValuePolicy.MASK_SENSITIVE -> "mask_sensitive"
                    ValuePolicy.KEYS_ONLY -> "keys_only"
                }
    }
}
