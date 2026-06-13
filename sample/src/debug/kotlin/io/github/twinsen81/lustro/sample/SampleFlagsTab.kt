package io.github.twinsen81.lustro.sample

import android.app.Application
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugTab
import io.github.twinsen81.lustro.escapeForJson
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

/** Debug-only sample tab for cursor polling, modal upload, and schema discovery. */
public class SampleFlagsTab(
    @Suppress("UNUSED_PARAMETER") application: Application,
) : DebugTab() {
    override val id: String = "flags"
    override val title: String = "Sample Client Feature Flags"
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

    // Static shell; flags.js owns rendering and event delegation.
    override fun renderContent(): String =
        """
        <div class="flags-toolbar">
            <h3>Sample Client Feature Flags</h3>
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

    // Accepts either a bare array or {"flags": [...]}.
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

}
