package io.github.twinsen81.lustro.sample

import android.app.Application
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugRoutes
import io.github.twinsen81.lustro.DebugTab
import io.github.twinsen81.lustro.ExperimentalDebugRoutes
import io.github.twinsen81.lustro.escapeForJson
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug-only sample tab for cursor polling, modal upload, and schema discovery.
 * The app reads the flags through [flags], so a toggle in the console changes
 * the app, and a toggle in the app shows in the console on its next poll.
 *
 * The tab declares its routes in [routes]: Lustro routes the requests, checks
 * their parameters, and makes the tab's OpenAPI document from the declarations.
 */
@OptIn(ExperimentalDebugRoutes::class)
public class SampleFlagsTab(
    application: Application,
) : DebugTab() {
    override val id: String = "flags"
    override val title: String = "Sample Client Feature Flags"
    override val icon: String = "🚩"

    // The app's own tab goes after the library's tabs and the plugins' tabs.
    override val order: Int = Int.MAX_VALUE

    // In-memory flag store. A monotonically increasing sequence backs the opaque
    // cursor so pollers can detect changes (mirrors the Network tab's cursor).
    private val lock = Any()
    private val byId = linkedMapOf<String, SampleFlag>()
    private val sequence = AtomicLong(0)
    private val state = MutableStateFlow<List<SampleFlag>>(emptyList())

    internal val flags: StateFlow<List<SampleFlag>> = state.asStateFlow()

    // The system setting that the dark theme flag last followed. A toggle of the flag
    // lasts until the system setting changes.
    private var systemDark = SampleFlags.isSystemDark(application.resources.configuration)

    init {
        // Seed a few flags so the tab is populated on first open. The app reads
        // the first two; see SampleFlags.
        putFlag(SampleFlag(SampleFlags.HOME_INTRO, "Intro on the sample's home screen", enabled = true))
        putFlag(SampleFlag(SampleFlags.DARK_THEME, "Dark theme for the sample app", enabled = systemDark))
        putFlag(SampleFlag("offline-sync", "Offline sync (the sample does not read it)", enabled = true))
    }

    /** Sets a flag. Returns false when there is no flag with [id]. */
    internal fun setEnabled(id: String, enabled: Boolean): Boolean {
        synchronized(lock) {
            val existing = byId[id] ?: return false
            if (existing.enabled == enabled) return true
            byId[id] = existing.copy(enabled = enabled)
            state.value = byId.values.toList()
        }
        sequence.incrementAndGet()
        return true
    }

    /** Sets the dark theme flag to [dark] when the system setting changed since the last call. */
    internal fun onSystemDarkTheme(dark: Boolean) {
        synchronized(lock) {
            if (dark == systemDark) return
            systemDark = dark
        }
        setEnabled(SampleFlags.DARK_THEME, dark)
    }

    private fun putFlag(flag: SampleFlag) {
        synchronized(lock) {
            byId[flag.id] = flag
            state.value = byId.values.toList()
        }
    }

    // Static shell; flags.js owns rendering and event delegation.
    override fun renderContent(): String =
        """
        <div class="dc-toolbar">
            <h3 class="dc-mono-label">Sample Client Feature Flags</h3>
            <span id="flags-count" class="flags-count">0 flags</span>
            <button class="dc-btn dc-btn--primary" data-action="openUpload" style="margin-left:auto" title="Upload a flags file (JSON). Opens a modal where you can pick a file or paste contents, then merge them.">Upload flags…</button>
        </div>
        <div id="flags-list" class="flags-list"></div>

        <div id="flags-upload-modal" class="dc-modal-scrim" hidden>
            <div class="dc-modal">
                <div class="dc-modal__head">
                    <span class="dc-modal__title">Upload flags file</span>
                    <button class="dc-modal__close" data-action="closeUpload" title="Close without uploading.">×</button>
                </div>
                <div class="dc-modal__body">
                    <p class="flags-upload-hint">Pick a <code>.json</code> file or paste its contents. Accepts a bare array of <code>{id, description, enabled}</code> objects or an object with a top-level <code>flags</code> array.</p>
                    <input type="file" accept="application/json,.json" data-action="onUploadFile" title="Read a flags file into the box below.">
                    <textarea id="flags-upload-text" class="dc-textarea flags-upload-text" placeholder='[{"id":"my-flag","description":"My flag","enabled":true}]' title="Flags JSON to upload."></textarea>
                </div>
                <div class="dc-modal__foot">
                    <button class="dc-btn dc-btn--primary" data-action="submitUpload" title="POST the contents to /api/v1/flags/upload and merge the flags.">Upload</button>
                    <button class="dc-btn" data-action="closeUpload" title="Cancel.">Cancel</button>
                </div>
            </div>
        </div>
        """.trimIndent()

    override fun routes(routes: DebugRoutes) {
        routes.version = "1.0"
        routes.description =
            "A sample custom Lustro tab demonstrating async polling (opaque cursor), a file-upload route, and modal UI. All routes are " +
            "token-authenticated (Bearer header or HttpOnly cookie) and live under /api/v1/flags. Errors use the shared error envelope."
        routes.component(
            "Flag",
            """
            {
              "type": "object",
              "required": ["id"],
              "properties": {
                "id": {"type": "string"},
                "description": {"type": "string"},
                "enabled": {"type": "boolean", "default": false}
              }
            }
            """,
        )
        routes.component(
            "FlagCursorEnvelope",
            """
            {
              "type": "object",
              "required": ["cursor", "status"],
              "properties": {
                "cursor": {"type": "string", "description": "Opaque cursor token."},
                "status": {"type": "string", "enum": ["delta", "unchanged", "reset"]},
                "items": {"type": "array", "items": ${DebugRoutes.ref("Flag")}}
              }
            }
            """,
        )

        routes.get("snapshot") {
            operationId = "snapshotFlags"
            summary = "Poll the current feature-flag set with an opaque cursor."
            description =
                "Short-polling. Send the last cursor back each call. status=reset returns the full current flag list; status=delta " +
                "returns the current list after a change; status=unchanged omits items. Treat unknown statuses as reset. Every " +
                "mutation (toggle, upload) advances the cursor."
            val cursor = query.string("cursor") { description = "Opaque cursor from the previous response. Omit on first poll." }
            response("Cursor envelope.", DebugRoutes.ref("FlagCursorEnvelope"))
            handle { call -> snapshot(call[cursor]) }
        }

        routes.post("toggle") {
            operationId = "toggleFlag"
            summary = "Toggle a feature flag's enabled state."
            // Also {"id": ...} as JSON: `curl -d id=dark-mode-v2` sends a form.
            acceptsForm = true
            val flagId = body.string("id") { minLength = 1 }.required()
            response(
                "New state.",
                """
                {
                  "type": "object",
                  "required": ["status", "id", "enabled"],
                  "properties": {"status": {"const": "ok"}, "id": {"type": "string"}, "enabled": {"type": "boolean"}}
                }
                """,
            )
            errors(404)
            handle { call -> toggle(call[flagId]) }
        }

        routes.post("upload") {
            operationId = "uploadFlags"
            summary = "Upload a flags file and merge its definitions."
            description =
                "Accepts the raw contents of an uploaded flags file: either a bare array of flag objects or an object with a " +
                "top-level \"flags\" array. Existing flags with the same id are replaced; new ids are added. Advances the cursor " +
                "when at least one flag is imported."
            rawBody(
                "application/json",
                """
                {
                  "oneOf": [
                    {"type": "array", "items": ${DebugRoutes.ref("Flag")}},
                    {"type": "object", "required": ["flags"], "properties": {"flags": {"type": "array", "items": ${DebugRoutes.ref("Flag")}}}}
                  ]
                }
                """,
            )
            response(
                "Import result.",
                """
                {
                  "type": "object",
                  "required": ["status", "imported"],
                  "properties": {"status": {"const": "ok"}, "imported": {"type": "integer"}}
                }
                """,
            )
            handle { call -> upload(call.request.bodyAsString().orEmpty()) }
        }
    }

    private fun snapshot(cursor: String?): DebugResponse =
        DebugResponse.cursorEnvelope(
            currentSequence = sequence.get(),
            clientCursor = cursor,
        ) {
            val snapshot = synchronized(lock) { byId.values.toList() }
            snapshot.forEachIndexed { index, flag ->
                if (index > 0) append(",")
                append("{")
                append("\"id\":\"").append(flag.id.escapeForJson()).append("\",")
                append("\"description\":\"").append(flag.description.escapeForJson()).append("\",")
                append("\"enabled\":").append(flag.enabled)
                append("}")
            }
        }

    private fun toggle(flagId: String): DebugResponse {
        val updated =
            synchronized(lock) {
                val existing = byId[flagId] ?: return DebugResponse.notFound("Unknown flag: $flagId")
                val toggled = existing.copy(enabled = !existing.enabled)
                byId[flagId] = toggled
                state.value = byId.values.toList()
                toggled
            }
        sequence.incrementAndGet()
        return DebugResponse.json {
            append("{\"status\":\"ok\",\"id\":\"").append(updated.id.escapeForJson())
                .append("\",\"enabled\":").append(updated.enabled).append("}")
        }
    }

    // Accepts either a bare array or {"flags": [...]}. The router answers an empty body.
    private fun upload(body: String): DebugResponse {
        if (body.isBlank()) {
            return DebugResponse.error("Empty upload", field = "body")
        }
        val array =
            try {
                parseFlagsArray(body)
            } catch (e: Exception) {
                return DebugResponse.error("Invalid flags file: ${e.message}")
            }
        var imported = 0
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val flagId = obj.optString("id")
            if (flagId.isBlank()) continue
            putFlag(
                SampleFlag(
                    id = flagId,
                    description = obj.optString("description", flagId),
                    enabled = obj.optBoolean("enabled", false),
                ),
            )
            imported++
        }
        if (imported > 0) sequence.incrementAndGet()
        return DebugResponse.json {
            append("{\"status\":\"ok\",\"imported\":").append(imported).append("}")
        }
    }

    private fun parseFlagsArray(body: String): JSONArray {
        val trimmed = body.trim()
        return if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            JSONObject(trimmed).optJSONArray("flags") ?: JSONArray()
        }
    }
}
