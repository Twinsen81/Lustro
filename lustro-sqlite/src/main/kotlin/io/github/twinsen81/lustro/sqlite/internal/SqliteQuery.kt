package io.github.twinsen81.lustro.sqlite.internal

import android.database.AbstractWindowedCursor
import android.database.Cursor
import android.database.sqlite.SQLiteBlobTooBigException
import android.os.CancellationSignal
import android.os.OperationCanceledException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The result of a client's statement: its columns, in order, and a row of cells for each row. */
internal class QueryResult(val columns: List<String>, val rows: List<List<Cell>>, val truncated: Boolean)

/**
 * Runs a client's statement for `POST query`, on a connection that SQLite opened
 * read-only, so that SQLite refuses every write, whatever the statement is.
 *
 * Out of WAL mode, a reader holds a SHARED lock for as long as its statement
 * runs, and the app's writer waits for it. Android's SQLite gives up on a lock
 * after 2.5 s, and the app's write then fails with `SQLiteDatabaseLockedException`,
 * so a statement there stops after [rollbackLimitMs], before that.
 */
internal class SqliteQuery(private val rollbackLimitMs: Long = ROLLBACK_LIMIT_MS) {
    fun run(connection: DbConnection, sql: String, signal: CancellationSignal): QueryResult {
        val wal = connection.query("PRAGMA journal_mode", emptyArray(), signal).use { it.moveToFirst() && it.getString(0).equals("wal", ignoreCase = true) }
        val limited = AtomicBoolean()
        val timer =
            if (wal) {
                null
            } else {
                TIMER.schedule({
                    limited.set(true)
                    signal.cancel()
                }, rollbackLimitMs, TimeUnit.MILLISECONDS)
            }
        try {
            return connection.query(sql, emptyArray(), signal).use { read(it) }
        } catch (e: OperationCanceledException) {
            if (!limited.get()) throw e
            throw RouteException(
                "The statement was stopped after $rollbackLimitMs ms: the database is not in WAL mode, so a longer read " +
                    "would block the app's writes until they fail. Add a LIMIT or a WHERE that an index serves, or page with GET rows",
                status = 504,
                code = "read_limit",
                field = "sql",
            )
        } finally {
            timer?.cancel(false)
        }
    }

    // Android's cursor steps through the whole result once, to count it; only the first rows are kept.
    private fun read(cursor: Cursor): QueryResult {
        val columns = cursor.columnNames.toList()
        val rows = ArrayList<List<Cell>>()
        var chars = 0L
        var truncated = false
        while (cursor.moveToNext()) {
            if (rows.size == MAX_ROWS || chars >= MAX_CHARS) {
                truncated = true
                break
            }
            checkInWindow(cursor)
            val row = List(columns.size) { cell(cursor, it) }
            chars += row.sumOf { (it as? Cell.Text)?.value?.length?.toLong() ?: 0L }
            rows += row
        }
        return QueryResult(columns, rows, truncated)
    }

    // From API 28, a row that Android's cursor window can't hold throws. Before, the window leaves
    // the row out, and the cursor reads each of its cells as NULL.
    private fun checkInWindow(cursor: Cursor) {
        val window = (cursor as? AbstractWindowedCursor)?.window ?: return
        val position = cursor.position
        if (position < window.startPosition || position >= window.startPosition + window.numRows) {
            throw SQLiteBlobTooBigException("Row too big to fit into CursorWindow: row $position")
        }
    }

    // A text comes whole: a page of rows cuts it, and a query is how a client reads all of it.
    private fun cell(cursor: Cursor, index: Int): Cell =
        when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_INTEGER -> Cell.Integer(cursor.getLong(index))
            Cursor.FIELD_TYPE_FLOAT -> Cell.Real(cursor.getDouble(index))
            Cursor.FIELD_TYPE_STRING -> Cell.Text(cursor.getString(index), length = null)
            Cursor.FIELD_TYPE_BLOB -> Cell.Blob(cursor.getBlob(index).size.toLong())
            else -> Cell.Null
        }

    companion object {
        /** The most rows in a result. */
        const val MAX_ROWS: Int = 1000

        /** The most characters of text in a result, over all of its cells, before the next row. */
        const val MAX_CHARS: Long = 4L * 1024 * 1024

        /** How long a statement runs on a database that is not in WAL mode. */
        const val ROLLBACK_LIMIT_MS: Long = 2_000

        private val TIMER =
            ScheduledThreadPoolExecutor(1) { task -> Thread(task, "lustro-sqlite-limit").apply { isDaemon = true } }.apply {
                setKeepAliveTime(1, TimeUnit.SECONDS)
                allowCoreThreadTimeOut(true)
                removeOnCancelPolicy = true
            }
    }
}
