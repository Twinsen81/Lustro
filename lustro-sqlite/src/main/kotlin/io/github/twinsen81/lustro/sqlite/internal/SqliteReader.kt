package io.github.twinsen81.lustro.sqlite.internal

import android.database.Cursor
import android.os.CancellationSignal
import java.util.Locale

/** A table or a view, as `sqlite_master` lists it. */
internal class Table(val name: String, val type: String, val sql: String?) {
    val isView: Boolean get() = type == VIEW
    val isVirtual: Boolean get() = sql?.trimStart()?.startsWith("CREATE VIRTUAL TABLE", ignoreCase = true) == true

    companion object {
        const val VIEW = "view"
    }
}

/**
 * A column, as `PRAGMA table_xinfo` gives it. [primaryKey] is its place in the primary key, from 1,
 * or 0; [generated] is true for a generated column, which SQLite computes from the others.
 */
internal class Column(val name: String, val type: String, val notNull: Boolean, val defaultValue: String?, val primaryKey: Int, val generated: Boolean = false)

/** A foreign key, with its columns in order. A `null` in [to] stands for the primary key of [table]. */
internal class ForeignKey(val table: String, val from: List<String>, val to: List<String?>, val onUpdate: String, val onDelete: String)

/** One value of a row, by its storage class, read with no more than [SqliteReader.MAX_TEXT_CHARS] of a text. */
internal sealed class Cell {
    object Null : Cell()

    class Integer(val value: Long) : Cell()

    class Real(val value: Double) : Cell()

    /** A text; [length] is the length of the whole value, in characters, when [value] is cut. */
    class Text(val value: String, val length: Long?) : Cell()

    /** A blob: only its size, in bytes. */
    class Blob(val bytes: Long) : Cell()
}

/** A row; [rowid] is `null` for a table without one and for a view. */
internal class Row(val rowid: Long?, val cells: List<Cell>)

internal class RowsQuery(
    val offset: Long,
    val limit: Int,
    val filter: String?,
    /** A column of the table, or the rowid as [SqliteReader.rowid] names it. */
    val sort: String?,
    val descending: Boolean,
)

/** A page of rows, and where the next one starts, or `null` when this is the last. */
internal class Page(val rows: List<Row>, val nextOffset: Long?)

/**
 * The statements that the routes run. Every identifier in them comes from the
 * database's own schema, quoted, and every value from a client is a bound
 * parameter, so a request can't change what a statement does.
 */
internal class SqliteReader(private val connection: DbConnection, private val signal: CancellationSignal) {
    fun userVersion(): Int = query("PRAGMA user_version") { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** The tables and views, by name, as SQLite sorts names without case. */
    fun tables(): List<Table> =
        query("SELECT name, type, sql FROM sqlite_master WHERE type IN ('table', 'view') ORDER BY name COLLATE NOCASE") { cursor ->
            buildList {
                while (cursor.moveToNext()) add(Table(cursor.getString(0), cursor.getString(1), cursor.getStringOrNull(2)))
            }
        }

    /** The table or view named [name]; SQLite names do not depend on case, but one with the same case comes first. */
    fun table(name: String, tables: List<Table> = tables()): Table? =
        tables.firstOrNull { it.name == name } ?: tables.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /**
     * The columns, in their order in the table. `table_info` leaves out a generated column, so
     * `table_xinfo` (SQLite 3.26) comes first; an older SQLite ignores a pragma that it does not
     * know and returns a result without columns. A virtual table's hidden columns stay out, as
     * `table_info` has them.
     */
    fun columns(table: Table): List<Column> =
        columns("table_xinfo", table).filter { it.hidden != HIDDEN }.map { it.column }.ifEmpty { columns("table_info", table).map { it.column } }

    private class Info(val column: Column, val hidden: Int)

    private fun columns(pragma: String, table: Table): List<Info> =
        query("PRAGMA $pragma(${quote(table.name)})") { cursor ->
            if (cursor.getColumnIndex("name") < 0) return@query emptyList()
            val name = cursor.getColumnIndexOrThrow("name")
            val type = cursor.getColumnIndexOrThrow("type")
            val notNull = cursor.getColumnIndexOrThrow("notnull")
            val default = cursor.getColumnIndexOrThrow("dflt_value")
            val pk = cursor.getColumnIndexOrThrow("pk")
            val hidden = cursor.getColumnIndex("hidden")
            buildList {
                while (cursor.moveToNext()) {
                    val kind = if (hidden < 0) 0 else cursor.getInt(hidden)
                    val column =
                        Column(
                            cursor.getString(name),
                            cursor.getStringOrNull(type).orEmpty(),
                            cursor.getInt(notNull) != 0,
                            cursor.getStringOrNull(default),
                            cursor.getInt(pk),
                            generated = kind == GENERATED_VIRTUAL || kind == GENERATED_STORED,
                        )
                    add(Info(column, kind))
                }
            }
        }

    fun foreignKeys(table: Table): List<ForeignKey> =
        query("PRAGMA foreign_key_list(${quote(table.name)})") { cursor ->
            // Before SQLite 3.16 (API 24 to 25), the result of a table without foreign keys has no columns.
            if (cursor.getColumnIndex("id") < 0) return@query emptyList()
            val id = cursor.getColumnIndexOrThrow("id")
            val seq = cursor.getColumnIndexOrThrow("seq")
            val parent = cursor.getColumnIndexOrThrow("table")
            val from = cursor.getColumnIndexOrThrow("from")
            val to = cursor.getColumnIndexOrThrow("to")
            val onUpdate = cursor.getColumnIndexOrThrow("on_update")
            val onDelete = cursor.getColumnIndexOrThrow("on_delete")
            class Part(val id: Int, val seq: Int, val parent: String, val from: String, val to: String?, val onUpdate: String, val onDelete: String)
            val parts =
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            Part(
                                cursor.getInt(id), cursor.getInt(seq), cursor.getString(parent), cursor.getString(from), cursor.getStringOrNull(to),
                                cursor.getString(onUpdate), cursor.getString(onDelete),
                            ),
                        )
                    }
                }
            // A key over several columns has a row for each, with the same id.
            parts.groupBy { it.id }.toSortedMap().values.map { key ->
                val ordered = key.sortedBy { it.seq }
                ForeignKey(ordered[0].parent, ordered.map { it.from }, ordered.map { it.to }, ordered[0].onUpdate, ordered[0].onDelete)
            }
        }

    /**
     * How a statement names the table's rowid: `rowid`, or `_rowid_` or `oid`
     * when a column has that name. `null` for a view, for a table `WITHOUT
     * ROWID`, and for a table whose columns take all three names.
     */
    fun rowid(table: Table, columns: List<Column>): String? {
        if (table.isView) return null
        val alias = ROWID_ALIASES.firstOrNull { alias -> columns.none { it.name.equals(alias, ignoreCase = true) } } ?: return null
        // Unquoted: a quoted name that is not a column would be read as a string.
        return try {
            query("SELECT $alias FROM ${quote(table.name)} LIMIT 0") { alias }
        } catch (_: android.database.sqlite.SQLiteException) {
            // A table WITHOUT ROWID has no such column.
            null
        }
    }

    fun count(table: Table): Long = query("SELECT COUNT(*) FROM ${quote(table.name)}") { if (it.moveToFirst()) it.getLong(0) else 0L }

    /** A page of rows of [table], whose columns are [columns] and whose rowid [rowid] names, for [request]. */
    fun rows(table: Table, columns: List<Column>, rowid: String?, request: RowsQuery): Page {
        val select =
            buildList {
                if (rowid != null) add(rowid)
                for (column in columns) {
                    val name = quote(column.name)
                    // The storage class, per cell: any column can hold any type, a blob in a TEXT column too.
                    add("typeof($name)")
                    // A blob is never loaded, and a text is cut. A number comes as it is.
                    add("CASE typeof($name) WHEN 'text' THEN substr($name, 1, $MAX_TEXT_CHARS) WHEN 'blob' THEN NULL ELSE $name END")
                    // length() of a text counts characters, of a blob bytes, and reads only the size of a blob.
                    add("CASE WHEN typeof($name) IN ('text', 'blob') THEN length($name) END")
                }
            }.joinToString(", ")
        val args = mutableListOf<String>()
        val where =
            request.filter?.takeIf { it.isNotEmpty() }?.let { filter ->
                args += "%" + escapeLike(filter) + "%"
                // One parameter, ?1, for every column; a blob is left out, as its bytes are not text.
                columns.joinToString(" OR ", prefix = " WHERE (", postfix = ")") {
                    val name = quote(it.name)
                    "(typeof($name) != 'blob' AND CAST($name AS TEXT) LIKE ?1 ESCAPE '\\')"
                }
            }.orEmpty()
        val sql = "SELECT $select FROM ${quote(table.name)}$where${orderBy(columns, rowid, request)} LIMIT ${request.limit + 1} OFFSET ${request.offset}"
        val rows =
            query(sql, args.toTypedArray()) { cursor ->
                buildList {
                    while (size <= request.limit && cursor.moveToNext()) add(readRow(cursor, columns.size, rowid != null))
                }
            }
        return if (rows.size > request.limit) Page(rows.subList(0, request.limit), request.offset + request.limit) else Page(rows, null)
    }

    // A sort on a column also sorts by the row's key, so that the rows with the same value keep
    // their order from page to page. Without a sort, the rows come in the order of the key.
    private fun orderBy(columns: List<Column>, rowid: String?, request: RowsQuery): String {
        val direction = if (request.descending) " DESC" else " ASC"
        val key = if (rowid != null) listOf(rowid) else columns.filter { it.primaryKey > 0 }.sortedBy { it.primaryKey }.map { quote(it.name) }
        val sort = request.sort?.let { if (it == rowid) it else quote(it) }
        val terms = listOfNotNull(sort) + key.filter { it != sort }
        return if (terms.isEmpty()) "" else terms.joinToString(", ", prefix = " ORDER BY ") { it + direction }
    }

    private fun readRow(cursor: Cursor, columnCount: Int, hasRowid: Boolean): Row {
        var index = 0
        val rowid = if (hasRowid) cursor.getLong(index++) else null
        val cells = ArrayList<Cell>(columnCount)
        repeat(columnCount) {
            val type = cursor.getString(index)
            val value = index + 1
            val length = index + 2
            index += CELL_COLUMNS
            cells +=
                when (type) {
                    "integer" -> Cell.Integer(cursor.getLong(value))
                    "real" -> Cell.Real(cursor.getDouble(value))
                    "text" -> {
                        val whole = cursor.getLong(length)
                        Cell.Text(cursor.getString(value), whole.takeIf { it > MAX_TEXT_CHARS })
                    }
                    "blob" -> Cell.Blob(cursor.getLong(length))
                    else -> Cell.Null
                }
        }
        return Row(rowid, cells)
    }

    private fun <T> query(sql: String, args: Array<String> = emptyArray(), read: (Cursor) -> T): T = connection.query(sql, args, signal).use(read)

    companion object {
        /** The most characters of a text value that a page of rows holds. */
        const val MAX_TEXT_CHARS: Int = 4096

        // Each column of a row is read as its type, its value, and its length.
        private const val CELL_COLUMNS = 3

        private val ROWID_ALIASES = listOf("rowid", "_rowid_", "oid")

        // The values of table_xinfo's hidden column: a virtual table's hidden column, and the two kinds of generated column.
        private const val HIDDEN = 1
        private const val GENERATED_VIRTUAL = 2
        private const val GENERATED_STORED = 3

        // The tables that SQLite's own virtual table modules (FTS3, FTS4, FTS5, R*Tree) keep for one of theirs.
        private val SHADOW_SUFFIXES =
            listOf("_content", "_segments", "_segdir", "_docsize", "_stat", "_data", "_idx", "_config", "_node", "_parent", "_rowid")

        /**
         * Whether [table] is one that SQLite, Android, or Room keeps for itself, or a
         * shadow table of one of [tables] that is a virtual table: a view can hide them.
         */
        fun isInternal(table: Table, tables: List<Table>): Boolean {
            val name = table.name.lowercase(Locale.ROOT)
            if (name.startsWith("sqlite_") || name == "android_metadata" || name == "room_master_table") return true
            return tables.any { virtual ->
                virtual.isVirtual && SHADOW_SUFFIXES.any { name == virtual.name.lowercase(Locale.ROOT) + it }
            }
        }

        /** [identifier] as a quoted SQL identifier: a `"` in it is doubled. */
        fun quote(identifier: String): String = "\"" + identifier.replace("\"", "\"\"") + "\""

        /** [text] with the `LIKE` wildcards escaped, for `ESCAPE '\'`. */
        fun escapeLike(text: String): String = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
    }
}
