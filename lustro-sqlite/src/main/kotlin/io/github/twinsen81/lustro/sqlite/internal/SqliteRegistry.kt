package io.github.twinsen81.lustro.sqlite.internal

import io.github.twinsen81.lustro.sqlite.SqliteDb
import io.github.twinsen81.lustro.sqlite.TableHint
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

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
 */
internal class SqliteRegistry(databases: List<SqliteDb>, private val directory: File?) {
    private val registered = databases.map { DbEntry(it.name, registered = true, it.writable, it.tableHints, it.source) }

    fun list(): List<DbEntry> = registered + discovered()

    fun find(name: String): DbEntry? = registered.firstOrNull { it.name == name } ?: discovered().firstOrNull { it.name == name }

    private fun discovered(): List<DbEntry> {
        val files =
            directory?.listFiles().orEmpty()
                .filter { file -> file.isFile && SIDECARS.none { file.name.endsWith(it) } && isSqlite(file) }
                .sortedBy { it.name }
        if (files.isEmpty()) return emptyList()
        val names = registered.mapTo(HashSet()) { it.name }
        val covered = registeredFiles()
        return files
            .filter { it.name !in names && it.absoluteFile.normalize() !in covered }
            .map { DbEntry(it.name, registered = false, writable = false, emptyMap(), FileSource(it.absoluteFile.normalize())) }
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
                    // A database that does not open is not listed twice: the registration shows its error.
                    null
                }
        }

    private fun isSqlite(file: File): Boolean =
        try {
            RandomAccessFile(file, "r").use { input ->
                val header = ByteArray(HEADER.size)
                input.length() >= HEADER.size && header.also { input.readFully(it) }.contentEquals(HEADER)
            }
        } catch (_: IOException) {
            false
        }

    private companion object {
        val SIDECARS = listOf("-wal", "-shm", "-journal")

        // The first 16 bytes of every SQLite database file.
        val HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    }
}
