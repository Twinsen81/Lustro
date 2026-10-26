package io.github.twinsen81.lustro.prefs.internal

import io.github.twinsen81.lustro.CursorCodec
import io.github.twinsen81.lustro.SensitiveKeys
import io.github.twinsen81.lustro.prefs.ValuePolicy
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

private val SETTER_TYPES = listOf(PrefType.STRING, PrefType.INT, PrefType.LONG, PrefType.FLOAT, PrefType.BOOLEAN, PrefType.STRING_SET)

/**
 * The kinds of file that the Preferences tab shows, by their names on the wire,
 * with the types that `POST edit` writes to each.
 */
internal enum class PrefsStore(val wireName: String, val label: String, val writeTypes: List<PrefType>) {
    SHARED_PREFS("shared_prefs", "a SharedPreferences file", SETTER_TYPES),
    PREFERENCES_DATASTORE("preferences_datastore", "a Preferences DataStore", SETTER_TYPES + listOf(PrefType.DOUBLE, PrefType.BYTES)),
    ;

    companion object {
        fun forWireName(name: String): PrefsStore? = entries.firstOrNull { it.wireName == name }
    }
}

/**
 * One file of the Preferences tab: what the tab may show and do with it, and the
 * sequence that its cursor carries.
 */
internal abstract class PrefsSource(
    val name: String,
    val store: PrefsStore,
    /** The policy that the app chose. [Contents.policy] is the one that applies. */
    val policy: ValuePolicy,
    /** Whether the app made the file read-only. [writable] also checks the file. */
    val readOnly: Boolean,
    val registered: Boolean,
) {
    private val sequence = AtomicLong()

    /** A file has an epoch of its own, so that the cursor of one file is foreign to another. */
    val epoch: Long = Random.nextLong()

    /** The change sequence: the app's changes, as the source sees them, and the tab's own move it. */
    open val currentSequence: Long get() = sequence.get()

    /** Whether the instance decrypts what it reads. Known without a read, for a file that its instance can't read. */
    open val decrypting: Boolean get() = false

    fun cursor(): String = CursorCodec.encode(currentSequence, epoch)

    /**
     * Records a change. The tab calls it for its own changes, so that a poll
     * right after a change already sees a new cursor, also when the source
     * hears of the change later.
     */
    fun changed() {
        sequence.incrementAndGet()
    }

    /** Starts to follow the app's changes to the file. */
    abstract fun listen()

    abstract fun unlisten()

    /**
     * Reads every entry of the file.
     *
     * @throws UnreadableFileException when the file can't be read, such as an
     *   `EncryptedSharedPreferences` whose key is gone.
     */
    abstract fun read(): Contents

    /** Whether the tab may change the file. */
    abstract fun writable(): Boolean

    /**
     * Makes [change] to the file, and returns when the file on disk has it.
     *
     * @throws WriteFailedException when the write fails.
     */
    abstract fun write(change: PrefChange)
}

/** What the tab knows of a file from one read of it. */
internal class Contents(
    val entries: Map<String, *>,
    /** Whether the file on disk is encrypted. */
    val encrypted: Boolean,
    /** The policy that applies, which can be stricter than the one that the app chose. */
    val policy: ValuePolicy,
    val readOnly: Boolean,
) {
    fun masked(key: String): Boolean =
        when (policy) {
            ValuePolicy.VISIBLE -> false
            ValuePolicy.MASK_SENSITIVE -> SensitiveKeys.isSensitive(key)
            ValuePolicy.KEYS_ONLY -> true
        }
}

/** A change that the tab makes to a file. */
internal sealed class PrefChange {
    class Put(val key: String, val type: PrefType, val value: Any) : PrefChange()

    class Remove(val key: String) : PrefChange()

    object Clear : PrefChange()
}

/** The file can't be read. */
internal class UnreadableFileException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A write to a file failed. */
internal class WriteFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)
