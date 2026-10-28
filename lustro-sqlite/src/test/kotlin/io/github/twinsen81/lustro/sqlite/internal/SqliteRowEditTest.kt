package io.github.twinsen81.lustro.sqlite.internal

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.sqlite.SqliteDb
import io.github.twinsen81.lustro.sqlite.internal.Fixtures.json
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.SQLiteMode

/** POST edit and POST delete: one row of a writable database, by its rowid or its primary key. */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SqliteRowEditTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var live: SQLiteDatabase
    private lateinit var routes: SqliteRoutes

    @Before
    fun setUp() {
        live = SQLiteDatabase.openOrCreateDatabase(File(app.dataDir, "databases/app.db").apply { parentFile?.mkdirs() }, null)
        Fixtures.create(live)
        live.execSQL("CREATE TABLE pairs (a TEXT, b TEXT, PRIMARY KEY (a, b))")
        // A table with a rowid allows NULL in its primary key, so two rows can have the same key.
        live.execSQL("INSERT INTO pairs VALUES (NULL, 'x'), (NULL, 'x'), ('one', 'y')")
        live.execSQL("CREATE TABLE computed (id INTEGER PRIMARY KEY, a INTEGER, twice INTEGER GENERATED ALWAYS AS (a * 2))")
        live.execSQL("INSERT INTO computed (id, a) VALUES (1, 2)")
        routes = routes(SqliteDb.framework("app", writable = true) { live })
    }

    @After
    fun tearDown() {
        live.close()
    }

    @Test
    fun `edit sets the values of the row that its rowid names, by their JSON types and the columns' affinity`() {
        ok(edit(JSONObject().put("rowid", "2").put("values", JSONObject().put("title", "Edited").put("score", 2.25).put("body", JSONObject.NULL).put("folder_id", "1"))))
        ok(edit(JSONObject().put("rowid", 3).put("values", JSONObject().put("id", "30").put("mixed", "30"))))

        assertEquals(listOf("Edited", null, "2.25", "integer"), row("SELECT title, body, score, typeof(folder_id) FROM notes WHERE id = 2"))
        // A text in an INTEGER column is stored as the number; in a column without a type, as the text.
        assertEquals(listOf("integer", "text"), row("SELECT typeof(id), typeof(mixed) FROM notes WHERE id = 30"))
        assertEquals(listOf("Note 1"), row("SELECT title FROM notes WHERE id = 1"))
    }

    @Test
    fun `a table without rowid and a table with a key take the key's columns`() {
        ok(edit(JSONObject().put("table", "tags").put("key", JSONObject().put("note_id", 1).put("name", "work")).put("values", JSONObject().put("color", "blue"))))
        ok(edit(JSONObject().put("key", JSONObject().put("ID", "1")).put("values", JSONObject().put("TITLE", "By key"))))

        assertEquals(listOf("blue"), row("SELECT color FROM tags WHERE note_id = 1"))
        assertEquals(listOf("By key"), row("SELECT title FROM notes WHERE id = 1"))
    }

    @Test
    fun `delete removes the one row that its rowid or its key names`() {
        ok(delete(JSONObject().put("rowid", "1")))
        ok(delete(JSONObject().put("table", "tags").put("key", JSONObject().put("note_id", "1").put("name", "work"))))
        ok(delete(JSONObject().put("table", "pairs").put("key", JSONObject().put("a", "one").put("b", "y"))))

        assertEquals(listOf("2"), row("SELECT COUNT(*) FROM notes"))
        assertEquals(listOf("0"), row("SELECT COUNT(*) FROM tags"))
        assertEquals(listOf("2"), row("SELECT COUNT(*) FROM pairs"))
    }

    @Test
    fun `a key that no row has is a 404, and one that two rows have a 400, and neither changes a row`() {
        assertField(404, "rowid", delete(JSONObject().put("rowid", "99")))
        assertField(404, "key", edit(JSONObject().put("table", "tags").put("key", JSONObject().put("note_id", 2).put("name", "work")).put("values", JSONObject().put("color", "x"))))
        assertField(400, "key", delete(JSONObject().put("table", "pairs").put("key", JSONObject().put("a", JSONObject.NULL).put("b", "x"))))
        assertField(400, "key", edit(JSONObject().put("table", "pairs").put("key", JSONObject().put("a", JSONObject.NULL).put("b", "x")).put("values", JSONObject().put("b", "z"))))

        assertEquals(listOf("3"), row("SELECT COUNT(*) FROM notes"))
        assertEquals(listOf("2"), row("SELECT COUNT(*) FROM pairs WHERE b = 'x'"))
        assertFalse(live.inTransaction())
    }

    @Test
    fun `a JSON number past 2^53 binds as that integer, in a key and in a value, so a column without affinity keeps its class`() {
        live.execSQL("CREATE TABLE big_keys (k, v, n, PRIMARY KEY (k)) WITHOUT ROWID")
        live.execSQL("INSERT INTO big_keys VALUES (9007199254740993, 'integer', 0), ('9007199254740993', 'text', 0), (9007199254740995, 'second', 0)")

        // The bodies as the console writes them: the digits as a JSON number, and a whole real with its point.
        ok(post("edit", """{"db":"app","table":"big_keys","key":{"k":9007199254740993},"values":{"v":"edited","n":9007199254740997}}"""))
        ok(post("edit", """{"db":"app","table":"big_keys","key":{"k":"9007199254740993"},"values":{"n":3.0}}"""))
        ok(post("delete", """{"db":"app","table":"big_keys","key":{"k":9007199254740995}}"""))

        assertEquals(listOf("edited", "integer", "9007199254740997"), row("SELECT v, typeof(n), n FROM big_keys WHERE k = 9007199254740993"))
        assertEquals(listOf("text", "real"), row("SELECT v, typeof(n) FROM big_keys WHERE k = '9007199254740993'"))
        assertEquals(listOf("2"), row("SELECT COUNT(*) FROM big_keys"))
    }

    @Test
    fun `a row is named by rowid or by the whole primary key, and the values by the table's columns`() {
        assertField(400, "rowid", delete(JSONObject()))
        assertField(400, "rowid", delete(JSONObject().put("rowid", "1").put("key", JSONObject().put("id", 1))))
        assertField(400, "rowid", delete(JSONObject().put("rowid", "one")))
        assertField(400, "rowid", delete(JSONObject().put("rowid", 1.5)))
        assertField(400, "rowid", delete(JSONObject().put("table", "tags").put("rowid", "1")))
        assertField(400, "key", delete(JSONObject().put("table", "tags").put("key", JSONObject().put("note_id", 1))))
        assertField(400, "key", delete(JSONObject().put("key", JSONObject().put("id", 1).put("title", "Note 1"))))
        assertField(400, "key", delete(JSONObject().put("table", "shadowed").put("key", JSONObject().put("value", "v"))))
        assertField(400, "key", delete(JSONObject().put("key", "1")))
        assertField(400, "values", edit(JSONObject().put("rowid", "1")))
        assertField(400, "values", edit(JSONObject().put("rowid", "1").put("values", JSONObject())))
        assertField(400, "values", edit(JSONObject().put("rowid", "1").put("values", JSONObject().put("missing", 1))))
        assertField(400, "values", edit(JSONObject().put("rowid", "1").put("values", JSONObject().put("title", true))))
        assertField(400, "values", edit(JSONObject().put("table", "computed").put("rowid", "1").put("values", JSONObject().put("twice", 5))))
        assertField(400, "table", edit(JSONObject().put("table", "recent_notes").put("rowid", "1").put("values", JSONObject().put("title", "x"))))
        assertField(404, "table", delete(JSONObject().put("table", "missing").put("rowid", "1")))
        assertField(400, "table", delete(JSONObject().put("table", "")))

        assertEquals(listOf("Note 1"), row("SELECT title FROM notes WHERE id = 1"))
    }

    @Test
    fun `a value that the table's constraints refuse is a 400 with SQLite's message`() {
        val response = edit(JSONObject().put("table", "folders").put("rowid", "1").put("values", JSONObject().put("name", JSONObject.NULL)))

        assertField(400, "values", response)
        assertEquals("sqlite_error", response.json().getString("code"))
        assertEquals(listOf("Work"), row("SELECT name FROM folders"))
    }

    @Test
    fun `edit and delete on a database that is not writable are a 403`() {
        val catalog = Fixtures.file(app.cacheDir, "catalog.db")
        val routes = routes(SqliteDb.framework("locked") { live }, SqliteDb.file("catalog", catalog))

        for (db in listOf("locked", "catalog")) {
            assertField(403, "db", Fixtures.post(routes, "edit", JSONObject().put("db", db).put("table", "notes").put("rowid", "1").put("values", JSONObject().put("title", "x"))))
            assertField(403, "db", Fixtures.post(routes, "delete", JSONObject().put("db", db).put("table", "notes").put("rowid", "1")))
        }
        assertEquals(listOf("3"), row("SELECT COUNT(*) FROM notes"))
    }

    @Test
    fun `a cancelled edit changes nothing`() {
        val request = Fixtures.request("delete", JSONObject().put("db", "app").put("table", "notes").put("rowid", "1"))
        request.cancel()

        assertEquals(504, routes.handle(request)!!.status)
        assertEquals(listOf("3"), row("SELECT COUNT(*) FROM notes"))
    }

    private fun edit(body: JSONObject): DebugResponse = Fixtures.post(routes, "edit", defaults(body))

    private fun post(path: String, body: String): DebugResponse = routes.handle(DebugRequest(path, "POST", body = body.toByteArray()))!!

    private fun delete(body: JSONObject): DebugResponse = Fixtures.post(routes, "delete", defaults(body))

    private fun defaults(body: JSONObject): JSONObject = body.apply { if (!has("db")) put("db", "app"); if (!has("table")) put("table", "notes") }

    private fun ok(response: DebugResponse) {
        assertEquals(response.json().toString(), 200, response.status)
        assertEquals("ok", response.json().getString("status"))
    }

    private fun row(sql: String): List<String?> =
        live.rawQuery(sql, null).use { cursor -> if (cursor.moveToFirst()) List(cursor.columnCount) { cursor.getString(it) } else emptyList() }

    private fun routes(vararg databases: SqliteDb): SqliteRoutes = SqliteRoutes(SqliteRegistry(databases.toList(), directory = null))

    private fun assertField(status: Int, field: String, response: DebugResponse) {
        val body = response.json()
        assertEquals(body.toString(), status, response.status)
        assertEquals(body.toString(), field, body.getString("field"))
    }
}
