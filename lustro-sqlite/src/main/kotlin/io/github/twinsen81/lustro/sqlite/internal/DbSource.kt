package io.github.twinsen81.lustro.sqlite.internal

import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteProgram
import android.os.CancellationSignal
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.Closeable
import java.io.File

/** Where the tab gets a connection to one database: the app's open handle, or a file. */
internal abstract class DbSource {
    /** The source's name on the wire. */
    abstract val kind: String

    /** The file, when the source knows it without opening the database. */
    open val file: File? get() = null

    /**
     * A connection for one request. Close it when the request is done: that closes
     * a connection that the tab opened, and leaves the app's handle open.
     */
    abstract fun open(): DbConnection
}

/** One request's way to run the statements that the tab builds. */
internal interface DbConnection : Closeable {
    /** The database file, or `null` for a database in memory. */
    val path: String?

    /** Runs [sql] with [args] bound to its parameters, as text. [signal] stops the statement. */
    fun query(sql: String, args: Array<String>, signal: CancellationSignal): Cursor

    /**
     * Whether the connection runs on the platform's SQLite, with which the tab opens its own
     * connections. Two copies of SQLite in one process do not see each other's locks.
     */
    fun platformSqlite(): Boolean

    /**
     * Whether the tab opened the connection next to the app's own. A read on it takes a lock on
     * the file, which the app's writer waits for, outside the app's connection pool.
     */
    val separate: Boolean
}

/**
 * The app's own handle, through which the tab writes. Each method runs on the
 * connection that the calling thread holds in [transaction].
 */
internal interface LiveConnection : DbConnection {
    /**
     * Runs [block] in a transaction that takes the write lock (`BEGIN IMMEDIATE`), and
     * commits when it returns, or rolls back when it throws.
     */
    fun <T> transaction(block: () -> T): T

    /** Runs [sql], which returns no rows, with [args] bound to its parameters by their own types. */
    fun execute(sql: String, args: Array<Any?> = NO_ARGS)

    /** The first column of the first row of [sql], with [args] bound by their own types. */
    fun long(sql: String, args: Array<Any?> = NO_ARGS): Long

    companion object {
        val NO_ARGS: Array<Any?> = emptyArray()
    }
}

internal class FrameworkSource(private val database: () -> SQLiteDatabase) : DbSource() {
    override val kind: String = "framework"

    override fun open(): DbConnection = Live(database().also { check(it.isOpen) { CLOSED } })

    private class Live(private val db: SQLiteDatabase) : LiveConnection {
        override val path: String? get() = filePath(db.path)

        override fun query(sql: String, args: Array<String>, signal: CancellationSignal): Cursor = db.rawQuery(sql, args, signal)

        override fun platformSqlite(): Boolean = true

        override val separate: Boolean = false

        override fun <T> transaction(block: () -> T): T {
            db.beginTransactionNonExclusive()
            try {
                return block().also { db.setTransactionSuccessful() }
            } finally {
                db.endTransaction()
            }
        }

        override fun execute(sql: String, args: Array<Any?>) = db.execSQL(sql, args)

        override fun long(sql: String, args: Array<Any?>): Long =
            db.compileStatement(sql).use { statement ->
                bind(statement, args)
                statement.simpleQueryForLong()
            }

        // The app's handle: the app closes it.
        override fun close() = Unit
    }
}

internal class SupportSource(private val database: () -> SupportSQLiteDatabase) : DbSource() {
    override val kind: String = "support"

    override fun open(): DbConnection = Live(database().also { check(it.isOpen) { CLOSED } })

    private class Live(private val db: SupportSQLiteDatabase) : LiveConnection {
        override val path: String? get() = filePath(db.path)

        override fun query(sql: String, args: Array<String>, signal: CancellationSignal): Cursor =
            db.query(SimpleSQLiteQuery(sql, Array<Any?>(args.size) { args[it] }), signal)

        // A SupportSQLiteDatabase can be the platform's, through androidx or a wrapper of Room's, or
        // another copy of SQLite, such as SQLCipher's. The two answer these the same way only when
        // they are the same build.
        override fun platformSqlite(): Boolean = fingerprint { db.query(it) } == PLATFORM_FINGERPRINT

        override val separate: Boolean = false

        override fun <T> transaction(block: () -> T): T {
            db.beginTransactionNonExclusive()
            try {
                return block().also { db.setTransactionSuccessful() }
            } finally {
                db.endTransaction()
            }
        }

        override fun execute(sql: String, args: Array<Any?>) = db.execSQL(sql, args)

        override fun long(sql: String, args: Array<Any?>): Long =
            db.compileStatement(sql).use { statement ->
                SimpleSQLiteQuery.bind(statement, args)
                statement.simpleQueryForLong()
            }

        // The app's handle: the app closes it.
        override fun close() = Unit
    }

    private companion object {
        val PLATFORM_FINGERPRINT: String by lazy { SQLiteDatabase.create(null).use { db -> fingerprint { db.rawQuery(it, null) } } }

        // The SQLite build: its version and check-in, and the options that it was compiled with.
        fun fingerprint(query: (String) -> Cursor): String =
            buildString {
                query("SELECT sqlite_source_id()").use { if (it.moveToFirst()) append(it.getString(0)) }
                query("PRAGMA compile_options").use { while (it.moveToNext()) append('\n').append(it.getString(0)) }
            }
    }
}

/**
 * A file that the tab opens read-only for each request. The engine then refuses
 * every write, and in WAL mode the connection does not block the app's writer.
 * The connection is never kept: a long-lived second connection to a file that
 * the app writes can conflict with the app's own.
 */
internal class FileSource(override val file: File) : DbSource() {
    override val kind: String = "file"

    override fun open(): DbConnection = Opened(SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY, KEEP_FILES))

    private class Opened(private val db: SQLiteDatabase) : DbConnection {
        override val path: String? get() = filePath(db.path)

        override fun query(sql: String, args: Array<String>, signal: CancellationSignal): Cursor = db.rawQuery(sql, args, signal)

        override fun platformSqlite(): Boolean = true

        override val separate: Boolean = true

        override fun close() = db.close()
    }

    private companion object {
        // The default handler deletes a database file in which SQLite finds corruption, also
        // through a read-only connection. The tab reports the error and leaves the file to the app.
        val KEEP_FILES = DatabaseErrorHandler { }
    }
}

// A query on it would throw an IllegalStateException, which no route expects.
private const val CLOSED = "the app closed the handle that it returns"

// SQLiteDatabase names a database in memory ":memory:".
private fun filePath(path: String?): String? = path?.takeUnless { it.isEmpty() || it == ":memory:" }

private fun bind(program: SQLiteProgram, args: Array<Any?>) {
    args.forEachIndexed { index, value ->
        val position = index + 1
        when (value) {
            null -> program.bindNull(position)
            is Long -> program.bindLong(position, value)
            is Double -> program.bindDouble(position, value)
            is String -> program.bindString(position, value)
            is ByteArray -> program.bindBlob(position, value)
            else -> throw IllegalArgumentException("Can't bind a ${value.javaClass.name}")
        }
    }
}
