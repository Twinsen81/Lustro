package io.github.twinsen81.lustro.prefs

import android.content.SharedPreferences

/**
 * A SharedPreferences file that the app registers with [PrefsDebugTab.create].
 *
 * @property name the file's name, as the app passes it to
 *   `Context.getSharedPreferences`. The API names the file by it, and discovery
 *   leaves out the file on disk with this name, so that [prefs] stands for it.
 * @property prefs the instance that the tab reads and edits. It can be an
 *   `EncryptedSharedPreferences` that decrypts: then the tab shows the plain
 *   values, as [policy] lets it.
 * @property policy what the tab shows of the values.
 * @property readOnly whether the tab refuses to edit, delete, and clear: those
 *   routes answer `403`.
 * @throws IllegalArgumentException when [name] is empty or contains `/` or `\`.
 */
public class PrefsFile @JvmOverloads constructor(
    public val name: String,
    public val prefs: SharedPreferences,
    public val policy: ValuePolicy = ValuePolicy.MASK_SENSITIVE,
    public val readOnly: Boolean = false,
) {
    init {
        require(name.isNotEmpty() && '/' !in name && '\\' !in name) { "name must be a file name, without / or \\: \"$name\"" }
    }
}
