package io.github.twinsen81.lustro.sqlite.internal

import android.app.Application
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.sqlite.SqliteDb
import io.github.twinsen81.lustro.sqlite.internal.Fixtures.json
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.SQLiteMode

/**
 * POST query and POST exec. The probes that showed rawQuery to run a write, and a BEGIN to leave a
 * transaction on a request thread, run here against the routes: SQLite refuses every write of
 * query, and neither route leaves a transaction or a setting on the app's connection.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SqliteConsoleTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val databases = File(app.dataDir, "databases")
    private lateinit var live: SQLiteDatabase
    private lateinit var routes: SqliteRoutes

    @Before
    fun setUp() {
        live = SQLiteDatabase.openOrCreateDatabase(File(databases, "app.db").apply { parentFile?.mkdirs() }, null)
        live.enableWriteAheadLogging()
        Fixtures.create(live)
        routes = routes(SqliteDb.framework("app", writable = true) { live }, SqliteDb.framework("locked") { live })
    }

    @After
    fun tearDown() {
        live.close()
    }

    @Test
    fun `query returns the columns and the rows, each cell by its own type, and a long text whole`() {
        live.execSQL("UPDATE notes SET body = ?, data = zeroblob(70000), mixed = x'0102' WHERE id = 1", arrayOf(Fixtures.LONG_TEXT))

        val result = ok(query("SELECT id, title, body, data, mixed, score, NULL AS empty, 9007199254740993 AS big FROM notes WHERE id = 1"))

        assertEquals("app", result.getString("database"))
        assertEquals(listOf("id", "title", "body", "data", "mixed", "score", "empty", "big"), strings(result.getJSONArray("columns")))
        assertFalse(result.getBoolean("truncated"))
        val row = result.getJSONArray("rows").getJSONArray(0)
        assertEquals(1, row.getJSONObject(0).getInt("value"))
        assertEquals("integer", row.getJSONObject(0).getString("type"))
        assertEquals("Note 1", row.getJSONObject(1).getString("value"))
        // A page of rows cuts a text at 4096 characters; a query is how a client reads it all.
        assertEquals(Fixtures.LONG_TEXT, row.getJSONObject(2).getString("value"))
        assertFalse(row.getJSONObject(2).has("truncated"))
        assertEquals(70_000, row.getJSONObject(3).getInt("bytes"))
        // A blob in a column without a type.
        assertEquals("blob", row.getJSONObject(4).getString("type"))
        assertEquals(2, row.getJSONObject(4).getInt("bytes"))
        assertEquals(1.5, row.getJSONObject(5).getDouble("value"), 0.0)
        assertEquals("null", row.getJSONObject(6).getString("type"))
        assertEquals("9007199254740993", row.getJSONObject(7).getString("value"))
    }

    @Test
    fun `columns with the same name stay apart, and a pragma reads through query`() {
        val same = ok(query("SELECT 1 AS a, 2 AS a"))
        assertEquals(listOf("a", "a"), strings(same.getJSONArray("columns")))
        assertEquals(2, same.getJSONArray("rows").getJSONArray(0).getJSONObject(1).getInt("value"))

        val version = ok(query("PRAGMA user_version"))
        assertEquals(7, version.getJSONArray("rows").getJSONArray(0).getJSONObject(0).getInt("value"))
        val keys = ok(query("SELECT * FROM pragma_foreign_key_list('notes')"))
        assertEquals(1, keys.getJSONArray("rows").length())
    }

    @Test
    fun `a result stops at 1000 rows, and says that it is cut`() {
        val result = ok(query("WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM n WHERE x < 1500) SELECT x FROM n"))

        assertEquals(SqliteQuery.MAX_ROWS, result.getJSONArray("rows").length())
        assertTrue(result.getBoolean("truncated"))
        assertFalse(ok(query("SELECT * FROM notes")).getBoolean("truncated"))
        // Exactly as many rows as the cap is a whole result.
        assertFalse(ok(query("WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM n WHERE x < 1000) SELECT x FROM n")).getBoolean("truncated"))
    }

    @Test
    fun `a result stops before the next row once its texts pass the character budget`() {
        val chars = 1024 * 1024
        val result = ok(query("WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM n WHERE x < 10) SELECT x, hex(zeroblob(${chars / 2})) FROM n"))

        assertEquals((SqliteQuery.MAX_CHARS / chars).toInt(), result.getJSONArray("rows").length())
        assertTrue(result.getBoolean("truncated"))
    }

    @Test
    fun `a row too big for the cursor window is a 400 with code row_too_big`() {
        live.execSQL("UPDATE notes SET data = zeroblob(3000000) WHERE id = 1")

        assertError(400, "row_too_big", query("SELECT data FROM notes WHERE id = 1"))
        assertEquals(3_000_000, ok(query("SELECT length(data) FROM notes WHERE id = 1")).getJSONArray("rows").getJSONArray(0).getJSONObject(0).getInt("value"))
    }

    @Test
    fun `a row that the cursor window left out, as Android does before API 28, is a row too big, not a row of NULLs`() {
        val connection =
            object : DbConnection {
                override val path: String = "skipped.db"

                override fun query(sql: String, args: Array<String>, signal: android.os.CancellationSignal): Cursor =
                    if (sql.startsWith("PRAGMA journal_mode")) MatrixCursor(arrayOf("journal_mode")).apply { addRow(arrayOf("wal")) } else SkippedRow()

                override fun platformSqlite(): Boolean = true

                override val separate: Boolean = true

                override fun close() = Unit
            }

        val thrown = assertThrows(android.database.sqlite.SQLiteBlobTooBigException::class.java) { SqliteQuery().run(connection, "SELECT b FROM t", android.os.CancellationSignal()) }
        assertTrue(thrown.message, thrown.message!!.contains("row 0"))
    }

    // One row, which the window does not hold: the window has no rows.
    private class SkippedRow : android.database.AbstractWindowedCursor() {
        init {
            window = android.database.CursorWindow("skipped").apply { setNumColumns(1) }
        }

        override fun getCount(): Int = 1

        override fun getColumnNames(): Array<String> = arrayOf("b")
    }

    @Test
    fun `SQLite refuses every write of query, also the ones that a statement classifier takes for a read`() {
        val writes =
            listOf(
                "DELETE FROM notes",
                "WITH x AS (SELECT 1) DELETE FROM notes",
                "WITH `x(` AS (SELECT 1) DELETE FROM notes",
                "WITH [x(] AS (SELECT 1) DELETE FROM notes",
                "WITH \"x(\" AS (SELECT 1) DELETE FROM notes",
                "/* SELECT */ UPDATE notes SET title = 'changed'",
                "INSERT INTO notes (id, title) VALUES (99, 'no')",
                "DROP TABLE notes",
                "CREATE TABLE created (a)",
                "PRAGMA user_version(9)",
                "PRAGMA user_version = 9",
            )
        for (sql in writes) {
            val response = query(sql)
            assertError(403, "read_only", response)
            assertEquals(sql, "sql", response.json().getString("field"))
        }

        assertEquals(3, count("notes"))
        assertEquals(listOf("Note 1", "Note 2", "Note 3"), titles())
        assertEquals(7, live.version)
        assertFalse(tables().contains("created"))
    }

    @Test
    fun `a query can't set the process's heap limit, which every connection of the app shares`() {
        val response = query("PRAGMA main.\"hard_heap_limit\" = 1")

        assertError(400, "statement_refused", response)
        // A limit of 1 byte would make this write fail with SQLITE_NOMEM, as every later open of a connection.
        live.execSQL("INSERT INTO notes (id, title, body) VALUES (4, 'after the pragma', ?)", arrayOf("x".repeat(100_000)))
        assertEquals(4, count("notes"))
    }

    @Test
    fun `a byte order mark before a refused statement does not let it run on the app's connection`() {
        assertError(400, "statement_refused", exec("\uFEFFPRAGMA query_only = ON"))
        assertError(400, "statement_refused", exec("BEGIN\uFEFF"))

        assertFalse(live.inTransaction())
        live.execSQL("INSERT INTO notes (id, title) VALUES (4, 'still writable')")
        assertAppWritesOnAnotherThread()
    }

    @Test
    fun `PRAGMA query_only off in a query turns no write on, there or on the app's connection`() {
        assertEquals(200, query("PRAGMA query_only = OFF").status)
        assertEquals(200, query("PRAGMA query_only(1)").status)

        assertError(403, "read_only", query("DELETE FROM notes"))
        assertEquals(3, count("notes"))
        // The app's own connection writes as before.
        live.execSQL("INSERT INTO notes (id, title) VALUES (4, 'after the pragmas')")
        assertEquals(4, count("notes"))
    }

    @Test
    fun `BEGIN, SAVEPOINT, and the other refused statements never run, and no request thread stays in a transaction`() {
        val refused = listOf("BEGIN", "BEGIN IMMEDIATE", "/* x */ BEGIN", "-- x\nBEGIN", ";; begin", "SAVEPOINT s", " ; /**/ SAVEPOINT s", "COMMIT", "ROLLBACK", "END")
        for (sql in refused) {
            for (path in listOf("query", "exec")) {
                val response = post(path, sql)
                assertError(400, "statement_refused", response)
                assertFalse("$path $sql", live.inTransaction())
            }
        }
        for (sql in listOf("ATTACH DATABASE '${File(app.cacheDir, "attached.db").path}' AS a", "DETACH a", "VACUUM", "VACUUM INTO '${File(app.cacheDir, "copy.db").path}'")) {
            assertError(400, "statement_refused", query(sql))
            assertError(400, "statement_refused", exec(sql))
        }
        assertFalse(File(app.cacheDir, "attached.db").exists())
        assertFalse(File(app.cacheDir, "copy.db").exists())

        assertAppWritesOnAnotherThread()
    }

    @Test
    fun `SQLite runs only the first statement of a text, so a statement after it can't start a transaction`() {
        val response = exec("UPDATE notes SET title = 'first' WHERE id = 1; BEGIN")

        assertEquals(response.json().toString(), 200, response.status)
        assertEquals("first", titles()[0])
        assertFalse(live.inTransaction())
        assertAppWritesOnAnotherThread()
        assertEquals(200, query("SELECT 1; DELETE FROM notes").status)
        assertEquals(4, count("notes"))
    }

    @Test
    fun `the app writes to its live WAL handle while a query runs`() {
        val request = Fixtures.request("query", body("app", ENDLESS))
        val running = Executors.newSingleThreadExecutor()
        val slow = running.submit<DebugResponse> { routes.handle(request) }
        try {
            // The query has its read open on the file when the app writes.
            Thread.sleep(300)
            assertFalse(slow.isDone)

            val started = System.nanoTime()
            live.execSQL("INSERT INTO notes (id, title) VALUES (4, 'during a query')")
            val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

            assertTrue("the app's write waited $tookMs ms for the query", tookMs < 500)
            assertFalse(slow.isDone)
        } finally {
            // As the runtime does at the request's timeout.
            request.cancel()
        }
        assertEquals(504, slow.get(5, TimeUnit.SECONDS).status)
        assertEquals(4, count("notes"))
        running.shutdown()
    }

    @Test
    fun `out of WAL mode, a query stops at the limit, before a write of the app gives up on the lock`() {
        val file = Fixtures.file(databases, "rollback.db")
        val app = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        assertFalse(app.isWriteAheadLoggingEnabled)
        val routes = SqliteRoutes(SqliteRegistry(listOf(SqliteDb.framework("rollback", writable = true) { app }), directory = null))
        val request = Fixtures.request("query", body("rollback", ENDLESS))
        val running = Executors.newSingleThreadExecutor()
        val slow = running.submit<DebugResponse> { routes.handle(request) }
        try {
            Thread.sleep(300)
            assertFalse(slow.isDone)

            // The write waits for the query's SHARED lock, which the limit drops before Android's busy
            // timeout, 2.5 s, after which the write would fail with SQLiteDatabaseLockedException.
            val started = System.nanoTime()
            app.execSQL("INSERT INTO notes (id, title) VALUES (4, 'after the limit')")
            val waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

            assertTrue("the write waited $waitedMs ms", waitedMs in 1_000 until 2_500)
            assertError(504, "read_limit", slow.get(5, TimeUnit.SECONDS))
        } finally {
            request.cancel()
        }
        assertEquals(4, app.rawQuery("SELECT count(*) FROM notes", null).use { it.moveToFirst(); it.getInt(0) })
        running.shutdown()
        app.close()
    }

    @Test
    fun `a WAL database has no limit but the request's timeout`() {
        val routes = SqliteRoutes(SqliteRegistry(listOf(SqliteDb.framework("app") { live }), directory = null), ReadLimit(1))

        assertEquals(200, Fixtures.post(routes, "query", body("app", "WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM n WHERE x < 200000) SELECT count(*) FROM n, notes")).status)
    }

    @Test
    fun `a cancelled request stops its query with a 504`() {
        val request = Fixtures.request("query", body("app", "SELECT * FROM notes"))
        request.cancel()

        assertEquals(504, routes.handle(request)!!.status)
    }

    @Test
    fun `a file database and a discovered one take a query, on their read-only connection`() {
        val catalog = Fixtures.file(app.cacheDir, "catalog.db")
        Fixtures.file(databases, "library.db")
        val routes = SqliteRoutes(SqliteRegistry(listOf(SqliteDb.file("catalog", catalog)), databases))

        assertEquals(3, ok(Fixtures.post(routes, "query", body("catalog", "SELECT * FROM notes"))).getJSONArray("rows").length())
        assertEquals(1, ok(Fixtures.post(routes, "query", body("library.db", "SELECT * FROM folders"))).getJSONArray("rows").length())
        assertError(403, "read_only", Fixtures.post(routes, "query", body("catalog", "DELETE FROM notes")))
    }

    @Test
    fun `a database in memory, and one on another copy of SQLite, take no query`() {
        val memory = SQLiteDatabase.create(null)
        val helper = supportHelper()
        val other = OtherSqlite(helper.writableDatabase)
        val routes = routes(SqliteDb.framework("memory") { memory }, SqliteDb.support("other") { other }, SqliteDb.support("room") { helper.writableDatabase })

        assertError(400, "query_unavailable", Fixtures.post(routes, "query", body("memory", "SELECT 1")))
        assertError(400, "query_unavailable", Fixtures.post(routes, "query", body("other", "SELECT 1")))
        // The platform's SQLite, through androidx: the same build answers the same.
        assertEquals(200, Fixtures.post(routes, "query", body("room", "SELECT * FROM items")).status)
        helper.close()
        memory.close()
    }

    @Test
    fun `a statement that SQLite can't run is a 400 with SQLite's message, and a missing or empty sql a 400`() {
        val syntax = query("SELEC 1")
        assertError(400, "sqlite_error", syntax)
        assertTrue(syntax.json().getString("message"), syntax.json().getString("message").contains("syntax error"))
        assertError(400, "sqlite_error", query("SELECT * FROM missing"))

        assertField(400, "sql", Fixtures.post(routes, "query", JSONObject().put("db", "app")))
        assertField(400, "sql", query(" -- nothing\n ;"))
        assertField(400, "db", Fixtures.post(routes, "query", JSONObject().put("sql", "SELECT 1")))
        assertField(404, "db", Fixtures.post(routes, "query", body("missing", "SELECT 1")))
        assertField(400, "body", routes.handle(io.github.twinsen81.lustro.DebugRequest("query", "POST", body = "not json".toByteArray()))!!)
        assertEquals(405, Fixtures.get(routes, "query", "db" to "app").status)
    }

    @Test
    fun `exec writes to a writable database, and counts the rows that its statement changed`() {
        assertEquals(1, changes(exec("INSERT INTO notes (id, title) VALUES (4, 'from exec')")))
        assertEquals(4, changes(exec("UPDATE notes SET score = 0")))
        // A statement that changes no row counts none, although SQLite's changes() still holds the last UPDATE's.
        assertEquals(0, changes(exec("CREATE TABLE created (a)")))
        assertEquals(0, changes(exec("UPDATE notes SET score = 1 WHERE id = 99")))
        // A cascade deletes more rows than the statement does: those are the foreign key's, and are not counted.
        live.setForeignKeyConstraintsEnabled(true)
        assertEquals(1, changes(exec("DELETE FROM folders WHERE id = 1")))

        assertEquals(1, count("notes"))
        assertTrue(tables().contains("created"))
        assertFalse(live.inTransaction())
    }

    @Test
    fun `exec refuses every PRAGMA, so the app's connection keeps its settings`() {
        live.setForeignKeyConstraintsEnabled(true)
        for (sql in listOf("PRAGMA foreign_keys = OFF", "PRAGMA foreign_keys(0)", "PRAGMA query_only = ON", "PRAGMA query_only(1)", "/* x */ PRAGMA query_only(1)", "EXPLAIN PRAGMA query_only = 1")) {
            assertError(400, "statement_refused", exec(sql))
        }

        // The app's writes still check the foreign keys, and are not refused.
        assertThrows(SQLiteConstraintException::class.java) { live.execSQL("INSERT INTO notes (id, title, folder_id) VALUES (4, 'x', 99)") }
        live.execSQL("INSERT INTO notes (id, title, folder_id) VALUES (4, 'the app writes', 1)")
        assertEquals(4, count("notes"))
    }

    @Test
    fun `exec refuses a TEMP object, which would stay on the app's connection, and rolls its statement back`() {
        for (sql in listOf("CREATE TEMP TABLE scratch (a)", "CREATE TABLE temp.scratch (a)", "CREATE TEMP VIEW v AS SELECT 1", "CREATE TEMP TRIGGER t AFTER INSERT ON notes BEGIN DELETE FROM notes; END")) {
            assertError(400, "statement_refused", exec(sql))
        }
        live.execSQL("CREATE TEMP TABLE kept (a)")
        assertError(400, "statement_refused", exec("DROP TABLE temp.kept"))

        // In a transaction, on the primary connection, where the app's DDL and exec ran.
        live.beginTransactionNonExclusive()
        try {
            assertEquals(listOf("kept"), live.rawQuery("SELECT name FROM sqlite_temp_master", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } })
        } finally {
            live.endTransaction()
        }
        live.execSQL("INSERT INTO notes (id, title) VALUES (4, 'no trigger')")
        assertEquals(4, count("notes"))
    }

    @Test
    fun `a failed exec changes nothing and leaves no transaction`() {
        val response = exec("INSERT INTO folders (id, name) VALUES (1, 'taken')")

        assertError(400, "sqlite_error", response)
        assertTrue(response.json().getString("message"), response.json().getString("message").contains("UNIQUE"))
        assertFalse(live.inTransaction())
        // A statement that returns rows is for query.
        assertError(400, "sqlite_error", exec("SELECT * FROM notes"))
        assertAppWritesOnAnotherThread()
    }

    @Test
    fun `exec on a database that is not writable is a 403, whatever its statement is`() {
        val catalog = Fixtures.file(app.cacheDir, "catalog.db")
        Fixtures.file(databases, "library.db")
        val routes = SqliteRoutes(SqliteRegistry(listOf(SqliteDb.framework("locked") { live }, SqliteDb.file("catalog", catalog)), databases))

        for (db in listOf("locked", "catalog", "library.db")) {
            for (sql in listOf("DELETE FROM notes", "BEGIN", "PRAGMA foreign_keys = OFF", "")) {
                val response = Fixtures.post(routes, "exec", body(db, sql))
                assertError(403, "read_only", response)
                assertEquals("db", response.json().getString("field"))
            }
        }
        assertEquals(3, count("notes"))
    }

    @Test
    fun `a cancelled exec changes nothing`() {
        val request = Fixtures.request("exec", body("app", "DELETE FROM notes"))
        request.cancel()

        assertEquals(504, routes.handle(request)!!.status)
        assertEquals(3, count("notes"))
        assertFalse(live.inTransaction())
    }

    @Test
    fun `exec on Room's support handle writes through it`() {
        val helper = supportHelper()
        val routes = routes(SqliteDb.support("room", writable = true) { helper.writableDatabase })

        assertEquals(2, changes(Fixtures.post(routes, "exec", body("room", "UPDATE items SET name = upper(name)"))))
        assertError(400, "statement_refused", Fixtures.post(routes, "exec", body("room", "PRAGMA foreign_keys = OFF")))
        assertEquals(listOf("ONE", "TWO"), helper.readableDatabase.query("SELECT name FROM items ORDER BY id").use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } })
        helper.close()
    }

    private fun assertAppWritesOnAnotherThread() {
        val writer = Thread { live.execSQL("INSERT INTO notes (title) VALUES ('from the app')") }
        writer.start()
        writer.join(5_000)
        assertFalse("the app's write waited for a transaction that a request left open", writer.isAlive)
    }

    private fun supportHelper(): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(app)
                .name("support.db")
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL("CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT)")
                            db.execSQL("INSERT INTO items (name) VALUES ('one'), ('two')")
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )

    // A support handle that answers as another build of SQLite does, such as SQLCipher's.
    private class OtherSqlite(private val delegate: SupportSQLiteDatabase) : SupportSQLiteDatabase by delegate {
        override fun query(query: String): Cursor =
            if (query.contains("sqlite_source_id")) MatrixCursor(arrayOf("id")).apply { addRow(arrayOf("2024-01-01 other")) } else delegate.query(query)
    }

    private fun routes(vararg databases: SqliteDb): SqliteRoutes = SqliteRoutes(SqliteRegistry(databases.toList(), directory = null))

    private fun body(db: String, sql: String): JSONObject = JSONObject().put("db", db).put("sql", sql)

    private fun post(path: String, sql: String): DebugResponse = Fixtures.post(routes, path, body("app", sql))

    private fun query(sql: String): DebugResponse = post("query", sql)

    private fun exec(sql: String): DebugResponse = post("exec", sql)

    private fun ok(response: DebugResponse): JSONObject = response.json().also { assertEquals(it.toString(), 200, response.status) }

    private fun changes(response: DebugResponse): Int = ok(response).getInt("changes")

    private fun strings(array: JSONArray): List<String> = (0 until array.length()).map { array.getString(it) }

    private fun count(table: String): Int = live.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }

    private fun titles(): List<String> = live.rawQuery("SELECT title FROM notes ORDER BY id", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    private fun tables(): List<String> = live.rawQuery("SELECT name FROM sqlite_master", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    private fun assertError(status: Int, code: String, response: DebugResponse) {
        val body = response.json()
        assertEquals(body.toString(), status, response.status)
        assertEquals(body.toString(), code, body.optString("code"))
    }

    private fun assertField(status: Int, field: String, response: DebugResponse) {
        val body = response.json()
        assertEquals(body.toString(), status, response.status)
        assertEquals(body.toString(), field, body.getString("field"))
    }

    private companion object {
        // A statement that runs until it is stopped, with a read of the database open.
        const val ENDLESS = "WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM n) SELECT count(*) FROM notes, n"
    }
}
