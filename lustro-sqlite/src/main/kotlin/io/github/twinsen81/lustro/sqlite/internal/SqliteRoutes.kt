package io.github.twinsen81.lustro.sqlite.internal

import android.database.sqlite.SQLiteBlobTooBigException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteOutOfMemoryException
import android.database.sqlite.SQLiteReadOnlyDatabaseException
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.util.Log
import io.github.twinsen81.lustro.CursorCodec
import io.github.twinsen81.lustro.DebugBodyParams
import io.github.twinsen81.lustro.DebugCall
import io.github.twinsen81.lustro.DebugParam
import io.github.twinsen81.lustro.DebugParams
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugRoutes
import io.github.twinsen81.lustro.DebugRoutes.Companion.ref
import io.github.twinsen81.lustro.ExperimentalDebugRoutes
import io.github.twinsen81.lustro.escapeForJson
import io.github.twinsen81.lustro.sqlite.TableHint
import java.io.File
import java.util.Locale

/**
 * The routes of the SQLite tab, under `/api/v1/sqlite/`. [declare] declares them, and the tab's
 * OpenAPI document comes from the declarations, with the text of [SqliteDocument].
 */
@OptIn(ExperimentalDebugRoutes::class)
internal class SqliteRoutes(private val registry: SqliteRegistry, private val limit: ReadLimit = ReadLimit()) {
    private val console = SqliteQuery(limit)

    fun declare(routes: DebugRoutes) {
        routes.title = SqliteDocument.TITLE
        routes.version = "1.2"
        routes.description = SqliteDocument.DESCRIPTION
        for ((name, schema) in SQLITE_SCHEMAS) routes.component(name, schema)

        routes.get("databases") {
            operationId = "listSqliteDatabases"
            summary = "List the databases."
            description = SqliteDocument.DATABASES_DESCRIPTION
            response("The databases.", SqliteDocument.DATABASES_RESPONSE_SCHEMA)
            errors(504)
            handle { call -> answer(call) { signal -> databases(signal) } }
        }
        routes.get("tables") {
            operationId = "listSqliteTables"
            summary = "List the tables and views of a database."
            description = SqliteDocument.TABLES_DESCRIPTION
            val db = query.name("db", SqliteDocument.DB)
            response("The tables and views.", SqliteDocument.TABLES_RESPONSE_SCHEMA)
            errors(404, 500, 504)
            handle { call -> answer(call) { signal -> tables(entry(call[db]), signal) } }
        }
        routes.get("schema") {
            operationId = "getSqliteSchema"
            summary = "Get the columns, foreign keys, and row count of a table or view."
            description = SqliteDocument.SCHEMA_DESCRIPTION
            val db = query.name("db", SqliteDocument.DB)
            val table = query.name("table", SqliteDocument.TABLE)
            response("The schema.", ref("SqliteSchema"))
            errors(404, 500, 504)
            handle { call -> answer(call) { signal -> schema(entry(call[db]), call[table], signal) } }
        }
        routes.get("rows") {
            operationId = "listSqliteRows"
            summary = "Get a page of rows."
            description = SqliteDocument.ROWS_DESCRIPTION
            val db = query.name("db", SqliteDocument.DB)
            val table = query.name("table", SqliteDocument.TABLE)
            val cursor = query.string("cursor") { description = SqliteDocument.CURSOR }
            val limit =
                query.int("limit") {
                    min = 1
                    max = MAX_LIMIT.toLong()
                    description = SqliteDocument.LIMIT
                }.withDefault(DEFAULT_LIMIT)
            val filter = query.string("filter") { description = SqliteDocument.FILTER }
            val sort = query.string("sort") { description = SqliteDocument.SORT }
            // Read by the handler, which takes ASC and DESC too, and an empty dir as asc.
            val dir =
                query.string("dir") {
                    description = SqliteDocument.DIR
                    schema = SqliteDocument.DIR_SCHEMA
                }
            response("The page.", ref("SqliteRowsPage"))
            errors(404, 500, 504)
            handle { call ->
                answer(call) { signal ->
                    val entry = entry(call[db])
                    val offset = offset(call[cursor])
                    val descending = descending(call[dir])
                    rows(entry, call[table], RowsQuery(offset, call[limit], call[filter], call[sort]?.takeIf { it.isNotEmpty() }, descending), signal)
                }
            }
        }
        routes.post("query") {
            operationId = "runSqliteQuery"
            summary = "Run a statement read-only."
            description = SqliteDocument.QUERY_DESCRIPTION
            bodyDescription = "The database and the statement."
            val db = body.name("db", SqliteDocument.DB)
            val sql = body.text("sql", SqliteDocument.SQL)
            response("The result.", ref("SqliteQueryResult"))
            errors(404, 500, 504)
            handle { call -> answer(call) { signal -> query(entry(call[db]), call[sql], signal) } }
        }
        routes.post("exec") {
            operationId = "execSqliteStatement"
            summary = "Run a statement that writes."
            description = SqliteDocument.EXEC_DESCRIPTION
            bodyDescription = "The database and the statement."
            val db = body.name("db", SqliteDocument.WRITABLE_DB)
            val sql = body.text("sql", SqliteDocument.SQL)
            response("Committed.", ref("SqliteExecResult"))
            errors(404, 500, 504)
            handle { call -> answer(call) { _ -> exec(writable(call[db]), call[sql], call.request) } }
        }
        routes.post("edit") {
            operationId = "editSqliteRow"
            summary = "Change one row."
            description = SqliteDocument.EDIT_DESCRIPTION
            bodyDescription = "The row, and the values to set."
            val row = body.row()
            val values =
                body.json("values") {
                    description = SqliteDocument.VALUES
                    schema = SqliteDocument.VALUES_SCHEMA
                }.required()
            response("Committed.", ref("SqliteWriteOk"))
            errors(404, 500, 504)
            handle { call -> answer(call) { signal -> edit(row.read(call), call[values], call.request, signal) } }
        }
        routes.post("delete") {
            operationId = "deleteSqliteRow"
            summary = "Delete one row."
            description = SqliteDocument.DELETE_DESCRIPTION
            bodyDescription = "The row."
            val row = body.row()
            response("Committed.", ref("SqliteWriteOk"))
            errors(404, 500, 504)
            handle { call -> answer(call) { signal -> edit(row.read(call), values = null, call.request, signal) } }
        }
    }

    // A name that the client gives: a database, or a table in a GET route.
    private fun DebugParams.name(name: String, description: String): DebugParam<String> =
        string(name) {
            minLength = 1
            this.description = description
        }.required()

    // A body field that the handler checks for an empty value, after the database: a write to a
    // database that is not writable is a 403 whatever the statement or the table is.
    private fun DebugParams.text(name: String, description: String): DebugParam<String> =
        string(name) {
            this.description = description
            schema = NON_EMPTY_STRING
        }.required()

    // The row of POST edit and POST delete. rowid is JSON, because it is a whole number or a string of one.
    private fun DebugBodyParams.row(): RowParams =
        RowParams(
            db = name("db", SqliteDocument.WRITABLE_DB),
            table = text("table", SqliteDocument.WRITABLE_TABLE),
            rowid =
                json("rowid") {
                    description = SqliteDocument.ROWID
                    schema = SqliteDocument.ROWID_SCHEMA
                },
            key =
                json("key") {
                    description = SqliteDocument.KEY
                    schema = SqliteDocument.KEY_SCHEMA
                },
        )

    private class RowParams(
        val db: DebugParam<String>,
        val table: DebugParam<String>,
        val rowid: DebugParam<String?>,
        val key: DebugParam<String?>,
    ) {
        fun read(call: DebugCall): RowBody = RowBody(call[db], call[table], call[rowid], call[key])
    }

    /** The row that a client names, with `rowid` and `key` as the JSON text of their values. */
    private class RowBody(val db: String, val table: String, val rowid: String?, val key: String?)

    // A read stops when the runtime gives up on the request, at its timeout, so that a long one
    // does not keep a SQLite lock or a request slot.
    private inline fun answer(call: DebugCall, block: (CancellationSignal) -> DebugResponse): DebugResponse {
        val signal = CancellationSignal()
        call.request.onCancel { signal.cancel() }
        return try {
            block(signal)
        } catch (e: RouteException) {
            DebugResponse.error(e.message.orEmpty(), status = e.status, code = e.code, field = e.field, hint = e.hint)
        } catch (_: OperationCanceledException) {
            DebugResponse.error("The statement was stopped: the request timed out or the server stopped", status = 504)
        } catch (e: SQLiteException) {
            DebugResponse.error(e.message ?: e.javaClass.simpleName, status = 500, code = "sqlite_error")
        }
    }

    // The client's statement runs on a connection that SQLite opened read-only, never on the app's
    // handle: rawQuery runs any statement that it is given, a DELETE too.
    private fun query(entry: DbEntry, text: String, signal: CancellationSignal): DebugResponse {
        val sql = statement(text, exec = false)
        val result = readOnly(entry).use { connection -> statementErrors { console.run(connection, sql, signal, entry::masks) } }
        return DebugResponse.json {
            append("{\"database\":\"").append(entry.name.escapeForJson()).append('"')
            append(",\"columns\":[").append(result.columns.joinToString(",") { "\"${it.escapeForJson()}\"" }).append(']')
            append(",\"rows\":[")
            result.rows.forEachIndexed { index, row ->
                if (index > 0) append(',')
                append('[')
                row.forEachIndexed { cell, value ->
                    if (cell > 0) append(',')
                    Cells.append(this, value)
                }
                append(']')
            }
            append("],\"truncated\":").append(result.truncated).append('}')
        }
    }

    private fun exec(entry: DbEntry, text: String, request: DebugRequest): DebugResponse {
        val sql = statement(text, exec = true)
        val changes = live(entry).use { connection -> statementErrors { SqliteWriter(connection, request).exec(sql) } }
        afterWrite(entry)
        return DebugResponse.json { append("{\"status\":\"ok\",\"changes\":").append(changes).append('}') }
    }

    // POST edit and POST delete: one row, which the client names by its rowid or its primary key.
    // values is null for POST delete.
    private fun edit(row: RowBody, values: String?, request: DebugRequest, signal: CancellationSignal): DebugResponse {
        val entry = writable(row.db)
        val name = nonEmpty(row.table, "table")
        live(entry).use { connection ->
            val reader = SqliteReader(connection, signal)
            val table = reader.table(name) ?: throw noTable(entry, name)
            if (table.isView) throw RouteException("${table.name} is a view: only the rows of a table change", field = "table")
            val columns = reader.columns(table)
            val target = SqliteRowRequest.target(row.rowid, row.key, table, columns, reader.rowid(table, columns))
            val writer = SqliteWriter(connection, request)
            if (values == null) {
                statementErrors(field = null) { writer.delete(target) }
            } else {
                val bound = SqliteRowRequest.values(values, columns)
                statementErrors(field = "values") { writer.edit(target, bound) }
            }
        }
        afterWrite(entry)
        return DebugResponse.json { append("{\"status\":\"ok\"}") }
    }

    // The write is committed: what the app's callback throws is the app's, and does not undo it.
    @Suppress("TooGenericExceptionCaught")
    private fun afterWrite(entry: DbEntry) {
        val onWrite = entry.onWrite ?: return
        try {
            onWrite.run()
        } catch (e: Exception) {
            Log.w(TAG, "onWrite of the database ${entry.name} threw; the write is committed", e)
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
                append(",\"policy\":\"").append(entry.policy.name.lowercase(Locale.ROOT)).append('"')
                var path = entry.source.file?.path
                var version: Int? = null
                var error: String? = null
                try {
                    open(entry).use { connection ->
                        path = connection.path
                        version = reader(connection, signal).userVersion()
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
                // In WAL mode, the changes since the last checkpoint are in the -wal file, which
                // can be larger than the database file.
                val walSize = path?.let { File("$it-wal") }?.takeIf { it.isFile }?.length()
                append(",\"path\":").appendString(path)
                append(",\"sizeBytes\":").append(size)
                append(",\"walBytes\":").append(walSize)
                append(",\"schemaVersion\":").append(version)
                if (error != null) append(",\"error\":").appendString(error)
                append('}')
            }
            append("]}")
        }
    }

    private fun tables(entry: DbEntry, signal: CancellationSignal): DebugResponse {
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
                    } catch (e: RouteException) {
                        // A count that the read limit stopped: the other tables are still listed.
                        error = e.message
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

    private fun schema(entry: DbEntry, name: String, signal: CancellationSignal): DebugResponse {
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
                    append(",\"masked\":").append(entry.masks(column.name))
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

    // query.sort is the name that the client gave, which the table's schema turns into a column.
    private fun rows(entry: DbEntry, name: String, query: RowsQuery, signal: CancellationSignal): DebugResponse =
        read(entry, signal) { reader ->
            val table = reader.table(name) ?: throw noTable(entry, name)
            val columns = reader.columns(table)
            val rowid = reader.rowid(table, columns)
            val sort = query.sort?.let { sortColumn(it, columns, rowid) }
            val page = reader.rows(table, columns, rowid, RowsQuery(query.offset, query.limit, query.filter, sort, query.descending)) { entry.masks(it.name) }
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

    private fun descending(text: String?): Boolean =
        when {
            text.isNullOrEmpty() || text.equals("asc", ignoreCase = true) -> false
            text.equals("desc", ignoreCase = true) -> true
            else -> throw RouteException("dir must be asc or desc", field = "dir")
        }

    private fun entry(name: String): DbEntry = registry.find(name) ?: throw RouteException("No database named $name", status = 404, field = "db")

    // Checked by the route, before the statement, so that no request writes to a database that the app did not open for writes.
    private fun writable(name: String): DbEntry {
        val entry = entry(name)
        if (!entry.writable) {
            val why = if (entry.registered) "the app registers it without writable = true" else "a discovered file is never writable"
            throw RouteException("The database ${entry.name} is read-only: $why", status = 403, code = "read_only", field = "db")
        }
        return entry
    }

    private fun nonEmpty(value: String, field: String): String = value.ifEmpty { throw RouteException("$field must not be empty", field = field) }

    private fun statement(sql: String, exec: Boolean): String {
        if (!SqliteStatements.hasStatement(sql)) throw RouteException("sql has no statement", field = "sql")
        SqliteStatements.refusal(sql, exec)?.let { throw RouteException(it, code = "statement_refused", field = "sql") }
        return sql
    }

    // A connection that SQLite opens read-only, for one request, to the file of the database. A live
    // handle names its file; a second copy of SQLite in the process would not see the app's locks.
    private fun readOnly(entry: DbEntry): DbConnection {
        if (entry.source is FileSource) return open(entry)
        val file =
            open(entry).use { live ->
                val path =
                    live.path ?: throw RouteException(
                        "The database ${entry.name} is in memory, and query needs a file to open read-only. Read its rows with GET rows",
                        code = "query_unavailable",
                        field = "db",
                    )
                if (!live.platformSqlite()) {
                    throw RouteException(
                        "The database ${entry.name} runs on another copy of SQLite than the platform's, such as SQLCipher's, " +
                            "and a read-only connection of the platform's SQLite to its file would not see its locks. Read its rows with GET rows",
                        code = "query_unavailable",
                        field = "db",
                    )
                }
                File(path)
            }
        return open(entry.name) { FileSource(file).open() }
    }

    // A writable database is always a live handle: a file is opened read-only.
    private fun live(entry: DbEntry): LiveConnection {
        val connection = open(entry)
        if (connection is LiveConnection) return connection
        connection.close()
        throw RouteException("The database ${entry.name} is read-only", status = 403, code = "read_only", field = "db")
    }

    // SQLite's errors in a client's statement are the client's to fix, but for those of the file and the device.
    private fun <T> statementErrors(field: String? = "sql", block: () -> T): T =
        try {
            block()
        } catch (e: SQLiteReadOnlyDatabaseException) {
            throw RouteException(
                "SQLite refused a write: ${e.message}. query only reads; exec writes, to a writable database",
                status = 403,
                code = "read_only",
                field = field,
            )
        } catch (e: SQLiteBlobTooBigException) {
            throw RouteException(
                "A row of the result is too big for Android's cursor window: ${e.message}. Read a long value in parts, with substr() or length()",
                code = "row_too_big",
                field = field,
            )
        } catch (e: SQLiteException) {
            throw RouteException(e.message ?: e.javaClass.simpleName, status = if (serverSide(e)) 500 else 400, code = "sqlite_error", field = field)
        }

    private fun serverSide(e: SQLiteException): Boolean =
        when (e) {
            is SQLiteDatabaseCorruptException, is SQLiteDiskIOException, is SQLiteFullException -> true
            is SQLiteOutOfMemoryException, is SQLiteDatabaseLockedException -> true
            else -> false
        }

    private fun noTable(entry: DbEntry, name: String): RouteException =
        RouteException("The database ${entry.name} has no table or view named $name", status = 404, field = "table")

    private fun <T> read(entry: DbEntry, signal: CancellationSignal, block: (SqliteReader) -> T): T = open(entry).use { block(reader(it, signal)) }

    // A connection that the tab opened itself has the limit out of WAL mode; the app's handle has none.
    private fun reader(connection: DbConnection, signal: CancellationSignal): SqliteReader =
        SqliteReader(connection, signal, limit.takeIf { it.applies(connection, signal) })

    private fun open(entry: DbEntry): DbConnection = open(entry.name) { entry.source.open() }

    // Anything that the app's supplier throws is the app's, so it is reported, not thrown to the runtime.
    @Suppress("TooGenericExceptionCaught")
    private fun open(name: String, opener: () -> DbConnection): DbConnection =
        try {
            opener()
        } catch (e: Exception) {
            throw RouteException("The database $name does not open: ${e.message ?: e.javaClass.name}", status = 500, code = "open_failed", field = "db")
        }

    private fun StringBuilder.appendHint(hint: TableHint?) {
        if (hint?.entity != null) append(",\"entity\":").appendString(hint.entity)
        if (hint?.purpose != null) append(",\"purpose\":").appendString(hint.purpose)
    }

    private fun StringBuilder.appendString(text: String?): StringBuilder =
        if (text == null) append("null") else append('"').append(text.escapeForJson()).append('"')

    private companion object {
        const val TAG = "LustroSqlite"
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 500
        const val NON_EMPTY_STRING = "{\"type\": \"string\", \"minLength\": 1}"
    }
}

/** A request that a route answers with an error envelope. */
internal class RouteException(
    message: String,
    val status: Int = 400,
    val code: String? = null,
    val field: String? = null,
    val hint: String? = null,
) : Exception(message)
