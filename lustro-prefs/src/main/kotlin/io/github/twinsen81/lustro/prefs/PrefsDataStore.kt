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
 * @param unmasked the keys whose values [ValuePolicy.MASK_SENSITIVE] shows
 *   although they look like they hold a secret, such as an `oauth_scopes` key,
 *   which has `auth` in its name but holds no secret. A name matches a key with
 *   exactly that name, in any case: it is no pattern. It changes nothing under
 *   [ValuePolicy.VISIBLE], which shows every value, or [ValuePolicy.KEYS_ONLY],
 *   which shows none: the tab ignores it there, so that a change of the policy
 *   never shows more than the policy says.
 * @throws IllegalArgumentException when [name] is empty or contains `/` or `\`.
 */
public class PrefsDataStore @JvmOverloads constructor(
    public val name: String,
    public val dataStore: DataStore<Preferences>,
    public val policy: ValuePolicy = ValuePolicy.MASK_SENSITIVE,
    public val readOnly: Boolean = false,
    unmasked: Set<String> = emptySet(),
) {
    /** The keys whose values [ValuePolicy.MASK_SENSITIVE] shows, as the app passed them. A key matches in any case. */
    public val unmasked: Set<String> = unmasked.toSet()

    init {
        require(name.isNotEmpty() && '/' !in name && '\\' !in name) { "name must be a file name, without / or \\: \"$name\"" }
    }
}
