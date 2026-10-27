package io.github.twinsen81.lustro.sqlite.internal

import android.database.sqlite.SQLiteException
import android.os.CancellationSignal
import android.os.OperationCanceledException
import io.github.twinsen81.lustro.CursorCodec
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.escapeForJson
import io.github.twinsen81.lustro.sqlite.TableHint
import java.io.File

/** The read routes of the SQLite tab, under `/api/v1/sqlite/`. */
internal class SqliteRoutes(private val registry: SqliteRegistry) {
    fun handle(request: DebugRequest): DebugResponse? {
        val expected = METHODS[request.path] ?: return null
        if (request.method.uppercase() != expected) {
            return DebugResponse.error("${request.path} takes $expected", status = 405)
        }
        // A read stops when the runtime gives up on the request, at its timeout, so that a
        // long one does not keep a SQLite lock or a request slot.
        val signal = CancellationSignal()
        request.onCancel { signal.cancel() }
        return try {
            when (request.path) {
                "databases" -> databases(signal)
                "tables" -> tables(request, signal)
                "schema" -> schema(request, signal)
                "rows" -> rows(request, signal)
                else -> null
            }
        } catch (e: RouteException) {
            DebugResponse.error(e.message.orEmpty(), status = e.status, code = e.code, field = e.field)
        } catch (_: OperationCanceledException) {
            DebugResponse.error("The read was stopped: the request timed out or the server stopped", status = 504)
        } catch (e: SQLiteException) {
            DebugResponse.error(e.message ?: e.javaClass.simpleName, status = 500, code = "sqlite_error")
        }
    }

    private fun databases(signal: CancellationSignal): DebugResponse {
        val entries = registry.list()
        return DebugResponse.json {
            append("{\"items\":[")
            entries.forEachIndexed { index, entry ->
                if (index > 0) append(',')
                append("{\"name\":\"").append(entry.name.escapeForJson()).append('"')
                append(",\"registered\":").append(entry.registered)
                append(",\"source\":\"").append(entry.source.kind).append('"')
                append(",\"writable\":").append(entry.writable)
                var path = entry.source.file?.path
                var version: Int? = null
                var error: String? = null
                try {
                    open(entry).use { connection ->
                        path = connection.path
                        version = SqliteReader(connection, signal).userVersion()
                    }
                } catch (e: RouteException) {
                    error = e.message
                } catch (e: SQLiteException) {
                    error = e.message ?: e.javaClass.simpleName
                } catch (e: IllegalStateException) {
                    // A handle that the app has closed.
                    error = e.message ?: e.javaClass.simpleName
                }
                val size = path?.let { File(it) }?.takeIf { it.isFile }?.length()
                append(",\"path\":").appendString(path)
                append(",\"sizeBytes\":").append(size)
                append(",\"schemaVersion\":").append(version)
                if (error != null) append(",\"error\":").appendString(error)
                append('}')
            }
            append("]}")
        }
    }

    private fun tables(request: DebugRequest, signal: CancellationSignal): DebugResponse {
        val entry = entry(request)
        return read(entry, signal) { reader ->
            val tables = reader.tables()
            DebugResponse.json {
                append("{\"database\":\"").append(entry.name.escapeForJson()).append('"')
                append(",\"writable\":").append(entry.writable)
                append(",\"items\":[")
                tables.forEachIndexed { index, table ->
                    if (index > 0) append(',')
                    append("{\"name\":\"").append(table.name.escapeForJson()).append('"')
                    append(",\"type\":\"").append(table.type).append('"')
                    append(",\"internal\":").append(SqliteReader.isInternal(table, tables))
                    // Read before anything is written, so that a failed read leaves no field half written.
                    var hasRowId: Boolean? = null
                    var rowCount: Long? = null
                    var error: String? = null
                    try {
                        hasRowId = reader.rowid(table, reader.columns(table)) != null
                        // A view is not counted: that runs its query, which can be a slow one.
                        if (!table.isView) rowCount = reader.count(table)
                    } catch (e: SQLiteException) {
                        // Such as a view on a table that is gone, or a virtual table whose module this SQLite does not have.
                        error = e.message ?: e.javaClass.simpleName
                    }
                    if (hasRowId != null) append(",\"hasRowId\":").append(hasRowId)
                    if (rowCount != null) append(",\"rowCount\":").append(rowCount)
                    if (error != null) append(",\"error\":").appendString(error)
                    appendHint(entry.hints[table.name])
                    append('}')
                }
                append("]}")
            }
        }
    }

    private fun schema(request: DebugRequest, signal: CancellationSignal): DebugResponse {
        val entry = entry(request)
        val name = required(request, "table")
        return read(entry, signal) { reader ->
            val tables = reader.tables()
            val table = reader.table(name, tables) ?: throw noTable(entry, name)
            val columns = reader.columns(table)
            val rowid = reader.rowid(table, columns)
            val foreignKeys = reader.foreignKeys(table)
            val count = if (table.isView) null else reader.count(table)
            DebugResponse.json {
                append("{\"database\":\"").append(entry.name.escapeForJson()).append('"')
                append(",\"table\":\"").append(table.name.escapeForJson()).append('"')
                append(",\"type\":\"").append(table.type).append('"')
                append(",\"internal\":").append(SqliteReader.isInternal(table, tables))
                append(",\"hasRowId\":").append(rowid != null)
                append(",\"rowCount\":").append(count)
                append(",\"sql\":").appendString(table.sql)
                appendHint(entry.hints[table.name])
                append(",\"columns\":[")
                columns.forEachIndexed { index, column ->
                    if (index > 0) append(',')
                    append("{\"name\":\"").append(column.name.escapeForJson()).append('"')
                    append(",\"type\":\"").append(column.type.escapeForJson()).append('"')
                    append(",\"notNull\":").append(column.notNull)
                    append(",\"defaultValue\":").appendString(column.defaultValue)
                    append(",\"primaryKey\":").append(column.primaryKey)
                    append(",\"generated\":").append(column.generated)
                    append('}')
                }
                append("],\"foreignKeys\":[")
                foreignKeys.forEachIndexed { index, key ->
                    if (index > 0) append(',')
                    append("{\"table\":\"").append(key.table.escapeForJson()).append('"')
                    append(",\"from\":[").append(key.from.joinToString(",") { "\"${it.escapeForJson()}\"" }).append(']')
                    append(",\"to\":[").append(key.to.joinToString(",") { if (it == null) "null" else "\"${it.escapeForJson()}\"" }).append(']')
                    append(",\"onUpdate\":\"").append(key.onUpdate.escapeForJson()).append('"')
                    append(",\"onDelete\":\"").append(key.onDelete.escapeForJson()).append('"')
                    append('}')
                }
                append("]}")
            }
        }
    }

    private fun rows(request: DebugRequest, signal: CancellationSignal): DebugResponse {
        val entry = entry(request)
        val name = required(request, "table")
        val offset = offset(request.queryParam("cursor"))
        val limit = limit(request.queryParam("limit"))
        val descending = descending(request.queryParam("dir"))
        return read(entry, signal) { reader ->
            val table = reader.table(name) ?: throw noTable(entry, name)
            val columns = reader.columns(table)
            val rowid = reader.rowid(table, columns)
            val sort = request.queryParam("sort")?.takeIf { it.isNotEmpty() }?.let { sortColumn(it, columns, rowid) }
            val page = reader.rows(table, columns, rowid, RowsQuery(offset, limit, request.queryParam("filter"), sort, descending))
            DebugResponse.json {
                append("{\"items\":[")
                page.rows.forEachIndexed { index, row ->
                    if (index > 0) append(',')
                    append('{')
                    // A string, so that a JavaScript client keeps every digit of a 64-bit id.
                    if (row.rowid != null) append("\"rowid\":\"").append(row.rowid).append("\",")
                    append("\"cells\":{")
                    row.cells.forEachIndexed { cell, value ->
                        if (cell > 0) append(',')
                        append('"').append(columns[cell].name.escapeForJson()).append("\":")
                        Cells.append(this, value)
                    }
                    append("}}")
                }
                append("],\"nextCursor\":")
                appendString(page.nextOffset?.let { CursorCodec.encode(it) })
                append('}')
            }
        }
    }

    // The column that a client names, as the schema names it, or the rowid when no column has its name.
    private fun sortColumn(name: String, columns: List<Column>, rowid: String?): String =
        columns.firstOrNull { it.name == name }?.name
            ?: columns.firstOrNull { it.name.equals(name, ignoreCase = true) }?.name
            ?: rowid?.takeIf { name.equals("rowid", ignoreCase = true) || name.equals(it, ignoreCase = true) }
            ?: throw RouteException("sort must name a column of the table${if (rowid != null) ", or rowid" else ""}", field = "sort")

    private fun offset(cursor: String?): Long {
        if (cursor.isNullOrEmpty()) return 0L
        return CursorCodec.decode(cursor)?.takeIf { it >= 0 }
            ?: throw RouteException("cursor is not a cursor that GET rows returned", field = "cursor")
    }

    private fun limit(text: String?): Int {
        if (text.isNullOrEmpty()) return DEFAULT_LIMIT
        return text.toIntOrNull()?.takeIf { it in 1..MAX_LIMIT } ?: throw RouteException("limit must be a whole number from 1 to $MAX_LIMIT", field = "limit")
    }

    private fun descending(text: String?): Boolean =
        when {
            text.isNullOrEmpty() || text.equals("asc", ignoreCase = true) -> false
            text.equals("desc", ignoreCase = true) -> true
            else -> throw RouteException("dir must be asc or desc", field = "dir")
        }

    private fun entry(request: DebugRequest): DbEntry {
        val name = required(request, "db")
        return registry.find(name) ?: throw RouteException("No database named $name", status = 404, field = "db")
    }

    private fun required(request: DebugRequest, name: String): String =
        request.queryParam(name)?.takeIf { it.isNotEmpty() } ?: throw RouteException("$name is required", field = name)

    private fun noTable(entry: DbEntry, name: String): RouteException =
        RouteException("The database ${entry.name} has no table or view named $name", status = 404, field = "table")

    private fun <T> read(entry: DbEntry, signal: CancellationSignal, block: (SqliteReader) -> T): T = open(entry).use { block(SqliteReader(it, signal)) }

    // Anything that the app's supplier throws is the app's, so it is reported, not thrown to the runtime.
    @Suppress("TooGenericExceptionCaught")
    private fun open(entry: DbEntry): DbConnection =
        try {
            entry.source.open()
        } catch (e: Exception) {
            throw RouteException("The database ${entry.name} does not open: ${e.message ?: e.javaClass.name}", status = 500, code = "open_failed", field = "db")
        }

    private fun StringBuilder.appendHint(hint: TableHint?) {
        if (hint?.entity != null) append(",\"entity\":").appendString(hint.entity)
        if (hint?.purpose != null) append(",\"purpose\":").appendString(hint.purpose)
    }

    private fun StringBuilder.appendString(text: String?): StringBuilder =
        if (text == null) append("null") else append('"').append(text.escapeForJson()).append('"')

    private class RouteException(
        message: String,
        val status: Int = 400,
        val code: String? = null,
        val field: String? = null,
    ) : Exception(message)

    private companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 500

        val METHODS =
            mapOf(
                "databases" to "GET",
                "tables" to "GET",
                "schema" to "GET",
                "rows" to "GET",
            )
    }
}
