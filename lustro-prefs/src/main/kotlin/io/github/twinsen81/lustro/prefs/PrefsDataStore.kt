package io.github.twinsen81.lustro.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences

/**
 * A Preferences DataStore that the app registers with [PrefsDebugTab.create].
 *
 * The tab reads and edits [dataStore] itself, so that the app sees an edit at
 * once. DataStore allows one instance for each file in a process: the tab never
 * opens one of its own, and it reads a store that the app does not register
 * from its file, read-only.
 *
 * DataStore is a `compileOnly` dependency of this artifact: the app that
 * registers a store has DataStore 1.0 or newer, and coroutines with it. A byte
 * array value needs DataStore 1.1.
 *
 * @property name the store's name, as the app passes it to `preferencesDataStore`:
 *   the file on disk is `files/datastore/<name>.preferences_pb`. The API names
 *   the store by it, and discovery leaves out the file with this name, so that
 *   [dataStore] stands for it.
 * @property dataStore the instance that the tab reads and edits: the app's own.
 * @property policy what the tab shows of the values.
 * @property readOnly whether the tab refuses to edit, delete, and clear: those
 *   routes answer `403`.
 * @throws IllegalArgumentException when [name] is empty or contains `/` or `\`.
 */
public class PrefsDataStore @JvmOverloads constructor(
    public val name: String,
    public val dataStore: DataStore<Preferences>,
    public val policy: ValuePolicy = ValuePolicy.MASK_SENSITIVE,
    public val readOnly: Boolean = false,
) {
    init {
        require(name.isNotEmpty() && '/' !in name && '\\' !in name) { "name must be a file name, without / or \\: \"$name\"" }
    }
}
