package io.github.twinsen81.lustro.sqlite.internal

import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteException
import android.os.CancellationSignal
import io.github.twinsen81.lustro.sqlite.SqliteDb
import io.github.twinsen81.lustro.sqlite.TableHint
import java.io.File

/** A database that the routes can read: a registered one, or a file found on disk. */
internal class DbEntry(
    val name: String,
    val registered: Boolean,
    val writable: Boolean,
    val hints: Map<String, TableHint>,
    val source: DbSource,
)

/**
 * The registered databases, and, with a [directory], the other database files in
 * it. The directory is listed on each call, so a file that the app creates later
 * shows too. A discovered file is read-only and has no hints.
 *
 * [isDatabase] decides whether a file is a database. It runs only on the files that no
 * registration stands for, so the tab opens the app's own databases only through the
 * app's handles.
 */
internal class SqliteRegistry(
    databases: List<SqliteDb>,
    private val directory: File?,
    private val isDatabase: (File) -> Boolean = ::openable,
) {
    private val registered = databases.map { DbEntry(it.name, registered = true, it.writable, it.tableHints, it.source) }

    fun list(): List<DbEntry> = registered + discovered()

    fun find(name: String): DbEntry? = registered.firstOrNull { it.name == name } ?: discovered(name).firstOrNull()

    // With a name, only the file of that name is asked about.
    private fun discovered(name: String? = null): List<DbEntry> {
        val candidates =
            directory?.listFiles().orEmpty()
                .filter { file -> file.isFile && (name == null || file.name == name) && !NOT_DATABASES.containsMatchIn(file.name) }
                .map { it.absoluteFile.normalize() }
        if (candidates.isEmpty()) return emptyList()
        val names = registered.mapTo(HashSet()) { it.name }
        val covered = registeredFiles()
        return candidates
            .filter { it.name !in names && it !in covered && isDatabase(it) }
            .sortedBy { it.name }
            .map { DbEntry(it.name, registered = false, writable = false, emptyMap(), FileSource(it)) }
    }

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

    private companion object {
        // SQLite's own files next to a database (rollback journal, WAL, shared memory, super-journal),
        // and lock files, which a process can hold a lock on.
        val NOT_DATABASES = Regex("""(-journal|-wal|-shm|-mj[0-9A-Fa-f]+|\.lck|\.lock)$""")

        // The size of a database file's header: a file of a database has at least that much.
        const val HEADER_BYTES = 100L

        /**
         * Whether [file] is a SQLite database. SQLite reads it, never a stream of the tab's
         * own: on Android, a close() of any descriptor of a file drops every POSIX lock that
         * the process holds on it, also those of the app's SQLite connections, and SQLite
         * works around that only for the descriptors that it opens itself.
         */
        fun openable(file: File): Boolean {
            if (file.length() < HEADER_BYTES) return false
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
