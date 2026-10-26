package io.github.twinsen81.lustro.prefs

import android.content.Context
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugTab
import io.github.twinsen81.lustro.prefs.internal.PrefsRegistry
import io.github.twinsen81.lustro.prefs.internal.PrefsRoutes
import java.io.File

/**
 * The Preferences tab: the app's SharedPreferences files, to read and edit them,
 * under `/api/v1/prefs/`.
 *
 * Register it from debug code, as the Network tab:
 *
 * ```
 * Lustro.builder(app)
 *     .addTab(
 *         PrefsDebugTab.create(
 *             app,
 *             files = listOf(PrefsFile("settings", settingsPrefs, ValuePolicy.VISIBLE)),
 *         ),
 *     )
 *     .build()
 *     .start()
 * ```
 *
 * The tab shows the [registered files][PrefsFile] with the policy and the
 * read-only flag that the app gives each one, and, with discovery on, the other
 * files in the app's `shared_prefs` directory. The tab opens a discovered file
 * with `Context.getSharedPreferences`, and the platform then keeps it in memory
 * until the process ends. A discovered file shows its values as
 * [ValuePolicy.MASK_SENSITIVE] does, and is read-only unless the app turns
 * that off. A file that has the keysets of `EncryptedSharedPreferences`, read
 * by a plain instance, shows its keys only and is always read-only: an edit
 * would write a plain value into the encrypted file.
 *
 * Each file has a cursor, which moves when a value changes, through the tab or
 * through the app, so that a client polls a file and gets its entries again
 * only after a change. The tab sees the app's changes through a listener from
 * `Lustro.start()` to `Lustro.stop()`, also while the app is in the
 * background. Before API 30, `SharedPreferences.Editor.clear()` does not call
 * the listeners, so a clear by the app shows only with the next change to the
 * file. A clear through the tab always moves the cursor. Another process that
 * writes the file is not seen: the platform's instance keeps what it read.
 *
 * The tab has no console view yet: it serves its API, and stays out of the tab
 * bar. `GET /api/v1/prefs/_schema` describes its routes.
 */
public class PrefsDebugTab private constructor(private val registry: PrefsRegistry) : DebugTab() {
    override val id: String = "prefs"
    override val title: String = "Preferences"
    override val icon: String = "⚙️"
    override val order: Int = 30
    override val showInTabBar: Boolean = false

    private val routes = PrefsRoutes(registry)

    /** Serves the routes under `/api/v1/prefs/`; the static `prefs.openapi.json` describes them. */
    override fun handle(request: DebugRequest): DebugResponse? = routes.handle(request)

    /** Listens to the files for the app's changes. */
    override fun onStart() {
        registry.start()
    }

    /** Stops listening. The cursors keep their place, and a change made while stopped shows with the next one. */
    override fun onStop() {
        registry.stop()
    }

    /** Factory for [PrefsDebugTab]. */
    public companion object {
        /**
         * Creates a [PrefsDebugTab].
         *
         * @param context a context of the app. The tab keeps its application
         *   context, to find the files on disk and open them.
         * @param files the files to show with a policy of their own, in this
         *   order, before the discovered ones.
         * @param discover whether to show the other files in the app's
         *   `shared_prefs` directory too, such as the files of the app's
         *   libraries.
         * @param discoveredReadOnly whether the discovered files are read-only.
         *   An edit to a library's file can break that library.
         * @throws IllegalArgumentException when two of [files] have the same name.
         */
        @JvmStatic
        @JvmOverloads
        public fun create(
            context: Context,
            files: List<PrefsFile> = emptyList(),
            discover: Boolean = true,
            discoveredReadOnly: Boolean = true,
        ): PrefsDebugTab {
            val names = HashSet<String>()
            for (file in files) require(names.add(file.name)) { "Two files are named \"${file.name}\"" }
            val app = context.applicationContext ?: context
            val registry =
                PrefsRegistry(
                    files = files,
                    // Where ContextImpl keeps the files that getSharedPreferences opens.
                    directory = if (discover) File(app.dataDir, "shared_prefs") else null,
                    discoveredReadOnly = discoveredReadOnly,
                    open = { app.getSharedPreferences(it, Context.MODE_PRIVATE) },
                )
            return PrefsDebugTab(registry)
        }
    }
}
