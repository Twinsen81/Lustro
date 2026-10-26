package io.github.twinsen81.lustro.prefs

import android.content.SharedPreferences

/**
 * A SharedPreferences file that the app registers with [PrefsDebugTab.create]
 * (no-op build).
 *
 * Mirrors the `:lustro-prefs` [PrefsFile]: it holds what the app passes, and the
 * no-op tab never reads [prefs]. The name is checked as in the runtime, so that
 * a name the runtime rejects fails in release too.
 *
 * @property name the file's name, as the app passes it to `Context.getSharedPreferences`.
 * @property prefs the instance that the runtime tab reads and edits.
 * @property policy what the runtime tab shows of the values.
 * @property readOnly whether the runtime tab refuses to edit, delete, and clear.
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
