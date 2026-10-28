package io.github.twinsen81.lustro.sqlite.internal

import android.database.sqlite.SQLiteConstraintException
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
            if (changedTemp(temp, tempSchema())) {
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
            val violations = deferredViolations()
            block().also {
                if (violations != null) checkDeferredKeys(violations)
                checkWaited()
            }
        }

    // A TEMP table, view, index, or trigger belongs to the connection, which the app goes on using:
    // the statement may neither add one nor drop one. SQLite drops a TEMP trigger with its table,
    // such as one that Room keeps on a table that it observes, and rewrites it when the table is renamed.
    private fun changedTemp(before: List<TempObject>, after: List<TempObject>): Boolean {
        val added = after.any { new -> before.none { it.type == new.type && it.name == new.name } }
        val removed = before.filter { old -> after.none { it.type == old.type && it.name == old.name } }
        return added || removed.any { it.type != "trigger" || exists(it.table) }
    }

    private fun exists(table: String): Boolean =
        connection.long("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = ? COLLATE NOCASE", arrayOf(table)) > 0 ||
            connection.long("SELECT count(*) FROM sqlite_temp_master WHERE type = 'table' AND name = ? COLLATE NOCASE", arrayOf(table)) > 0

    /**
     * The foreign key violations that the database has before the statement, when a deferred key
     * can fail the commit, or `null`. SQLite checks a deferred key at COMMIT, and a COMMIT that
     * fails keeps the transaction open; Android then gives the connection back to the pool with it,
     * and the app's later writes go into a transaction that never commits.
     */
    private fun deferredViolations(): Set<String>? {
        if (connection.long("PRAGMA foreign_keys") == 0L) return null
        val deferred =
            connection.long("PRAGMA defer_foreign_keys") != 0L ||
                connection.long("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND sql LIKE '%DEFERRED%'") > 0
        return if (deferred) violations() else null
    }

    // A violation that was there before the statement does not fail the commit, nor does one that it fixed.
    private fun checkDeferredKeys(before: Set<String>) {
        val added = violations() - before
        if (added.isNotEmpty()) {
            throw SQLiteConstraintException("FOREIGN KEY constraint failed: a deferred foreign key of ${added.first().substringBefore(' ')} has no parent row")
        }
    }

    private fun violations(): Set<String> =
        connection.query("PRAGMA foreign_key_check", emptyArray(), CancellationSignal()).use { cursor ->
            buildSet { while (cursor.moveToNext()) add((0 until cursor.columnCount).joinToString(" ") { cursor.getString(it).orEmpty() }) }
        }

    private fun checkWaited() {
        if (request.isCancelled) throw OperationCanceledException()
    }

    private class TempObject(val type: String, val name: String, val table: String)

    private fun tempSchema(): List<TempObject> =
        connection.query("SELECT type, name, tbl_name FROM sqlite_temp_master", emptyArray(), CancellationSignal()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(TempObject(cursor.getString(0), cursor.getString(1), cursor.getString(2))) }
        }
}
