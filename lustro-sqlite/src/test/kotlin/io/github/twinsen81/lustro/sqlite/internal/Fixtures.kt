package io.github.twinsen81.lustro.sqlite.internal

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import java.io.File
import org.json.JSONObject

/** The databases that the route tests read. */
internal object Fixtures {
    /** A text one character longer than a page of rows keeps. */
    val LONG_TEXT: String = "x".repeat(SqliteReader.MAX_TEXT_CHARS) + "y"

    /** A name that ends a quoted identifier and starts another statement, if the tab put it in SQL as it is. */
    const val TRICKY_TABLE: String = "we\"ird; DROP TABLE notes; --"

    /**
     * Writes the demo schema into [db]: folders and notes with a foreign key, a
     * table WITHOUT ROWID with a composite key, a view, a full-text index with its
     * shadow tables, a table with a column named rowid, and [TRICKY_TABLE].
     */
    fun create(db: SQLiteDatabase, notes: Int = 3) {
        db.execSQL("CREATE TABLE folders (id INTEGER PRIMARY KEY, name TEXT NOT NULL DEFAULT 'Inbox')")
        db.execSQL(
            "CREATE TABLE notes (id INTEGER PRIMARY KEY, title TEXT, body TEXT, data BLOB, mixed, score REAL, " +
                "folder_id INTEGER REFERENCES folders(id) ON DELETE CASCADE)",
        )
        db.execSQL("CREATE TABLE tags (note_id INTEGER, name TEXT, color TEXT, PRIMARY KEY (note_id, name)) WITHOUT ROWID")
        db.execSQL(
            "CREATE TABLE note_links (a INTEGER, b TEXT, FOREIGN KEY (a, b) REFERENCES tags(note_id, name), FOREIGN KEY (a) REFERENCES notes)",
        )
        db.execSQL("CREATE TABLE shadowed (rowid TEXT, value TEXT)")
        db.execSQL("CREATE TABLE \"${TRICKY_TABLE.replace("\"", "\"\"")}\" (v TEXT)")
        db.execSQL("CREATE VIEW recent_notes AS SELECT id, title FROM notes ORDER BY id DESC")
        db.execSQL("CREATE VIRTUAL TABLE notes_fts USING fts4(title, body)")
        db.execSQL("PRAGMA user_version = 7")
        db.insert("folders", null, ContentValues().apply { put("id", 1); put("name", "Work") })
        repeat(notes) { index ->
            val id = index + 1
            db.insert(
                "notes",
                null,
                ContentValues().apply {
                    put("id", id)
                    put("title", "Note $id")
                    put("body", "Body of note $id")
                    put("score", id * 1.5)
                    put("folder_id", 1)
                },
            )
        }
        db.insert("tags", null, ContentValues().apply { put("note_id", 1); put("name", "work"); put("color", "red") })
        db.insert("shadowed", null, ContentValues().apply { put("rowid", "not the rowid"); put("value", "v") })
        db.execSQL("INSERT INTO \"${TRICKY_TABLE.replace("\"", "\"\"")}\" (v) VALUES ('safe')")
    }

    /** A new database file in [dir], with [create]'s schema, closed. */
    fun file(dir: File, name: String, notes: Int = 3, wal: Boolean = false): File {
        val file = File(dir, name)
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        if (wal) db.enableWriteAheadLogging()
        create(db, notes)
        db.close()
        return file
    }

    fun get(routes: SqliteRoutes, path: String, vararg query: Pair<String, String>): DebugResponse =
        routes.handle(DebugRequest(path, "GET", query.associate { it.first to listOf(it.second) }))!!

    fun post(routes: SqliteRoutes, path: String, body: JSONObject): DebugResponse = routes.handle(request(path, body))!!

    fun request(path: String, body: JSONObject): DebugRequest = DebugRequest(path, "POST", body = body.toString().toByteArray())

    fun DebugResponse.json(): JSONObject = JSONObject(text())

    fun DebugResponse.text(): String = body.toString(Charsets.UTF_8)
}
