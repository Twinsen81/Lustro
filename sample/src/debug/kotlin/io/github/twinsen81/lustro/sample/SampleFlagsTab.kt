package io.github.twinsen81.lustro.sample

import android.app.Application
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugTab
import io.github.twinsen81.lustro.escapeForJson
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

/**
 * A custom, schema-backed [DebugTab] for the sample app: a "Feature Flags"
 * console.
 *
 * This tab is a deliberate SPI exercise. It demonstrates the four capabilities
 * the sample's custom tab is meant to cover:
 *
 * - **File upload** — `POST upload` accepts a POSTed JSON body (a flags file)
 *   and merges it into the in-memory flag set. The browser uploads a file the
 *   user picks, so this also exercises reading a request body in [handle].
 * - **Async polling** — `GET snapshot?cursor=<opaque>` returns the cursor
 *   envelope `{ cursor, status: reset|unchanged|delta, items? }`, exactly like
 *   the Network tab, built with [DebugResponse.cursorEnvelope]. Every mutation
 *   (toggle or upload) advances the cursor.
 * - **Modal UI** — `flags.js` opens the shared `debugModal` to upload a file.
 * - **Schema-backed** — the static `assets/lustro/flags.openapi.json` (in
 *   `src/debug/assets`) makes the tab appear in `/api/v1/_meta` agent discovery;
 *   without a schema a tab works in the UI but is invisible to agents.
 *
 * The tab UI (`flags.js`) and styles (`flags.css`) are EXTERNAL static assets
 * loaded after `shared.js` (CSP: no inline `on*=` handlers, only `data-action`
 * delegation), so [renderScript]/[renderStyles]/[schema] stay blank/null and the
 * server serves the `flags.*` assets by id.
 *
 * This is debug-only: it lives in `src/debug` and is never registered in the
 * release/no-op variant, matching the "custom tabs are debug-only" guidance.
 */
public class SampleFlagsTab(
    @Suppress("UNUSED_PARAMETER") application: Application,
) : DebugTab() {
    override val id: String = "flags"
    override val title: String = "Feature Flags"
    override val icon: String = "🚩"
    override val order: Int = 20

    // In-memory flag store. A monotonically increasing sequence backs the opaque
    // cursor so pollers can detect changes (mirrors the Network tab's cursor).
    private val lock = Any()
    private val flags = linkedMapOf<String, Flag>()
    private val sequence = AtomicLong(0)

    init {
        // Seed a few flags so the tab is populated on first open.
        putFlag(Flag("new-onboarding", "New onboarding flow", enabled = true))
        putFlag(Flag("dark-mode-v2", "Dark mode v2", enabled = false))
        putFlag(Flag("offline-sync", "Offline sync", enabled = true))
    }

    private data class Flag(val id: String, val description: String, val enabled: Boolean)

    private fun putFlag(flag: Flag) {
        synchronized(lock) { flags[flag.id] = flag }
    }

    /**
     * The tab's HTML body. Served at `/api/v1/flags/_view` and injected by
     * `shared.js` into `#lustro-tab-content`. It is static structure only — the
     * list is filled and the modal driven by the EXTERNAL `flags.js` via
     * `data-action` delegation (no inline `on*=` handlers, per the page CSP).
     * `renderScript`/`renderStyles` stay blank so the server serves the static
     * `flags.js` / `flags.css` assets by id.
     */
    override fun renderContent(): String =
        """
        <div class="flags-toolbar">
            <h3>Feature Flags</h3>
            <span id="flags-count" class="flags-count">0 flags</span>
            <button class="debug-btn debug-btn-primary" data-action="openUpload" style="margin-left:auto" title="Upload a flags file (JSON). Opens a modal where you can pick a file or paste contents, then merge them.">Upload flags…</button>
        </div>
        <div id="flags-list" class="flags-list"></div>

        <div id="flags-upload-modal" class="debug-modal">
            <div class="debug-modal-content small">
                <div class="debug-modal-header">
                    <span>Upload flags file</span>
                    <button class="debug-modal-close" data-action="closeUpload" title="Close without uploading.">×</button>
                </div>
                <div class="debug-modal-body">
                    <p class="flags-upload-hint">Pick a <code>.json</code> file or paste its contents. Accepts a bare array of <code>{id, description, enabled}</code> objects or an object with a top-level <code>flags</code> array.</p>
                    <input type="file" accept="application/json,.json" data-action="onUploadFile" title="Read a flags file into the box below.">
                    <textarea id="flags-upload-text" class="flags-upload-text" placeholder='[{"id":"my-flag","description":"My flag","enabled":true}]' title="Flags JSON to upload."></textarea>
                    <div class="flags-modal-actions">
                        <button class="debug-btn debug-btn-primary" data-action="submitUpload" title="POST the contents to /api/v1/flags/upload and merge the flags.">Upload</button>
                        <button class="debug-btn" data-action="closeUpload" title="Cancel.">Cancel</button>
                    </div>
                </div>
            </div>
        </div>
        """.trimIndent()

    override fun handle(request: DebugRequest): DebugResponse? {
        val method = request.method.uppercase()
        val path = request.path
        return when {
            path == "snapshot" && method == "GET" -> handleSnapshot(request)
            path == "toggle" && method == "POST" -> handleToggle(request.bodyAsString())
            path == "upload" && method == "POST" -> handleUpload(request.bodyAsString())
            else -> null
        }
    }

    // region Async polling (opaque cursor envelope)

    private fun handleSnapshot(request: DebugRequest): DebugResponse =
        DebugResponse.cursorEnvelope(
            currentSequence = sequence.get(),
            clientCursor = request.queryParam("cursor"),
        ) {
            val snapshot = synchronized(lock) { flags.values.toList() }
            snapshot.forEachIndexed { index, flag ->
                if (index > 0) append(",")
                append("{")
                append("\"id\":\"").append(flag.id.escapeForJson()).append("\",")
                append("\"description\":\"").append(flag.description.escapeForJson()).append("\",")
                append("\"enabled\":").append(flag.enabled)
                append("}")
            }
        }

    // endregion

    // region Mutations

    private fun handleToggle(body: String?): DebugResponse {
        val json =
            try {
                JSONObject(body ?: "{}")
            } catch (e: Exception) {
                return DebugResponse.error("Invalid JSON: ${e.message}")
            }
        val flagId = json.optString("id")
        if (flagId.isBlank()) {
            return DebugResponse.error("id is required", field = "id")
        }
        val updated =
            synchronized(lock) {
                val existing = flags[flagId] ?: return DebugResponse.notFound("Unknown flag: $flagId")
                val toggled = existing.copy(enabled = !existing.enabled)
                flags[flagId] = toggled
                toggled
            }
        sequence.incrementAndGet()
        return DebugResponse.json {
            append("{\"status\":\"ok\",\"id\":\"").append(updated.id.escapeForJson())
                .append("\",\"enabled\":").append(updated.enabled).append("}")
        }
    }

    /**
     * File upload route: accepts a posted JSON document (the uploaded file's
     * contents) describing flags, and merges them into the store.
     *
     * Accepts either a bare array `[{id,description,enabled}, ...]` or an object
     * `{"flags": [...]}` so a hand-written or exported file works either way.
     */
    private fun handleUpload(body: String?): DebugResponse {
        if (body.isNullOrBlank()) {
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
                Flag(
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

    // endregion
}
