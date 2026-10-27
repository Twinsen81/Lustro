package io.github.twinsen81.lustro.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences

/**
 * A Preferences DataStore that the app registers with [PrefsDebugTab.create]
 * (no-op build).
 *
 * Mirrors the `:lustro-prefs` [PrefsDataStore]: it holds what the app passes, and
 * the no-op tab never reads [dataStore]. The name is checked as in the runtime,
 * so that a name the runtime rejects fails in release too.
 *
 * @property name the store's name, as the app passes it to `preferencesDataStore`.
 * @property dataStore the instance that the runtime tab reads and edits.
 * @property policy what the runtime tab shows of the values.
 * @property readOnly whether the runtime tab refuses to edit, delete, and clear.
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
