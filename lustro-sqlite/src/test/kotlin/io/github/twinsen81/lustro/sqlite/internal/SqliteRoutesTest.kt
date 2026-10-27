package io.github.twinsen81.lustro.sqlite.internal

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteReadOnlyDatabaseException
import android.os.CancellationSignal
import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.CursorCodec
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.sqlite.SqliteDb
import io.github.twinsen81.lustro.sqlite.TableHint
import io.github.twinsen81.lustro.sqlite.internal.Fixtures.json
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SqliteRoutesTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val databases = File(app.dataDir, "databases")
    private lateinit var live: SQLiteDatabase
    private lateinit var routes: SqliteRoutes

    @Before
    fun setUp() {
        live = SQLiteDatabase.openOrCreateDatabase(File(databases, "app.db").apply { parentFile?.mkdirs() }, null)
        Fixtures.create(live)
        routes = routes(SqliteDb.framework("app", writable = true, tableHints = HINTS) { live })
    }

    @After
    fun tearDown() {
        live.close()
    }

    @Test
    fun `databases lists each registration with its source, file, size, and schema version`() {
        val file = Fixtures.file(app.cacheDir, "cache.db")
        val memory = SQLiteDatabase.create(null)
        val routes =
            routes(
                SqliteDb.framework("app", writable = true) { live },
                SqliteDb.file("cache", file),
                SqliteDb.framework("memory") { memory },
                SqliteDb.file("missing", File(app.cacheDir, "missing.db")),
            )

        val items = Fixtures.get(routes, "databases").json().getJSONArray("items")

        val registered = items.getJSONObject(0)
        assertEquals("app", registered.getString("name"))
        assertTrue(registered.getBoolean("registered"))
        assertEquals("framework", registered.getString("source"))
        assertTrue(registered.getBoolean("writable"))
        assertEquals(live.path, registered.getString("path"))
        assertEquals(File(live.path).length(), registered.getLong("sizeBytes"))
        assertEquals(7, registered.getInt("schemaVersion"))
        assertFalse(registered.has("error"))
        val cache = items.getJSONObject(1)
        assertEquals("file", cache.getString("source"))
        assertFalse(cache.getBoolean("writable"))
        assertEquals(7, cache.getInt("schemaVersion"))
        val inMemory = items.getJSONObject(2)
        assertTrue(inMemory.isNull("path"))
        assertTrue(inMemory.isNull("sizeBytes"))
        assertEquals(0, inMemory.getInt("schemaVersion"))
        // A file that is not there yet is listed with why it does not open, and the tab does not create it.
        val missing = items.getJSONObject(3)
        assertTrue(missing.isNull("schemaVersion"))
        assertTrue(missing.getString("error"), missing.getString("error").contains("does not open"))
        assertFalse(File(app.cacheDir, "missing.db").exists())
        memory.close()
    }

    @Test
    fun `a closed handle and a supplier that throws are listed with their error`() {
        val closed = SQLiteDatabase.create(null).apply { close() }
        val routes = routes(SqliteDb.framework("closed") { closed }, SqliteDb.framework("throws") { error("not ready") })

        val items = Fixtures.get(routes, "databases").json().getJSONArray("items")

        assertTrue(items.getJSONObject(0).has("error"))
        assertTrue(items.getJSONObject(1).getString("error").contains("not ready"))
        val tables = Fixtures.get(routes, "tables", "db" to "throws")
        assertEquals(500, tables.status)
        assertEquals("open_failed", tables.json().getString("code"))
    }

    @Test
    fun `tables flags the internal ones, counts the rows of tables only, and adds the hints`() {
        val response = Fixtures.get(routes, "tables", "db" to "app").json()
        assertEquals("app", response.getString("database"))
        assertTrue(response.getBoolean("writable"))
        val tables = byName(response.getJSONArray("items"))

        assertFalse(tables.getValue("notes").getBoolean("internal"))
        assertEquals(3, tables.getValue("notes").getInt("rowCount"))
        assertTrue(tables.getValue("notes").getBoolean("hasRowId"))
        assertEquals("Note", tables.getValue("notes").getString("entity"))
        assertEquals("A note that the user wrote.", tables.getValue("notes").getString("purpose"))
        assertFalse(tables.getValue("folders").has("entity"))
        assertFalse(tables.getValue("tags").getBoolean("hasRowId"))
        assertEquals("view", tables.getValue("recent_notes").getString("type"))
        assertFalse(tables.getValue("recent_notes").has("rowCount"))
        assertFalse(tables.getValue("recent_notes").getBoolean("hasRowId"))
        // Android's own table, and the full-text index's shadow tables; the index itself is the app's.
        assertTrue(tables.getValue("android_metadata").getBoolean("internal"))
        assertTrue(tables.getValue("notes_fts_segments").getBoolean("internal"))
        assertTrue(tables.getValue("notes_fts_content").getBoolean("internal"))
        assertFalse(tables.getValue("notes_fts").getBoolean("internal"))
        assertTrue(tables.getValue("shadowed").getBoolean("hasRowId"))
        assertTrue(Fixtures.TRICKY_TABLE in tables)
        val names = tables.keys.toList()
        assertEquals(names.sortedWith(String.CASE_INSENSITIVE_ORDER), names)
    }

    @Test
    fun `schema has the columns, the foreign keys by key, the statement, and the hint`() {
        val schema = Fixtures.get(routes, "schema", "db" to "app", "table" to "notes").json()

        assertEquals("notes", schema.getString("table"))
        assertEquals("table", schema.getString("type"))
        assertEquals(3, schema.getInt("rowCount"))
        assertTrue(schema.getBoolean("hasRowId"))
        assertTrue(schema.getString("sql").startsWith("CREATE TABLE notes"))
        assertEquals("Note", schema.getString("entity"))
        val columns = schema.getJSONArray("columns")
        assertEquals(listOf("id", "title", "body", "data", "mixed", "score", "folder_id"), (0 until columns.length()).map { columns.getJSONObject(it).getString("name") })
        assertEquals(1, columns.getJSONObject(0).getInt("primaryKey"))
        assertEquals("INTEGER", columns.getJSONObject(0).getString("type"))
        assertEquals("", columns.getJSONObject(4).getString("type"))
        val key = schema.getJSONArray("foreignKeys").getJSONObject(0)
        assertEquals("folders", key.getString("table"))
        assertEquals("[\"folder_id\"]", key.getJSONArray("from").toString())
        assertEquals("CASCADE", key.getString("onDelete"))

        val folders = Fixtures.get(routes, "schema", "db" to "app", "table" to "folders").json().getJSONArray("columns")
        assertEquals("'Inbox'", folders.getJSONObject(1).getString("defaultValue"))
        assertTrue(folders.getJSONObject(1).getBoolean("notNull"))
        assertTrue(folders.getJSONObject(0).isNull("defaultValue"))

        val links = Fixtures.get(routes, "schema", "db" to "app", "table" to "note_links").json().getJSONArray("foreignKeys")
        val keys = (0 until links.length()).map { links.getJSONObject(it) }
        val composite = keys.single { it.getString("table") == "tags" }
        assertEquals("[\"a\",\"b\"]", composite.getJSONArray("from").toString())
        assertEquals("[\"note_id\",\"name\"]", composite.getJSONArray("to").toString())
        // A key without columns refers to the other table's primary key.
        assertTrue(keys.single { it.getString("table") == "notes" }.getJSONArray("to").isNull(0))

        val view = Fixtures.get(routes, "schema", "db" to "app", "table" to "RECENT_NOTES").json()
        assertEquals("recent_notes", view.getString("table"))
        assertTrue(view.isNull("rowCount"))
        assertFalse(view.getBoolean("hasRowId"))
    }

    @Test
    fun `a missing parameter is a 400, and an unknown database or table a 404`() {
        assertError(400, "db", Fixtures.get(routes, "tables"))
        assertError(404, "db", Fixtures.get(routes, "tables", "db" to "nope"))
        assertError(400, "table", Fixtures.get(routes, "schema", "db" to "app"))
        assertError(404, "table", Fixtures.get(routes, "schema", "db" to "app", "table" to "nope"))
        assertError(404, "table", Fixtures.get(routes, "rows", "db" to "app", "table" to "nope"))
        assertEquals(405, routes.handle(DebugRequest("rows", "POST"))!!.status)
        assertNull(routes.handle(DebugRequest("query", "POST")))
    }

    @Test
    fun `pages follow the cursor to the last page, whose nextCursor is null`() {
        insertNotes(from = 4, to = 120)

        val first = rows("limit" to "50")
        assertEquals(50, first.getJSONArray("items").length())
        assertEquals("1", first.getJSONArray("items").getJSONObject(0).getString("rowid"))
        assertEquals(50L, CursorCodec.decode(first.getString("nextCursor")))
        val second = rows("limit" to "50", "cursor" to first.getString("nextCursor"))
        assertEquals("51", second.getJSONArray("items").getJSONObject(0).getString("rowid"))
        val third = rows("limit" to "50", "cursor" to second.getString("nextCursor"))
        assertEquals(20, third.getJSONArray("items").length())
        assertTrue(third.isNull("nextCursor"))

        // A page that ends on the last row has no next page.
        val exact = rows("limit" to "60", "cursor" to CursorCodec.encode(60))
        assertEquals(60, exact.getJSONArray("items").length())
        assertTrue(exact.isNull("nextCursor"))
        // The default page is 50 rows, and a cursor past the end gives an empty last page.
        assertEquals(50, rows().getJSONArray("items").length())
        val past = rows("cursor" to CursorCodec.encode(10_000))
        assertEquals(0, past.getJSONArray("items").length())
        assertTrue(past.isNull("nextCursor"))
    }

    @Test
    fun `a bad limit, cursor, or dir is a 400 with its field`() {
        for (limit in listOf("0", "501", "-1", "ten", "1.5")) assertError(400, "limit", get("rows", "limit" to limit))
        assertEquals(3, rows("limit" to "500").getJSONArray("items").length())
        assertEquals(1, rows("limit" to "1").getJSONArray("items").length())
        for (cursor in listOf("%%%", CursorCodec.encode(-1), "bm90IGEgbnVtYmVy")) assertError(400, "cursor", get("rows", "cursor" to cursor))
        assertError(400, "dir", get("rows", "dir" to "up"))
    }

    @Test
    fun `a sort orders the rows, with the rowid as the tiebreak, in either direction`() {
        live.execSQL("UPDATE notes SET score = 1.0")
        live.execSQL("UPDATE notes SET score = 0.5 WHERE id = 2")

        assertEquals(listOf("2", "1", "3"), rowids(rows("sort" to "score")))
        assertEquals(listOf("3", "1", "2"), rowids(rows("sort" to "score", "dir" to "desc")))
        assertEquals(listOf("3", "2", "1"), rowids(rows("dir" to "DESC")))
        assertEquals(listOf("3", "2", "1"), rowids(rows("sort" to "rowid", "dir" to "desc")))
        assertEquals(listOf("2", "1", "3"), rowids(rows("sort" to "SCORE")))
    }

    @Test
    fun `a sort that is not a column is a 400, and never reaches the SQL`() {
        for (sort in listOf("nope", "score; DROP TABLE folders", "score DESC", "\"score\"", "1")) {
            assertError(400, "sort", get("rows", "sort" to sort))
        }
        assertEquals(1, count("folders"))
    }

    @Test
    fun `a filter is a bound value, in which LIKE wildcards and quotes are plain characters`() {
        live.insert("notes", null, ContentValues().apply { put("id", 10); put("title", "100% done_now") })
        live.insert("notes", null, ContentValues().apply { put("id", 11); put("title", "it's a 'quote'") })
        live.insert("notes", null, ContentValues().apply { put("id", 12); put("title", "back\\slash") })

        assertEquals(listOf("10"), rowids(rows("filter" to "0% d")))
        assertEquals(listOf("10"), rowids(rows("filter" to "e_n")))
        assertEquals(listOf("11"), rowids(rows("filter" to "'quote'")))
        assertEquals(listOf("12"), rowids(rows("filter" to "k\\s")))
        assertEquals(listOf("1", "2", "3"), rowids(rows("filter" to "body OF NOTE")))
        // A number matches as text, in any column.
        assertEquals(listOf("3"), rowids(rows("filter" to "4.5")))
        assertEquals(emptyList<String>(), rowids(rows("filter" to "' OR 1=1 --")))
        assertEquals(listOf("10"), rowids(rows("filter" to "%")))
        assertEquals(6, rowids(rows("filter" to "")).size)
    }

    @Test
    fun `a table name is checked against the schema, so a name with quotes is read as a name`() {
        assertError(404, "table", get("rows", "table" to "notes\"; DROP TABLE folders; --"))
        assertEquals(1, count("folders"))

        val tricky = Fixtures.get(routes, "rows", "db" to "app", "table" to Fixtures.TRICKY_TABLE).json()
        assertEquals("safe", tricky.getJSONArray("items").getJSONObject(0).getJSONObject("cells").getJSONObject("v").getString("value"))
        assertEquals(3, count("notes"))
        val schema = Fixtures.get(routes, "schema", "db" to "app", "table" to Fixtures.TRICKY_TABLE).json()
        assertEquals(Fixtures.TRICKY_TABLE, schema.getString("table"))
    }

    @Test
    fun `each cell has its own type, a long text is cut with its length, and a blob is a size`() {
        live.insert(
            "notes",
            null,
            ContentValues().apply {
                put("id", 20)
                put("title", Fixtures.LONG_TEXT)
                put("body", "x".repeat(SqliteReader.MAX_TEXT_CHARS))
                put("data", ByteArray(3_000_000))
                put("score", Double.POSITIVE_INFINITY)
                put("folder_id", Long.MAX_VALUE)
            },
        )
        // SQLite stores any type in any column: a blob in a TEXT column and in one without a type.
        live.execSQL("INSERT INTO notes (id, title, mixed, folder_id) VALUES (21, x'00ff', x'0102', -9007199254740992)")
        live.execSQL("INSERT INTO notes (id, mixed, score) VALUES (22, 9007199254740991, '1.5')")

        val items = rows("cursor" to CursorCodec.encode(3)).getJSONArray("items")
        val long = items.getJSONObject(0).getJSONObject("cells")
        val title = long.getJSONObject("title")
        assertEquals("text", title.getString("type"))
        assertEquals(SqliteReader.MAX_TEXT_CHARS, title.getString("value").length)
        assertTrue(title.getBoolean("truncated"))
        assertEquals(SqliteReader.MAX_TEXT_CHARS + 1L, title.getLong("length"))
        // A text of exactly the cap is whole.
        assertFalse(long.getJSONObject("body").has("truncated"))
        assertEquals(SqliteReader.MAX_TEXT_CHARS, long.getJSONObject("body").getString("value").length)
        // A blob larger than a CursorWindow is only its size.
        assertEquals("{\"type\":\"blob\",\"bytes\":3000000}", long.getJSONObject("data").toString())
        assertEquals("{\"type\":\"null\"}", long.getJSONObject("mixed").toString())
        assertEquals("Infinity", long.getJSONObject("score").getString("value"))
        assertEquals(Long.MAX_VALUE.toString(), long.getJSONObject("folder_id").get("value"))

        val mixed = items.getJSONObject(1).getJSONObject("cells")
        assertEquals("{\"type\":\"blob\",\"bytes\":2}", mixed.getJSONObject("title").toString())
        assertEquals("{\"type\":\"blob\",\"bytes\":2}", mixed.getJSONObject("mixed").toString())
        assertEquals("-9007199254740992", mixed.getJSONObject("folder_id").get("value"))
        assertEquals("integer", mixed.getJSONObject("folder_id").getString("type"))

        val safe = items.getJSONObject(2).getJSONObject("cells")
        assertEquals(9_007_199_254_740_991L, safe.getJSONObject("mixed").getLong("value"))
        assertTrue(safe.getJSONObject("mixed").get("value") is Number)
        // REAL affinity turns the text '1.5' into a number.
        assertEquals(1.5, safe.getJSONObject("score").getDouble("value"), 0.0)
    }

    @Test
    fun `a table without rowid comes in key order with no rowid, and a column named rowid is a column`() {
        live.insert("tags", null, ContentValues().apply { put("note_id", 1); put("name", "a"); put("color", "blue") })

        val tags = rows("table" to "tags").getJSONArray("items")
        assertFalse(tags.getJSONObject(0).has("rowid"))
        assertEquals("a", tags.getJSONObject(0).getJSONObject("cells").getJSONObject("name").getString("value"))
        assertError(400, "sort", get("rows", "table" to "tags", "sort" to "rowid"))

        val shadowed = rows("table" to "shadowed").getJSONArray("items").getJSONObject(0)
        assertEquals("1", shadowed.getString("rowid"))
        assertEquals("not the rowid", shadowed.getJSONObject("cells").getJSONObject("rowid").getString("value"))
        assertEquals(200, get("rows", "table" to "shadowed", "sort" to "rowid").status)

        val view = rows("table" to "recent_notes").getJSONArray("items")
        assertEquals(3, view.length())
        assertFalse(view.getJSONObject(0).has("rowid"))
    }

    @Test
    fun `a cancelled request stops its read with a 504`() {
        val request = DebugRequest("rows", "GET", mapOf("db" to listOf("app"), "table" to listOf("notes")))
        request.cancel()

        assertEquals(504, routes.handle(request)!!.status)
    }

    @Test
    fun `a WAL file is read through a read-only connection, with what the app has not checkpointed`() {
        val file = File(databases, "wal.db")
        val writer = SQLiteDatabase.openOrCreateDatabase(file, null)
        writer.enableWriteAheadLogging()
        Fixtures.create(writer)
        writer.execSQL("INSERT INTO notes (id, title) VALUES (4, 'only in the wal')")
        assertTrue(File(databases, "wal.db-wal").length() > 0)
        val routes = routes(SqliteDb.file("wal", file))

        val items = Fixtures.get(routes, "rows", "db" to "wal", "table" to "notes").json().getJSONArray("items")
        assertEquals("only in the wal", items.getJSONObject(3).getJSONObject("cells").getJSONObject("title").getString("value"))

        // The engine refuses a write on the tab's connection.
        val connection = FileSource(file).open()
        connection.use {
            assertThrows(SQLiteReadOnlyDatabaseException::class.java) {
                it.query("INSERT INTO notes (id, title) VALUES (5, 'no')", emptyArray(), CancellationSignal()).use { cursor -> cursor.moveToFirst() }
            }
        }
        // The app writes while the tab has a read open.
        val reading = FileSource(file).open()
        val cursor = reading.query("SELECT * FROM notes", emptyArray(), CancellationSignal())
        cursor.moveToFirst()
        writer.execSQL("INSERT INTO notes (id, title) VALUES (6, 'written during a read')")
        cursor.close()
        reading.close()
        assertEquals(5, writer.rawQuery("SELECT COUNT(*) FROM notes", null).use { it.moveToFirst(); it.getInt(0) })
        writer.close()
    }

    @Test
    fun `the app writes to a live WAL handle while the tab reads it`() {
        val file = File(databases, "live-wal.db")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.enableWriteAheadLogging()
        Fixtures.create(db)
        val routes = routes(SqliteDb.framework("live") { db })
        // A read in progress on another thread holds a reader connection of the pool.
        val cursor = FrameworkSource { db }.open().query("SELECT * FROM notes", emptyArray(), CancellationSignal())
        cursor.moveToFirst()

        val writer = Thread { db.execSQL("INSERT INTO notes (id, title) VALUES (4, 'during a read')") }
        writer.start()
        writer.join(5_000)

        assertFalse("the app's write waited for the read", writer.isAlive)
        cursor.close()
        assertEquals(4, Fixtures.get(routes, "rows", "db" to "live", "table" to "notes").json().getJSONArray("items").length())
        db.close()
    }

    @Test
    fun `discovery lists the other database files, without the registered ones, sidecars, or other files`() {
        val library = Fixtures.file(databases, "library.db")
        Fixtures.file(databases, "other.db", wal = true)
        Fixtures.file(databases, "registered-file.db")
        // A sidecar is left out by its name, even with a database's header.
        File(databases, "gone.db-journal").writeBytes(library.readBytes())
        File(databases, "notes.txt").writeText("not a database")
        File(databases, "empty.db").createNewFile()
        val routes =
            SqliteRoutes(
                SqliteRegistry(
                    listOf(SqliteDb.framework("app") { live }, SqliteDb.file("named", File(databases, "registered-file.db"))),
                    databases,
                ),
            )

        val items = Fixtures.get(routes, "databases").json().getJSONArray("items")
        val names = (0 until items.length()).map { items.getJSONObject(it).getString("name") }

        // app.db is the live handle's file, and registered-file.db is registered as "named".
        assertEquals(listOf("app", "named", "library.db", "other.db"), names)
        val discovered = items.getJSONObject(2)
        assertFalse(discovered.getBoolean("registered"))
        assertFalse(discovered.getBoolean("writable"))
        assertEquals("file", discovered.getString("source"))
        assertEquals(library.path, discovered.getString("path"))
        assertEquals(7, discovered.getInt("schemaVersion"))
        assertEquals(3, Fixtures.get(routes, "rows", "db" to "other.db", "table" to "notes").json().getJSONArray("items").length())
        assertEquals(404, Fixtures.get(routes, "tables", "db" to "app.db").status)
        assertEquals(404, Fixtures.get(routes, "tables", "db" to "notes.txt").status)
    }

    @Test
    fun `a registration of the app's support handle reads as a framework one`() {
        val helper =
            androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
                androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(app)
                    .name("support.db")
                    .callback(
                        object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(3) {
                            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                                db.execSQL("CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT)")
                                db.execSQL("INSERT INTO items (name) VALUES ('one'), ('two')")
                            }

                            override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                        },
                    )
                    .build(),
            )
        val routes = routes(SqliteDb.support("support", writable = true) { helper.writableDatabase })

        val database = Fixtures.get(routes, "databases").json().getJSONArray("items").getJSONObject(0)
        assertEquals("support", database.getString("source"))
        assertEquals(3, database.getInt("schemaVersion"))
        val items = Fixtures.get(routes, "rows", "db" to "support", "table" to "items", "filter" to "tw").json().getJSONArray("items")
        assertEquals("two", items.getJSONObject(0).getJSONObject("cells").getJSONObject("name").getString("value"))
        helper.close()
    }

    private fun routes(vararg databases: SqliteDb): SqliteRoutes = SqliteRoutes(SqliteRegistry(databases.toList(), directory = null))

    private fun get(path: String, vararg query: Pair<String, String>) =
        Fixtures.get(routes, path, *(listOf("db" to "app", "table" to "notes").filter { (key) -> query.none { it.first == key } } + query).toTypedArray())

    private fun rows(vararg query: Pair<String, String>): JSONObject = get("rows", *query).also { assertEquals(it.json().toString(), 200, it.status) }.json()

    private fun rowids(page: JSONObject): List<String> = page.getJSONArray("items").let { items -> (0 until items.length()).map { items.getJSONObject(it).getString("rowid") } }

    private fun byName(items: JSONArray): Map<String, JSONObject> = (0 until items.length()).associate { items.getJSONObject(it).getString("name") to items.getJSONObject(it) }

    private fun count(table: String): Int = live.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }

    private fun insertNotes(from: Int, to: Int) {
        for (id in from..to) live.insert("notes", null, ContentValues().apply { put("id", id); put("title", "Note $id") })
    }

    private fun assertError(status: Int, field: String, response: io.github.twinsen81.lustro.DebugResponse) {
        val body = response.json()
        assertEquals(body.toString(), status, response.status)
        assertEquals(body.toString(), field, body.getString("field"))
    }

    private companion object {
        val HINTS = mapOf("notes" to TableHint("Note", "A note that the user wrote."))
    }
}
