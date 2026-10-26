package io.github.twinsen81.lustro.prefs.internal

import io.github.twinsen81.lustro.prefs.ValuePolicy
import java.io.File
import java.io.IOException
import java.util.zip.CRC32

/**
 * The file of a Preferences DataStore that the app does not register, which the
 * tab reads itself with [PreferencesFileReader]. It is always read-only: a
 * DataStore reads its file once and keeps the data in memory, so the app would
 * not see an edit, and its next write would undo it. Opening a DataStore of
 * its own is no option either: a second instance of a file makes the app's
 * DataStore throw.
 *
 * Nothing tells the tab of a change, so the cursor moves when the file's bytes
 * differ from the last time it looked. DataStore writes a new file and renames
 * it over the old one, so a read sees one whole version of it.
 */
internal class DataStoreFileSource(
    name: String,
    private val file: File,
) : PrefsSource(name, PrefsStore.PREFERENCES_DATASTORE, ValuePolicy.MASK_SENSITIVE, readOnly = true, registered = false) {
    // Guarded by this. The checksum of the bytes when the cursor was last read.
    private var checksum: Long? = null

    override val currentSequence: Long
        get() {
            look()
            return super.currentSequence
        }

    @Synchronized
    private fun look() {
        val now =
            try {
                CRC32().apply { update(file.readBytes()) }.value
            } catch (_: IOException) {
                // Gone or unreadable: read() says so, and the file shows again when it reads.
                -1L
            }
        if (checksum != null && checksum != now) changed()
        checksum = now
    }

    override fun listen() = Unit

    override fun unlisten() = Unit

    override fun read(): Contents {
        val entries =
            try {
                PreferencesFileReader.read(file.readBytes())
            } catch (e: IOException) {
                throw UnreadableFileException("Can't read the file ${file.name}: $e", e)
            } catch (e: MalformedFileException) {
                throw UnreadableFileException("The file ${file.name} is not a Preferences DataStore file, or it is encrypted: ${e.message}", e)
            }
        return Contents(entries, encrypted = false, policy = policy, readOnly = true)
    }

    override fun writable(): Boolean = false

    override fun write(change: PrefChange) {
        throw WriteFailedException("The tab can't write the file of a DataStore that the app does not register")
    }
}
