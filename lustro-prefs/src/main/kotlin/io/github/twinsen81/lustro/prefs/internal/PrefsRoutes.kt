package io.github.twinsen81.lustro.prefs.internal

import io.github.twinsen81.lustro.DebugParam
import io.github.twinsen81.lustro.DebugParams
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugResponseException
import io.github.twinsen81.lustro.DebugRoutes
import io.github.twinsen81.lustro.DebugRoutes.Companion.ref
import io.github.twinsen81.lustro.ExperimentalDebugRoutes
import io.github.twinsen81.lustro.escapeForJson
import io.github.twinsen81.lustro.prefs.ValuePolicy
import io.github.twinsen81.lustro.prefs.internal.PrefValues.appendItem
import org.json.JSONTokener

/**
 * The routes of the Preferences tab, under `/api/v1/prefs/`. [declare] declares them, and the
 * tab's OpenAPI document comes from the declarations, with the text of [PrefsDocument].
 */
@OptIn(ExperimentalDebugRoutes::class)
internal class PrefsRoutes(private val registry: PrefsRegistry) {
    fun declare(routes: DebugRoutes) {
        routes.title = PrefsDocument.TITLE
        routes.version = "1.2"
        routes.description = PrefsDocument.DESCRIPTION
        for ((name, schema) in PrefsDocument.COMPONENTS) routes.component(name, schema)

        routes.get("files") {
            operationId = "listPrefsFiles"
            summary = "List the files, with their policies and cursors."
            description = PrefsDocument.FILES_DESCRIPTION
            response(PrefsDocument.FILES_RESPONSE, PrefsDocument.FILES_RESPONSE_SCHEMA)
            handle { files() }
        }
        routes.get("entries") {
            operationId = "listPrefEntries"
            summary = "Poll a file: every entry, after its value policy."
            description = PrefsDocument.ENTRIES_DESCRIPTION
            val store = query.store(PrefsDocument.STORE_PARAM)
            val file = query.file(minLength = 1)
            val cursor = query.string("cursor") { description = PrefsDocument.CURSOR }
            response(PrefsDocument.ENTRIES_RESPONSE, ref("PrefsCursorEnvelope"))
            errors(404, 500)
            handle { call -> guarded { entries(source(call[store], call[file]), call[cursor]) } }
        }
        routes.get("entry") {
            operationId = "getPrefEntry"
            summary = "Get one entry, with its whole value."
            description = PrefsDocument.ENTRY_DESCRIPTION
            val store = query.store(PrefsDocument.STORE_PARAM)
            val file = query.file(minLength = 1)
            val key = query.string("key") { description = PrefsDocument.ENTRY_KEY }.required()
            response(PrefsDocument.ENTRY_RESPONSE, PrefsDocument.ENTRY_RESPONSE_SCHEMA)
            errors(404, 500)
            handle { call -> guarded { entry(source(call[store], call[file]), call[key]) } }
        }
        routes.post("edit") {
            operationId = "editPref"
            summary = "Write one value, with its type."
            description = PrefsDocument.EDIT_DESCRIPTION
            bodyDescription = "The value to write."
            val store = body.store(null)
            val file = body.file(minLength = 0)
            val key = body.string("key") { description = PrefsDocument.EDIT_KEY }.required()
            // The store decides which types it can write, so the handler checks the type.
            val type = body.string("type") { schema = ref("PrefWriteType") }.required()
            val value = body.json("value") { description = PrefsDocument.VALUE; schema = PrefsDocument.VALUE_SCHEMA }.required()
            response(PrefsDocument.EDIT_RESPONSE, PrefsDocument.EDIT_RESPONSE_SCHEMA)
            errors(404, 500)
            handle { call -> guarded { edit(writableSource(call[store], call[file]), call[key], call[type], call[value]) } }
        }
        routes.post("delete") {
            operationId = "deletePref"
            summary = "Remove one key."
            description = PrefsDocument.DELETE_DESCRIPTION
            bodyDescription = "The key to remove."
            val store = body.store(null)
            val file = body.file(minLength = 0)
            val key = body.string("key") { description = PrefsDocument.DELETE_KEY }.required()
            response(PrefsDocument.DELETE_RESPONSE, PrefsDocument.DELETE_RESPONSE_SCHEMA)
            errors(404, 500)
            handle { call -> guarded { delete(writableSource(call[store], call[file]), call[key]) } }
        }
        routes.post("clear") {
            operationId = "clearPrefs"
            summary = "Remove every key of a file."
            description = PrefsDocument.CLEAR_DESCRIPTION
            bodyDescription = "The file to clear."
            val store = body.store(null)
            val file = body.file(minLength = 0)
            val confirm = body.boolean("confirm") { description = PrefsDocument.CONFIRM; schema = PrefsDocument.CONFIRM_SCHEMA }.required()
            response(PrefsDocument.CLEAR_RESPONSE, PrefsDocument.CLEAR_RESPONSE_SCHEMA)
            errors(404, 500)
            handle { call -> guarded { clear(writableSource(call[store], call[file]), call[confirm]) } }
        }
    }

    // A query file of "" is refused by the declaration; a body file of "" by fileName(), with the
    // message that the route had before its declarations.
    private fun DebugParams.file(minLength: Int): DebugParam<String> =
        string("file") {
            this.minLength = minLength
            description = PrefsDocument.FILE
        }.required()

    private fun DebugParams.store(description: String?): DebugParam<String?> =
        string("store") {
            values = PrefsStore.entries.map { it.wireName }
            schema = ref("PrefsStore")
            this.description = description
        }

    private inline fun guarded(answer: () -> DebugResponse): DebugResponse =
        try {
            answer()
        } catch (e: UnreadableFileException) {
            DebugResponse.error(e.message.orEmpty(), status = 500)
        } catch (e: WriteFailedException) {
            DebugResponse.error(e.message.orEmpty(), status = 500)
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

    private fun entries(source: PrefsSource, cursor: String?): DebugResponse =
        // The sequence before the entries, so that a change while they are read moves the cursor past them.
        DebugResponse.cursorEnvelope(
            currentSequence = source.currentSequence,
            clientCursor = cursor,
            epoch = source.epoch,
        ) {
            // Read only when the client lacks them. An unreadable file throws out of the envelope, to guarded().
            val contents = source.read()
            contents.entries.keys.sorted().forEachIndexed { index, key ->
                if (index > 0) append(',')
                appendEntry(contents, key, PrefValues.MAX_VALUE_CHARS)
            }
        }

    private fun entry(source: PrefsSource, key: String): DebugResponse {
        val contents = source.read()
        if (key !in contents.entries) fail("The file ${source.name} has no key \"$key\"", status = 404, field = "key")
        return DebugResponse.json {
            append("{\"item\":")
            appendEntry(contents, key)
            append('}')
        }
    }

    private fun edit(source: PrefsSource, key: String, typeName: String, valueJson: String): DebugResponse {
        // Often a client's mistake, such as a shell variable that did not expand. Delete still takes it,
        // so that a client can remove such a key.
        if (key.isEmpty()) fail("key must not be empty", field = "key")
        val type = type(typeName, source.store)
        val value = value(valueJson, type)
        val before = source.read().entries[key]
        write(source, changed = !PrefValues.same(before, value), PrefChange.Put(key, type, value))
        val after = source.read()
        return DebugResponse.json {
            append("{\"status\":\"ok\",\"item\":")
            appendEntry(after, key, PrefValues.MAX_VALUE_CHARS)
            append('}')
        }
    }

    private fun delete(source: PrefsSource, key: String): DebugResponse {
        val existed = key in source.read().entries
        write(source, changed = existed, PrefChange.Remove(key))
        return DebugResponse.json { append("{\"status\":\"ok\",\"deleted\":").append(existed).append('}') }
    }

    private fun clear(source: PrefsSource, confirm: Boolean): DebugResponse {
        if (!confirm) fail("confirm must be true: clear removes every key of the file", field = "confirm")
        val removed = source.read().entries.size
        write(source, changed = removed > 0, PrefChange.Clear)
        return DebugResponse.json { append("{\"status\":\"ok\",\"removed\":").append(removed).append('}') }
    }

    private fun StringBuilder.appendEntry(contents: Contents, key: String, maxChars: Int = Int.MAX_VALUE) {
        appendItem(key, contents.entries[key], contents.masked(key), maxChars, contents.redacted(key))
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

    private fun type(name: String, store: PrefsStore): PrefType =
        PrefType.forWireName(name)?.takeIf { it in store.writeTypes }
            ?: fail("type must be one of ${store.writeTypes.joinToString { it.wireName }} for ${store.label}", field = "type")

    // The value is the JSON text of the body's value, which the router read: parsed as org.json reads a body.
    private fun value(json: String, type: PrefType): Any =
        try {
            PrefValues.parse(type, JSONTokener(json).nextValue())
        } catch (e: BadValueException) {
            fail(e.message.orEmpty(), field = "value")
        }

    private fun writableSource(storeName: String?, fileName: String): PrefsSource {
        val source = source(storeName, fileName)
        if (!source.writable()) fail("The file ${source.name} is read-only", status = 403, code = "read_only", field = "file")
        return source
    }

    // Without a store, a SharedPreferences file, as before there were others. The declaration refuses another name.
    private fun source(storeName: String?, name: String): PrefsSource {
        val store = storeName?.let { PrefsStore.forWireName(it) } ?: PrefsStore.SHARED_PREFS
        return registry.find(store, fileName(name)) ?: fail("No file named $name in the ${store.wireName} store", status = 404, field = "file")
    }

    private fun fileName(name: String): String {
        if (name.isEmpty()) fail("file is required", field = "file")
        if ('/' in name || '\\' in name) fail("file must be a file name, without / or \\", field = "file")
        return name
    }

    private fun fail(message: String, status: Int = 400, code: String? = null, field: String? = null): Nothing =
        throw DebugResponseException(DebugResponse.error(message, status = status, code = code, field = field))

    companion object {
        val ValuePolicy.wireName: String
            get() =
                when (this) {
                    ValuePolicy.VISIBLE -> "visible"
                    ValuePolicy.MASK_SENSITIVE -> "mask_sensitive"
                    ValuePolicy.KEYS_ONLY -> "keys_only"
                }
    }
}
