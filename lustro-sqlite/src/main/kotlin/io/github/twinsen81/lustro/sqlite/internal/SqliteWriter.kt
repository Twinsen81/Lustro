package io.github.twinsen81.lustro.sqlite.internal

import android.os.CancellationSignal
import android.os.OperationCanceledException
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.sqlite.internal.SqliteReader.Companion.quote

/** The row that an edit or a delete names, by its rowid or by its primary key, as a `WHERE` clause. */
internal class RowTarget(val table: Table, val where: String, val args: Array<Any?>, val field: String)

/**
 * The writes of `POST exec`, `edit`, and `delete`, on the app's own handle of a
 * writable database. Each runs in a transaction of its own, which commits only
 * while the runtime still waits for the request: SQLite can't stop a write at
 * the timeout, so a statement that outlives it is rolled back when it ends. Only
 * a timeout during the commit itself leaves the outcome to the commit.
 */
internal class SqliteWriter(private val connection: LiveConnection, private val request: DebugRequest) {
    /**
     * Runs a client's statement, and returns how many rows it inserted, updated, or deleted,
     * without the rows of triggers and foreign key actions.
     */
    fun exec(sql: String): Long =
        write {
            val temp = tempSchema()
            val before = connection.long("SELECT total_changes()")
            connection.execute(sql)
            // changes() keeps the count of the connection's last INSERT, UPDATE, or DELETE, which can
            // be an earlier statement's: it is this one's only when this one changed a row.
            val changes = if (connection.long("SELECT total_changes()") != before) connection.long("SELECT changes()") else 0L
            // A TEMP table, view, or trigger belongs to the connection, which the app goes on using.
            if (tempSchema() != temp) {
                throw RouteException(
                    "exec does not create or drop TEMP tables, views, indexes, or triggers: they would stay on the app's connection",
                    code = "statement_refused",
                    field = "sql",
                )
            }
            changes
        }

    /** Sets [values] in the one row that [target] names. */
    fun edit(target: RowTarget, values: List<Pair<Column, Any?>>) =
        write {
            one(target)
            val set = values.joinToString(", ") { (column, _) -> "${quote(column.name)} = ?" }
            val args = values.map { it.second } + target.args
            connection.execute("UPDATE ${quote(target.table.name)} SET $set WHERE ${target.where}", args.toTypedArray())
        }

    /** Deletes the one row that [target] names. */
    fun delete(target: RowTarget) =
        write {
            one(target)
            connection.execute("DELETE FROM ${quote(target.table.name)} WHERE ${target.where}", target.args)
        }

    // The transaction holds the write lock, so the row that is counted is the row that changes.
    private fun one(target: RowTarget) {
        val count = connection.long("SELECT COUNT(*) FROM ${quote(target.table.name)} WHERE ${target.where}", target.args)
        if (count == 0L) throw RouteException("No row of ${target.table.name} has this ${target.field}", status = 404, field = target.field)
        if (count > 1) {
            throw RouteException("$count rows of ${target.table.name} have this ${target.field}: edit and delete change one row", field = target.field)
        }
    }

    private fun <T> write(block: () -> T): T =
        connection.transaction {
            // The transaction can start late, when the app holds the write lock.
            checkWaited()
            block().also { checkWaited() }
        }

    private fun checkWaited() {
        if (request.isCancelled) throw OperationCanceledException()
    }

    private fun tempSchema(): List<String> =
        connection.query("SELECT type, name, sql FROM sqlite_temp_master ORDER BY type, name", emptyArray(), CancellationSignal()).use { cursor ->
            buildList { while (cursor.moveToNext()) add("${cursor.getString(0)} ${cursor.getString(1)} ${cursor.getString(2)}") }
        }
}
