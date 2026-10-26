package io.github.twinsen81.lustro.prefs.internal

import android.content.SharedPreferences
import io.github.twinsen81.lustro.CursorCodec
import io.github.twinsen81.lustro.SensitiveKeys
import io.github.twinsen81.lustro.prefs.ValuePolicy
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * One file of the Preferences tab: its instance, what the tab may show and do
 * with it, and the sequence that its cursor carries.
 */
internal class PrefsSource(
    val name: String,
    val prefs: SharedPreferences,
    /** The policy that the app chose. [Contents.policy] is the one that applies. */
    val policy: ValuePolicy,
    /** Whether the app made the file read-only. [writable] also checks the file. */
    val readOnly: Boolean,
    val registered: Boolean,
) {
    private val sequence = AtomicLong()

    /** A file has an epoch of its own, so that the cursor of one file is foreign to another. */
    val epoch: Long = Random.nextLong()

    // SharedPreferences holds its listeners weakly, so the source keeps this one.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> sequence.incrementAndGet() }

    // Guarded by this.
    private var listening = false

    /** The change sequence: the listener and the tab's own changes move it. */
    val currentSequence: Long get() = sequence.get()

    /** Whether the instance is an `EncryptedSharedPreferences`, which decrypts what it reads. */
    val decrypting: Boolean = prefs.javaClass.name == ENCRYPTED_PREFS_CLASS

    fun cursor(): String = CursorCodec.encode(currentSequence, epoch)

    /**
     * Records a change that the tab made. The listener also sees it, later when
     * the change was made off the main thread, but a poll right after the
     * change must already see a new cursor.
     */
    fun changed() {
        sequence.incrementAndGet()
    }

    @Synchronized
    fun listen() {
        if (listening) return
        prefs.registerOnSharedPreferenceChangeListener(listener)
        listening = true
    }

    @Synchronized
    fun unlisten() {
        if (!listening) return
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        listening = false
    }

    /**
     * Reads every entry of the file.
     *
     * @throws UnreadableFileException when the instance can't read the file,
     *   such as an `EncryptedSharedPreferences` whose key is gone.
     */
    fun read(): Contents {
        val entries =
            try {
                prefs.all
            } catch (e: RuntimeException) {
                throw UnreadableFileException("Can't read the file $name: $e", e)
            }
        return Contents(entries, rawEncrypted = KEYSET_KEYS.any { it in entries })
    }

    /**
     * Whether the tab may change the file: not when the app made it read-only,
     * and never when it is an encrypted file read without its keys, where a
     * plain value would break the file for the app.
     */
    fun writable(): Boolean =
        // A decrypting instance hides the keysets, and throws when it is asked for them.
        !readOnly && (decrypting || KEYSET_KEYS.none { prefs.contains(it) })

    /** What the tab knows of the file from one read of it. */
    inner class Contents(val entries: Map<String, *>, private val rawEncrypted: Boolean) {
        /**
         * Whether the file on disk is encrypted: the instance decrypts it, or it has
         * the keysets of `EncryptedSharedPreferences` and the instance reads it plain.
         */
        val encrypted: Boolean get() = decrypting || rawEncrypted

        /** The policy that applies: keys only, whatever the app chose, for an encrypted file read plain. */
        val policy: ValuePolicy get() = if (rawEncrypted) ValuePolicy.KEYS_ONLY else this@PrefsSource.policy

        val readOnly: Boolean get() = rawEncrypted || this@PrefsSource.readOnly

        fun masked(key: String): Boolean =
            when (policy) {
                ValuePolicy.VISIBLE -> false
                ValuePolicy.MASK_SENSITIVE -> SensitiveKeys.isSensitive(key)
                ValuePolicy.KEYS_ONLY -> true
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

/** The instance of a file throws when it reads it. */
internal class UnreadableFileException(message: String, cause: Throwable) : Exception(message, cause)
