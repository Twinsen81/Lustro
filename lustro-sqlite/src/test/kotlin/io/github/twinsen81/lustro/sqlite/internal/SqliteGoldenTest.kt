package io.github.twinsen81.lustro.sqlite.internal

import android.app.Application
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.sqlite.SqliteDb
import io.github.twinsen81.lustro.sqlite.TableHint
import io.github.twinsen81.lustro.sqlite.internal.Fixtures.text
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.SQLiteMode

/**
 * Pins the golden fixtures of the SQLite routes to what the routes write, so the
 * fixtures that clients validate against can't drift from the server. A file's
 * path and size depend on the device, so they are left out of the comparison.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SqliteGoldenTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val databases = File(app.dataDir, "databases")
    private val helper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(app)
                .name("app.db")
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(3) {
                        override fun onCreate(db: SupportSQLiteDatabase) = createApp(db)

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )

    @After
    fun tearDown() {
        helper.close()
    }

    @Test
    fun `the golden databases, tables, schema, rows, query, and exec fixtures are what the routes write`() {
        Fixtures.file(databases, "sdk_cache.db", notes = 0)
        val catalog = File(databases, "catalog.db")
        Fixtures.file(databases, catalog.name, notes = 0)
        val routes =
            SqliteRoutes(
                SqliteRegistry(
                    listOf(
                        SqliteDb.support("app", writable = true, tableHints = HINTS) { helper.writableDatabase },
                        SqliteDb.file("catalog", catalog),
                    ),
                    databases,
                ),
            )

        assertGolden(DATABASES, Fixtures.get(routes, "databases").text())
        assertGolden(TABLES, Fixtures.get(routes, "tables", "db" to "app").text())
        assertGolden(SCHEMA, Fixtures.get(routes, "schema", "db" to "app", "table" to "notes").text())
        assertGolden(ROWS, Fixtures.get(routes, "rows", "db" to "app", "table" to "notes", "limit" to "3", "dir" to "desc").text())
        assertGolden(QUERY, Fixtures.post(routes, "query", JSONObject().put("db", "app").put("sql", QUERY_SQL)).text())
        assertGolden(EXEC, Fixtures.post(routes, "exec", JSONObject().put("db", "app").put("sql", EXEC_SQL)).text())
    }

    private fun createApp(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        db.execSQL("INSERT INTO room_master_table VALUES (42, 'a1b2c3d4e5f6')")
        db.execSQL("CREATE TABLE folders (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL DEFAULT 'Inbox')")
        db.execSQL(
            "CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, body TEXT, attachment BLOB, " +
                "score REAL, created_at INTEGER NOT NULL, folder_id INTEGER REFERENCES folders(id) ON DELETE CASCADE)",
        )
        db.execSQL("CREATE TABLE note_tags (note_id INTEGER NOT NULL, tag TEXT NOT NULL, PRIMARY KEY (note_id, tag)) WITHOUT ROWID")
        db.execSQL("CREATE VIEW recent_notes AS SELECT id, title FROM notes ORDER BY created_at DESC")
        db.execSQL("INSERT INTO folders (name) VALUES ('Work')")
        db.execSQL("INSERT INTO notes (title, body, score, created_at, folder_id) VALUES ('Groceries', 'Milk, eggs', 4.5, 1792419740010, 1)")
        db.execSQL(
            "INSERT INTO notes (title, body, attachment, created_at, folder_id) VALUES ('Trip plan', ?, zeroblob(20480), 1792419750020, 1)",
            arrayOf(LONG_BODY),
        )
        // Any column holds any type: a blob in a TEXT column, and an id too large for a JavaScript number.
        db.execSQL("INSERT INTO notes (title, body, created_at) VALUES ('Imported', x'89504e47', 1792419760030)")
        db.execSQL("INSERT INTO notes (id, title, score, created_at) VALUES (9007199254740993, 'Synced', 0.25, 1792419770040)")
        db.execSQL("INSERT INTO note_tags VALUES (1, 'home'), (2, 'travel')")
    }

    private fun assertGolden(path: String, written: String) {
        assertEquals(
            "$path differs from:\n$written\n",
            canonical(JSONObject(File(path).readText())),
            canonical(JSONObject(written)),
        )
    }

    // Key order is not part of JSON, so objects compare as sorted maps, without the device's paths and sizes.
    private fun canonical(value: Any?): Any? =
        when (value) {
            is JSONObject -> value.keys().asSequence().filter { it !in DEVICE_KEYS }.associateWith { canonical(value.get(it)) }.toSortedMap()
            is JSONArray -> (0 until value.length()).map { canonical(value.get(it)) }
            else -> value
        }

    private companion object {
        val HINTS = mapOf("notes" to TableHint("NoteEntity", "A note that the user wrote, in a folder."), "folders" to TableHint("FolderEntity"))
        val LONG_BODY = "Day 1: " + "walk the old town. ".repeat(230)
        val DEVICE_KEYS = setOf("path", "sizeBytes")

        // Unit tests run in the module directory.
        const val DATABASES = "../wire-protocol/v1/golden/sqlite-databases.json"
        const val TABLES = "../wire-protocol/v1/golden/sqlite-tables.json"
        const val SCHEMA = "../wire-protocol/v1/golden/sqlite-schema.json"
        const val ROWS = "../wire-protocol/v1/golden/sqlite-rows.json"
        const val QUERY = "../wire-protocol/v1/golden/sqlite-query.json"
        const val EXEC = "../wire-protocol/v1/golden/sqlite-exec.json"

        const val QUERY_SQL = "SELECT id, title, attachment, score, length(body) AS body_chars FROM notes ORDER BY id DESC"
        const val EXEC_SQL = "UPDATE notes SET score = 5 WHERE folder_id = 1"
    }
}
