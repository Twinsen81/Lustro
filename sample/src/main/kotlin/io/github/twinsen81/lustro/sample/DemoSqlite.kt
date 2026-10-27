package io.github.twinsen81.lustro.sample

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File
import java.util.concurrent.Executor
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The sample's SQLite databases for the SQLite tab, and the app's own writes to
 * them. The debug bootstrap registers [NOTES] through the app's open handle, the
 * one that Room would give, and [CATALOG] as a file that the tab opens read-only;
 * [SDK_CACHE] stands for the database of a library, which the tab finds on disk.
 * They are plain SQLite calls, so this file needs no Lustro type.
 */
internal class DemoSqlite(context: Context, private val io: Executor) {
    private val app = context.applicationContext
    private val counts = MutableStateFlow<Pair<Long, Long>?>(null)

    /** How many notes and products the app reads now, or null before the first read. */
    val rowCounts: StateFlow<Pair<Long, Long>?> = counts.asStateFlow()

    fun refresh() {
        io.execute { counts.value = count(notes(app).writableDatabase, "notes") to withCatalog { count(it, "products") } }
    }

    fun addNote(done: (Long) -> Unit, failed: (Exception) -> Unit) =
        write(failed) {
            val id = insertNote(notes(app).writableDatabase, Random.nextInt(), System.currentTimeMillis())
            done(id)
        }

    /**
     * Writes a note and changes a product every [WRITE_INTERVAL_MS] for [WRITE_FOR_MS],
     * while the console reads. A read that blocked the app's writer would show as
     * a slow write.
     */
    fun writeForAWhile(done: (writes: Int, slowestMs: Long) -> Unit, failed: (Exception) -> Unit) =
        write(failed) {
            val notes = notes(app).writableDatabase
            val end = System.currentTimeMillis() + WRITE_FOR_MS
            var writes = 0
            var slowest = 0L
            withCatalog { catalog ->
                while (System.currentTimeMillis() < end) {
                    val started = System.nanoTime()
                    insertNote(notes, writes, System.currentTimeMillis())
                    catalog.execSQL("UPDATE products SET stock = stock + 1 WHERE rowid = ?", arrayOf<Any>(1 + writes % PRODUCTS))
                    slowest = maxOf(slowest, (System.nanoTime() - started) / NANOS_PER_MS)
                    writes++
                    Thread.sleep(WRITE_INTERVAL_MS)
                }
            }
            done(writes, slowest)
        }

    fun restock(done: (Int) -> Unit, failed: (Exception) -> Unit) =
        write(failed) {
            val changed = withCatalog { db -> db.compileStatement("UPDATE products SET stock = stock + 10 WHERE stock < 20").use { it.executeUpdateDelete() } }
            done(changed)
        }

    fun reset(done: () -> Unit, failed: (Exception) -> Unit) =
        write(failed) {
            val db = notes(app).writableDatabase
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM note_tags")
                db.execSQL("DELETE FROM notes")
                db.execSQL("DELETE FROM folders")
                db.execSQL("DELETE FROM sqlite_sequence")
                seedNotes(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            // In place, not by deleting the files: "Write for 10 s" can hold a connection to the
            // catalog now, and SQLite would delete the -wal of a new file when that connection closes.
            reseed(app.getDatabasePath(CATALOG_FILE), ::seedCatalog) { db ->
                db.execSQL("DELETE FROM products")
                insertProducts(db)
            }
            reseed(app.getDatabasePath(SDK_CACHE), ::seedSdkCache) { db ->
                db.execSQL("DELETE FROM events")
                insertEvents(db)
            }
            done()
        }

    private fun write(failed: (Exception) -> Unit, block: () -> Unit) {
        io.execute {
            try {
                block()
                refresh()
            } catch (e: android.database.SQLException) {
                failed(e)
            } catch (e: InterruptedException) {
                failed(e)
            }
        }
    }

    // The app's own connection to the catalog file, as a library would hold one, apart from the tab's.
    private fun <T> withCatalog(block: (SQLiteDatabase) -> T): T {
        val flags = SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
        val db = SQLiteDatabase.openDatabase(app.getDatabasePath(CATALOG_FILE).path, null, flags)
        return try {
            block(db)
        } finally {
            db.close()
        }
    }

    companion object {
        /** The registered name of the notes database, which the app has open. */
        const val NOTES: String = "notes"

        /** The registered name of the catalog, a file that the tab opens itself. */
        const val CATALOG: String = "catalog"
        const val NOTES_FILE: String = "sample_notes.db"
        const val CATALOG_FILE: String = "sample_catalog.db"

        /** A library's database, which the app does not register: the tab finds it on disk. */
        const val SDK_CACHE: String = "sample_sdk_cache.db"

        /** What each notes table holds, for the tab's hints: entity and purpose. */
        val NOTES_HINTS: Map<String, Pair<String, String>> =
            mapOf(
                "notes" to ("NoteEntity" to "A note that the user wrote, in a folder."),
                "folders" to ("FolderEntity" to "A folder of notes."),
                "note_tags" to ("NoteTagEntity" to "A tag on a note: one row for each pair."),
                "pinned_notes" to ("PinnedNote" to "The pinned notes, newest first."),
            )

        private const val SCHEMA_VERSION = 3
        private const val NOTE_COUNT = 2_000
        private const val PRODUCTS = 250
        private const val WRITE_FOR_MS = 10_000L
        private const val WRITE_INTERVAL_MS = 50L
        private const val NANOS_PER_MS = 1_000_000L
        private const val HOUR_MS = 3_600_000L
        private const val ATTACHMENT_EVERY = 25
        private const val LONG_BODY_EVERY = 100
        private const val BIG_ATTACHMENT_NOTE = 1_001
        private const val BIG_ATTACHMENT_BYTES = 3_000_000
        private val LONG_BODY = "The quick brown fox jumps over the lazy dog. ".repeat(120)

        @Volatile
        private var helper: SupportSQLiteOpenHelper? = null

        /**
         * The app's open helper of the notes database, one for the process: the
         * framework one that Room uses, in WAL mode, as Room opens it. The first
         * open writes the demo notes.
         */
        fun notes(context: Context): SupportSQLiteOpenHelper =
            helper ?: synchronized(this) {
                helper ?: FrameworkSQLiteOpenHelperFactory()
                    .create(
                        SupportSQLiteOpenHelper.Configuration.builder(context.applicationContext)
                            .name(NOTES_FILE)
                            .callback(
                                object : SupportSQLiteOpenHelper.Callback(SCHEMA_VERSION) {
                                    override fun onCreate(db: SupportSQLiteDatabase) {
                                        createNotes(db)
                                        seedNotes(db)
                                    }

                                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                                },
                            )
                            .build(),
                    )
                    .also {
                        it.setWriteAheadLoggingEnabled(true)
                        helper = it
                    }
            }

        /**
         * Writes the catalog and the library's database on the first start, so that
         * they are on disk before the console lists them.
         */
        fun seedFiles(context: Context) {
            val catalog = context.getDatabasePath(CATALOG_FILE)
            if (!catalog.exists()) createFile(catalog) { db -> seedCatalog(db) }
            val sdk = context.getDatabasePath(SDK_CACHE)
            if (!sdk.exists()) createFile(sdk) { db -> seedSdkCache(db) }
        }

        private fun createFile(file: File, seed: (SQLiteDatabase) -> Unit) {
            val db = SQLiteDatabase.openOrCreateDatabase(file, null)
            try {
                db.enableWriteAheadLogging()
                inTransaction(db, seed)
            } finally {
                db.close()
            }
        }

        // Writes the demo rows again in an existing file, or creates the file when it is not there.
        private fun reseed(file: File, create: (SQLiteDatabase) -> Unit, rows: (SQLiteDatabase) -> Unit) {
            if (!file.exists()) return createFile(file, create)
            val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING)
            try {
                inTransaction(db, rows)
            } finally {
                db.close()
            }
        }

        private fun inTransaction(db: SQLiteDatabase, block: (SQLiteDatabase) -> Unit) {
            db.beginTransaction()
            try {
                block(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        private fun createNotes(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE folders (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)")
            db.execSQL(
                "CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT, folder_id INTEGER REFERENCES folders(id) ON DELETE CASCADE, " +
                    "title TEXT NOT NULL, body TEXT, attachment BLOB, pinned INTEGER NOT NULL DEFAULT 0, score REAL, created_at INTEGER NOT NULL)",
            )
            db.execSQL("CREATE INDEX notes_folder ON notes (folder_id)")
            db.execSQL("CREATE TABLE note_tags (note_id INTEGER NOT NULL, tag TEXT NOT NULL, PRIMARY KEY (note_id, tag)) WITHOUT ROWID")
            db.execSQL("CREATE VIEW pinned_notes AS SELECT id, title, created_at FROM notes WHERE pinned = 1 ORDER BY created_at DESC")
        }

        private fun seedNotes(db: SupportSQLiteDatabase) {
            for (name in listOf("Inbox", "Work", "Travel")) db.execSQL("INSERT INTO folders (name) VALUES (?)", arrayOf(name))
            val start = System.currentTimeMillis() - NOTE_COUNT * HOUR_MS
            for (index in 1..NOTE_COUNT) {
                val values =
                    ContentValues().apply {
                        put("folder_id", 1 + index % 3)
                        put("title", "Note $index")
                        // Every hundredth note has a body longer than a page of rows keeps.
                        put("body", if (index % LONG_BODY_EVERY == 0) "Long note $index. " + LONG_BODY else "Body of note $index")
                        // One attachment is larger than a CursorWindow: the tab reads only its size.
                        if (index == BIG_ATTACHMENT_NOTE) put("attachment", ByteArray(BIG_ATTACHMENT_BYTES))
                        if (index % ATTACHMENT_EVERY == 0) put("attachment", ByteArray(index * 16))
                        put("pinned", if (index % 7 == 0) 1 else 0)
                        put("score", index % 50 / 10.0)
                        put("created_at", start + index * HOUR_MS)
                    }
                db.insert("notes", SQLiteDatabase.CONFLICT_ABORT, values)
            }
            // SQLite stores any type in any column: an imported note has a blob in its TEXT body.
            db.execSQL("INSERT INTO notes (folder_id, title, body, created_at) VALUES (1, 'Imported note', x'89504e470d0a1a0a', ?)", arrayOf(start))
            db.execSQL("INSERT INTO note_tags VALUES (1, 'home'), (1, 'todo'), (2, 'work'), (3, 'travel')")
        }

        private fun insertNote(db: SupportSQLiteDatabase, seed: Int, now: Long): Long =
            db.insert(
                "notes",
                SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("folder_id", 1)
                    put("title", "Added by the app ${Integer.toHexString(seed)}")
                    put("body", "Written at $now")
                    put("created_at", now)
                },
            )

        private fun seedCatalog(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE products (sku TEXT NOT NULL UNIQUE, name TEXT NOT NULL, price REAL NOT NULL, stock INTEGER NOT NULL)")
            insertProducts(db)
            db.version = 1
        }

        private fun insertProducts(db: SQLiteDatabase) {
            for (index in 1..PRODUCTS) {
                db.execSQL(
                    "INSERT INTO products (sku, name, price, stock) VALUES (?, ?, ?, ?)",
                    arrayOf<Any>("SKU-%04d".format(index), "Product $index", index * 1.25, index % 40),
                )
            }
        }

        private fun seedSdkCache(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY, name TEXT NOT NULL, payload TEXT, sent INTEGER NOT NULL DEFAULT 0)")
            insertEvents(db)
            db.version = 12
        }

        private fun insertEvents(db: SQLiteDatabase) {
            for (index in 1..30) {
                db.execSQL("INSERT INTO events (name, payload) VALUES (?, ?)", arrayOf<Any>("screen_view", "{\"screen\":\"home\",\"n\":$index}"))
            }
        }

        private fun count(db: SupportSQLiteDatabase, table: String): Long =
            db.query("SELECT COUNT(*) FROM $table").use { if (it.moveToFirst()) it.getLong(0) else 0L }

        private fun count(db: SQLiteDatabase, table: String): Long =
            db.rawQuery("SELECT COUNT(*) FROM $table", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
    }
}
