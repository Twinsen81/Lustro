package io.github.twinsen81.lustro.prefs.internal

import android.annotation.SuppressLint
import android.content.SharedPreferences
import io.github.twinsen81.lustro.prefs.ValuePolicy

/** A SharedPreferences file of the Preferences tab, and its instance. */
internal class SharedPrefsSource(
    name: String,
    val prefs: SharedPreferences,
    policy: ValuePolicy,
    readOnly: Boolean,
    registered: Boolean,
    /**
     * Opens the platform's instance of the file, which the tab listens to as well
     * when [prefs] is another one, such as an `EncryptedSharedPreferences` over it.
     */
    private val openPlatform: (() -> SharedPreferences)? = null,
) : PrefsSource(name, PrefsStore.SHARED_PREFS, policy, readOnly, registered) {
    // SharedPreferences holds its listeners weakly, so the source keeps this one.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> changed() }

    // Guarded by this.
    private var listening = false

    // Guarded by this. The platform's instance, while the tab listens to it.
    private var platform: SharedPreferences? = null

    /**
     * Whether the instance is an `EncryptedSharedPreferences`, which decrypts what
     * it reads. R8 can rename the class, so a lookup of a keyset's key, which such
     * an instance refuses, decides too. Lazy, so that a plain instance is asked on
     * a request's thread, which may wait for the file to load.
     */
    override val decrypting: Boolean by lazy {
        prefs.javaClass.name == ENCRYPTED_PREFS_CLASS ||
            try {
                prefs.contains(KEYSET_KEYS[0])
                false
            } catch (_: SecurityException) {
                true
            }
    }

    /**
     * Listens to the instance and, when it is another one, to the platform's
     * instance of the file too: `EncryptedSharedPreferences.Editor.clear()`
     * removes the keys through the platform's editor and calls no listener of
     * its own, on every API level, while the platform's instance reports each key.
     */
    @Synchronized
    override fun listen() {
        if (listening) return
        prefs.registerOnSharedPreferenceChangeListener(listener)
        platform = platformInstance()?.also { it.registerOnSharedPreferenceChangeListener(listener) }
        listening = true
    }

    @Synchronized
    override fun unlisten() {
        if (!listening) return
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        platform?.unregisterOnSharedPreferenceChangeListener(listener)
        platform = null
        listening = false
    }

    // The platform keeps one instance for each file, so for a plain registered
    // file this is prefs itself. Opening can fail, for example in direct boot,
    // and then the tab listens to prefs only.
    private fun platformInstance(): SharedPreferences? =
        try {
            openPlatform?.invoke()?.takeIf { it !== prefs }
        } catch (_: RuntimeException) {
            null
        }

    override fun read(): Contents {
        val entries =
            try {
                prefs.all
            } catch (e: RuntimeException) {
                throw UnreadableFileException("Can't read the file $name: $e", e)
            }
        // An encrypted file read plain: its values are ciphertext, and a plain value would break the file for the app.
        val rawEncrypted = KEYSET_KEYS.any { it in entries }
        return Contents(
            entries,
            encrypted = decrypting || rawEncrypted,
            policy = if (rawEncrypted) ValuePolicy.KEYS_ONLY else policy,
            readOnly = rawEncrypted || readOnly,
        )
    }

    /**
     * Not when the app made the file read-only, and never when it is an encrypted
     * file read without its keys, where a plain value would break the file for the app.
     */
    override fun writable(): Boolean =
        // A decrypting instance hides the keysets, and throws when it is asked for them.
        !readOnly && (decrypting || KEYSET_KEYS.none { prefs.contains(it) })

    // commit(), not apply(), so that the response shows what the file holds, and says when the disk write failed.
    @SuppressLint("ApplySharedPref")
    override fun write(change: PrefChange) {
        val written =
            try {
                prefs.edit().apply { make(change) }.commit()
            } catch (e: RuntimeException) {
                throw WriteFailedException("Can't write the file $name: $e", e)
            }
        if (!written) throw WriteFailedException("The file $name changed in memory, but its write to disk failed")
    }

    private fun SharedPreferences.Editor.make(change: PrefChange) {
        when (change) {
            is PrefChange.Put -> put(change.key, change.type, change.value)
            is PrefChange.Remove -> remove(change.key)
            PrefChange.Clear -> clear()
        }
    }

    private fun SharedPreferences.Editor.put(key: String, type: PrefType, value: Any) {
        @Suppress("UNCHECKED_CAST")
        when (type) {
            PrefType.STRING -> putString(key, value as String)
            PrefType.INT -> putInt(key, value as Int)
            PrefType.LONG -> putLong(key, value as Long)
            PrefType.FLOAT -> putFloat(key, value as Float)
            PrefType.BOOLEAN -> putBoolean(key, value as Boolean)
            PrefType.STRING_SET -> putStringSet(key, value as Set<String>)
            PrefType.DOUBLE, PrefType.BYTES, PrefType.UNKNOWN -> error("${type.wireName} is not a SharedPreferences type")
        }
    }

    companion object {
        const val ENCRYPTED_PREFS_CLASS: String = "androidx.security.crypto.EncryptedSharedPreferences"

        /** The keys under which `EncryptedSharedPreferences` keeps its keysets, in the file that it encrypts. */
        val KEYSET_KEYS: List<String> =
            listOf(
                "__androidx_security_crypto_encrypted_prefs_key_keyset__",
                "__androidx_security_crypto_encrypted_prefs_value_keyset__",
            )
    }
}
