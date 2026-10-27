package io.github.twinsen81.lustro.sqlite.internal

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
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
}

internal class FrameworkSource(private val database: () -> SQLiteDatabase) : DbSource() {
    override val kind: String = "framework"

    override fun open(): DbConnection = Live(database())

    private class Live(private val db: SQLiteDatabase) : DbConnection {
        override val path: String? get() = filePath(db.path)

        override fun query(sql: String, args: Array<String>, signal: CancellationSignal): Cursor = db.rawQuery(sql, args, signal)

        // The app's handle: the app closes it.
        override fun close() = Unit
    }
}

internal class SupportSource(private val database: () -> SupportSQLiteDatabase) : DbSource() {
    override val kind: String = "support"

    override fun open(): DbConnection = Live(database())

    private class Live(private val db: SupportSQLiteDatabase) : DbConnection {
        override val path: String? get() = filePath(db.path)

        override fun query(sql: String, args: Array<String>, signal: CancellationSignal): Cursor =
            db.query(SimpleSQLiteQuery(sql, Array<Any?>(args.size) { args[it] }), signal)

        // The app's handle: the app closes it.
        override fun close() = Unit
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

    override fun open(): DbConnection = Opened(SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY))

    private class Opened(private val db: SQLiteDatabase) : DbConnection {
        override val path: String? get() = filePath(db.path)

        override fun query(sql: String, args: Array<String>, signal: CancellationSignal): Cursor = db.rawQuery(sql, args, signal)

        override fun close() = db.close()
    }
}

// SQLiteDatabase names a database in memory ":memory:".
private fun filePath(path: String?): String? = path?.takeUnless { it.isEmpty() || it == ":memory:" }
