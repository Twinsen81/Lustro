package io.github.twinsen81.lustro.prefs.internal

import android.content.SharedPreferences

/**
 * Behaves as `EncryptedSharedPreferences` does where the tab depends on it, under
 * a class name that R8 could have given it: it stores its entries in [platform],
 * the platform's instance of the file, next to two keyset entries; it hides the
 * keysets and throws `SecurityException` when it is asked for them; and its
 * editor's `clear()` removes the keys through the platform's editor and calls
 * none of its own listeners. Robolectric has no Android keystore, so a real
 * `EncryptedSharedPreferences` can't run in a unit test.
 */
internal class DecryptingPrefs(private val platform: SharedPreferences) : SharedPreferences {
    private val listeners = ArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

    init {
        platform.edit().apply { SharedPrefsSource.KEYSET_KEYS.forEach { putString(it, "keyset") } }.commit()
    }

    override fun getAll(): Map<String, *> = platform.all.filterKeys { it !in SharedPrefsSource.KEYSET_KEYS }

    override fun getString(key: String?, defValue: String?): String? = checked(key) { platform.getString(key, defValue) }

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = checked(key) { platform.getStringSet(key, defValues) }

    override fun getInt(key: String?, defValue: Int): Int = checked(key) { platform.getInt(key, defValue) }

    override fun getLong(key: String?, defValue: Long): Long = checked(key) { platform.getLong(key, defValue) }

    override fun getFloat(key: String?, defValue: Float): Float = checked(key) { platform.getFloat(key, defValue) }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = checked(key) { platform.getBoolean(key, defValue) }

    override fun contains(key: String?): Boolean = checked(key) { platform.contains(key) }

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners += listener
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners -= listener
    }

    private fun <T> checked(key: String?, read: () -> T): T {
        if (key in SharedPrefsSource.KEYSET_KEYS) throw SecurityException("$key is a reserved key for the encryption keyset.")
        return read()
    }

    private inner class Editor : SharedPreferences.Editor {
        private val editor = platform.edit()
        private val changed = ArrayList<String>()
        private var clear = false

        override fun putString(key: String, value: String?) = also { checked(key) { editor.putString(key, value) }; changed += key }

        override fun putStringSet(key: String, values: MutableSet<String>?) = also { checked(key) { editor.putStringSet(key, values) }; changed += key }

        override fun putInt(key: String, value: Int) = also { checked(key) { editor.putInt(key, value) }; changed += key }

        override fun putLong(key: String, value: Long) = also { checked(key) { editor.putLong(key, value) }; changed += key }

        override fun putFloat(key: String, value: Float) = also { checked(key) { editor.putFloat(key, value) }; changed += key }

        override fun putBoolean(key: String, value: Boolean) = also { checked(key) { editor.putBoolean(key, value) }; changed += key }

        override fun remove(key: String) = also { checked(key) { editor.remove(key) }; changed += key }

        override fun clear() = also { clear = true }

        override fun commit(): Boolean {
            if (clear) all.keys.filter { it !in changed }.forEach { editor.remove(it) }
            val written = editor.commit()
            for (listener in listeners.toList()) changed.forEach { listener.onSharedPreferenceChanged(this@DecryptingPrefs, it) }
            return written
        }

        override fun apply() {
            commit()
        }
    }
}
