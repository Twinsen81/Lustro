package io.github.twinsen81.lustro.sqlite.internal

import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteException
import android.os.CancellationSignal
import io.github.twinsen81.lustro.SensitiveKeys
import io.github.twinsen81.lustro.sqlite.SqliteDb
import io.github.twinsen81.lustro.sqlite.SqliteValuePolicy
import io.github.twinsen81.lustro.sqlite.TableHint
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale

/** A database that the routes can read: a registered one, or a file found on disk. */
internal class DbEntry(
    val name: String,
    val registered: Boolean,
    val writable: Boolean,
    val hints: Map<String, TableHint>,
    val policy: SqliteValuePolicy,
    val source: DbSource,
    val onWrite: Runnable? = null,
    unmasked: Set<String> = emptySet(),
) {
    private val unmasked: Set<String> = unmasked.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }

    /**
     * Whether the policy leaves out the values of a column, or of a result column, of this name:
     * a sensitive name that the app did not list in its exceptions, in any case.
     */
    fun masks(column: String): Boolean {
        if (policy != SqliteValuePolicy.MASK_SENSITIVE) return false
        val name = column.lowercase(Locale.ROOT)
        return name != KEY_COLUMN && name !in unmasked && SensitiveKeys.isSensitive(column)
    }

    private companion object {
        // In a table, a column named only `key` is the name column of a key-value table, such as
        // WorkManager's `Preference`: masked, the rows would show values without their names, and
        // a discovered file has no exceptions to fix that. `SensitiveKeys` keeps it, because a
        // JSON field `key` in a request can hold an API key.
        const val KEY_COLUMN = "key"
    }
}

/**
 * The registered databases, and, with a [directory], the other database files in
 * it and in [noBackupDirectory]. The directories are listed on each call, so a file
 * that the app creates later shows too. A discovered file is read-only, masks
 * sensitive columns with no exceptions, and has no hints. A file in
 * [noBackupDirectory] is named by its file name too, unless a registration or a
 * file in [directory] has that name: then it is `no_backup/<file name>`.
 *
 * Discovery decides whether a file is a database only for the files that no registration
 * stands for, so the tab opens the app's own databases only through the app's handles. A
 * file that [openFiles] says the process holds open goes to [probe], which asks SQLite. The
 * tab reads the header of any other file itself, and never gives SQLite a file that is not
 * a database: some builds of SQLite write the first page of such a file to logcat.
 */
internal class SqliteRegistry(
    databases: List<SqliteDb>,
    private val directory: File?,
    private val noBackupDirectory: File? = null,
    private val openFiles: () -> OpenFiles = OpenFiles::ofProcess,
    private val probe: (File) -> Boolean = ::openable,
) {
    private val registered =
        databases.map { DbEntry(it.name, registered = true, it.writable, it.tableHints, it.valuePolicy, it.source, it.onWrite, it.unmasked) }

    fun list(): List<DbEntry> = registered + discovered()

    fun find(name: String): DbEntry? = registered.firstOrNull { it.name == name } ?: discovered(name).firstOrNull { it.name == name }

    // With a name, only the files of that file name are asked about.
    private fun discovered(name: String? = null): List<DbEntry> {
        val fileName = name?.removePrefix(NO_BACKUP_PREFIX)
        val primary = candidates(directory, fileName)
        val secondary = candidates(noBackupDirectory, fileName)
        if (primary.isEmpty() && secondary.isEmpty()) return emptyList()
        val names = registered.mapTo(HashSet()) { it.name }
        val covered = registeredFiles()
        // Once per listing, before the first header read: a read's close() drops the locks only
        // of a file that the process opened in the meantime.
        val open by lazy(LazyThreadSafetyMode.NONE) { openFiles() }
        val isDatabase = { file: File -> file.length() >= HEADER_BYTES && if (open.holds(file)) probe(file) else hasHeader(file) }
        val inDirectory = primary.filter { it.name !in names && it !in covered && isDatabase(it) }.sortedBy { it.name }
        val taken = names + inDirectory.map { it.name }
        val inNoBackup = secondary.filter { it !in covered && isDatabase(it) }.sortedBy { it.name }
        return inDirectory.map { discoveredEntry(it.name, it) } +
            inNoBackup.map { discoveredEntry(if (it.name in taken) NO_BACKUP_PREFIX + it.name else it.name, it) }
    }

    private fun candidates(directory: File?, fileName: String?): List<File> =
        directory?.listFiles().orEmpty()
            .filter { file -> file.isFile && (fileName == null || file.name == fileName) && !NOT_DATABASES.containsMatchIn(file.name) }
            .map { it.absoluteFile.normalize() }

    private fun discoveredEntry(name: String, file: File): DbEntry =
        DbEntry(name, registered = false, writable = false, emptyMap(), SqliteValuePolicy.MASK_SENSITIVE, FileSource(file))

    // The files that the registrations stand for. A live handle names its file only once
    // it is open, so this asks the app for it, as a read of that database would.
    @Suppress("TooGenericExceptionCaught")
    private fun registeredFiles(): Set<File> =
        registered.mapNotNullTo(HashSet()) { entry ->
            entry.source.file
                ?: try {
                    entry.source.open().use { it.path }?.let { File(it).absoluteFile.normalize() }
                } catch (_: Exception) {
                    // A handle that does not open names no file, so discovery can list its file too,
                    // next to the registration, which shows the error.
                    null
                }
        }

    internal companion object {
        // The name of a file in noBackupFilesDir whose file name is taken starts with it.
        private const val NO_BACKUP_PREFIX = "no_backup/"

        // SQLite's own files next to a database (rollback journal, WAL, shared memory, super-journal),
        // and lock files, which a process can hold a lock on.
        private val NOT_DATABASES = Regex("""(-journal|-wal|-shm|-mj[0-9A-Fa-f]+|\.lck|\.lock)$""")

        // The size of a database file's header: a file of a database has at least that much.
        private const val HEADER_BYTES = 100L

        // The start of every database file: "SQLite format 3" and a zero byte.
        private val MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

        /**
         * Whether [file] starts as a database file does. Only for a file that the process does
         * not hold open: on Android, a close() of any descriptor of a file drops every POSIX
         * lock that the process holds on it, also those of the app's SQLite connections.
         */
        private fun hasHeader(file: File): Boolean =
            try {
                DataInputStream(FileInputStream(file)).use { input -> ByteArray(MAGIC.size).also(input::readFully).contentEquals(MAGIC) }
            } catch (_: IOException) {
                // Shorter than the header, or gone.
                false
            }

        /**
         * Whether SQLite can read [file], for a file that the process holds open. SQLite opens
         * it, never a stream of the tab's own, because SQLite keeps the locks of its other
         * connections to the file when it closes its own descriptor.
         */
        fun openable(file: File): Boolean {
            return try {
                FileSource(file).open().use { connection ->
                    connection.query("PRAGMA schema_version", emptyArray(), CancellationSignal()).use { it.moveToFirst() }
                }
                true
            } catch (_: SQLiteDatabaseLockedException) {
                // A database, which a writer of the app holds now: GET databases says so.
                true
            } catch (_: SQLiteException) {
                // Not a database: SQLite reports it as SQLITE_NOTADB.
                false
            }
        }
    }
}
