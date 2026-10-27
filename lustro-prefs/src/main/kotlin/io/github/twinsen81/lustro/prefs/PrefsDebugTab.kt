package io.github.twinsen81.lustro.prefs

import android.content.Context
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugTab
import io.github.twinsen81.lustro.prefs.internal.PrefsRegistry
import io.github.twinsen81.lustro.prefs.internal.PrefsRoutes
import io.github.twinsen81.lustro.prefs.internal.PrefsView
import java.io.File

/**
 * The Preferences tab: the app's SharedPreferences files and Preferences
 * DataStores, to read and edit them, under `/api/v1/prefs/`.
 *
 * Register it from debug code, as the Network tab:
 *
 * ```
 * Lustro.builder(app)
 *     .addTab(
 *         PrefsDebugTab.create(
 *             app,
 *             files = listOf(PrefsFile("settings", settingsPrefs, ValuePolicy.VISIBLE)),
 *             dataStores = listOf(PrefsDataStore("app_state", app.appStateDataStore)),
 *         ),
 *     )
 *     .build()
 *     .start()
 * ```
 *
 * The tab shows the [registered files][PrefsFile] and
 * [registered data stores][PrefsDataStore] with the policy and the read-only
 * flag that the app gives each one, and, with discovery on, the other files in
 * the app's `shared_prefs` and `files/datastore` directories. The tab opens a discovered file
 * with `Context.getSharedPreferences`, and the platform then keeps it in memory
 * until the process ends. A discovered file shows its values as
 * [ValuePolicy.MASK_SENSITIVE] does, and is read-only unless the app turns
 * that off. When the app deletes a file with `Context.deleteSharedPreferences`
 * and opens it again, the tab reads the new instance. A file that has the
 * keysets of `EncryptedSharedPreferences`, read by a plain instance, shows its
 * keys only and is always read-only: an edit would write a plain value into
 * the encrypted file.
 *
 * The tab reads and writes a registered data store through the app's instance,
 * so the app sees an edit at once. A data store that the app does not register,
 * the tab reads from its `.preferences_pb` file, as [ValuePolicy.MASK_SENSITIVE]
 * does, and it is always read-only: DataStore keeps its data in memory and
 * would not see an edit to its file, and a second instance of a file makes the
 * app's DataStore throw. Only a store under `files/datastore` is discovered.
 *
 * Each file has a cursor, which moves when a value changes, through the tab or
 * through the app, so that a client polls a file and gets its entries again
 * only after a change. The tab sees the app's changes through a listener from
 * `Lustro.start()` to `Lustro.stop()`, also while the app is in the
 * background. Before API 30, `SharedPreferences.Editor.clear()` does not call
 * the listeners, so a clear by the app shows only with the next change to the
 * file. A clear through the tab always moves the cursor. Another process that
 * writes the file is not seen: the platform's instance keeps what it read. The
 * tab follows a registered data store's data over the same period, and it sees
 * a change to a discovered one when it reads the file again: on each poll.
 *
 * In the console, the tab shows the files as tabs, with each file's keys,
 * types, and values, and edits them where the file is not read-only.
 * `GET /api/v1/prefs/_schema` describes its routes.
 */
public class PrefsDebugTab private constructor(private val registry: PrefsRegistry) : DebugTab() {
    override val id: String = "prefs"
    override val title: String = "Preferences"
    override val icon: String = "⚙️"
    override val order: Int = 30

    private val routes = PrefsRoutes(registry)

    /** The view's static shell; `prefs.js` and `prefs.css` in the assets fill and style it. */
    override fun renderContent(): String = PrefsView.HTML

    /** Serves the routes under `/api/v1/prefs/`; the static `prefs.openapi.json` describes them. */
    override fun handle(request: DebugRequest): DebugResponse? = routes.handle(request)

    /** Listens to the files for the app's changes. */
    override fun onStart() {
        registry.start()
    }

    /**
     * Stops listening. The cursors keep their place. A change that the app makes
     * while stopped moves the cursor of a SharedPreferences file with the file's
     * next change, and the cursor of a registered data store at the next start.
     * A discovered data store's cursor moves on the next poll either way.
     */
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
         * @param files the SharedPreferences files to show with a policy of
         *   their own, in this order, before the discovered ones.
         * @param dataStores the Preferences DataStores to show with a policy of
         *   their own, in this order, after [files] and before the discovered ones.
         * @param discover whether to show the other files in the app's
         *   `shared_prefs` and `files/datastore` directories too, such as the
         *   files of the app's libraries.
         * @param discoveredReadOnly whether the discovered SharedPreferences files
         *   are read-only. An edit to a library's file can break that library.
         *   Lustro's own file, `lustro_debug`, which holds the token, is
         *   read-only either way, and so is a discovered data store.
         * @throws IllegalArgumentException when two of [files], or two of
         *   [dataStores], have the same name.
         */
        @JvmStatic
        @JvmOverloads
        public fun create(
            context: Context,
            files: List<PrefsFile> = emptyList(),
            dataStores: List<PrefsDataStore> = emptyList(),
            discover: Boolean = true,
            discoveredReadOnly: Boolean = true,
        ): PrefsDebugTab {
            val names = HashSet<String>()
            for (file in files) require(names.add(file.name)) { "Two files are named \"${file.name}\"" }
            val storeNames = HashSet<String>()
            for (store in dataStores) require(storeNames.add(store.name)) { "Two data stores are named \"${store.name}\"" }
            val app = context.applicationContext ?: context
            val registry =
                PrefsRegistry(
                    files = files,
                    // Where ContextImpl keeps the files that getSharedPreferences opens.
                    directory = if (discover) File(app.dataDir, "shared_prefs") else null,
                    discoveredReadOnly = discoveredReadOnly,
                    dataStores = dataStores,
                    // Where preferencesDataStore puts a store's file: filesDir/datastore. ContextImpl's
                    // filesDir is dataDir/files, and getFilesDir() would create it, on the caller's thread.
                    dataStoreDirectory = if (discover) File(app.dataDir, "files/datastore") else null,
                    open = { app.getSharedPreferences(it, Context.MODE_PRIVATE) },
                )
            return PrefsDebugTab(registry)
        }
    }
}
