package io.github.twinsen81.lustro.sqlite.internal

import android.database.AbstractWindowedCursor
import android.database.Cursor
import android.database.sqlite.SQLiteBlobTooBigException
import android.os.CancellationSignal

/** The result of a client's statement: its columns, in order, and a row of cells for each row. */
internal class QueryResult(val columns: List<String>, val rows: List<List<Cell>>, val truncated: Boolean)

/**
 * Runs a client's statement for `POST query`, on a connection that SQLite opened
 * read-only, so that SQLite refuses every write, whatever the statement is. The
 * connection is the tab's own, so out of WAL mode [limit] stops the statement.
 */
internal class SqliteQuery(private val limit: ReadLimit = ReadLimit()) {
    fun run(connection: DbConnection, sql: String, signal: CancellationSignal): QueryResult =
        if (limit.applies(connection, signal)) {
            limit.statement(signal, field = "sql") { statement -> connection.query(sql, emptyArray(), statement).use { read(it) } }
        } else {
            connection.query(sql, emptyArray(), signal).use { read(it) }
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
    }
}
