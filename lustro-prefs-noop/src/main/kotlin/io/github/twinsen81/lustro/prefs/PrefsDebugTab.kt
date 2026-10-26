package io.github.twinsen81.lustro.prefs

import android.content.Context
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugTab

/**
 * The Preferences tab (no-op build).
 *
 * Mirrors the `:lustro-prefs` [PrefsDebugTab] public surface so that the app's
 * release code compiles unchanged. The tab's identity ([id], [title], [icon],
 * [order]) is the same, but it reads no files, and [handle] returns `null`.
 */
public class PrefsDebugTab private constructor() : DebugTab() {
    override val id: String = "prefs"
    override val title: String = "Preferences"
    override val icon: String = "⚙️"
    override val order: Int = 30

    /** Returns `null`; the no-op build routes nothing. */
    override fun handle(request: DebugRequest): DebugResponse? = null

    /** Factory for [PrefsDebugTab]. */
    public companion object {
        /**
         * Creates a [PrefsDebugTab].
         *
         * @param context a context of the app.
         * @param files the files to show with a policy of their own.
         * @param discover whether to show the other files in the app's `shared_prefs` directory too.
         * @param discoveredReadOnly whether the discovered files are read-only;
         *   Lustro's own file, `lustro_debug`, is read-only either way.
         * @throws IllegalArgumentException when two of [files] have the same name.
         *
         * No-op build: the tab keeps no context and opens no file. The names are
         * only checked, so that a list the runtime rejects fails in release too.
         */
        @JvmStatic
        @JvmOverloads
        @Suppress("UNUSED_PARAMETER")
        public fun create(
            context: Context,
            files: List<PrefsFile> = emptyList(),
            discover: Boolean = true,
            discoveredReadOnly: Boolean = true,
        ): PrefsDebugTab {
            val names = HashSet<String>()
            for (file in files) require(names.add(file.name)) { "Two files are named \"${file.name}\"" }
            return PrefsDebugTab()
        }
    }
}
