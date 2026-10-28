package io.github.twinsen81.lustro.sqlite.internal

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import android.os.OperationCanceledException
import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.sqlite.SqliteDb
import io.github.twinsen81.lustro.sqlite.internal.Fixtures.json
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.SQLiteMode

/**
 * The limit on a statement that holds a lock on a connection of the tab's own, out of WAL mode:
 * there the app's writer waits for it, and Android's SQLite fails the write after 2.5 s.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ReadLimitTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val databases = File(app.dataDir, "databases")

    @Test
    fun `the limit stops one statement, and the next statement of the request runs`() {
        val limit = ReadLimit(limitMs = 100)
        val request = CancellationSignal()

        val stopped = assertThrows(RouteException::class.java) { limit.statement(request, field = "sql") { waitForCancel(it) } }

        assertEquals(504, stopped.status)
        assertEquals("read_limit", stopped.code)
        assertEquals("sql", stopped.field)
        assertFalse(request.isCanceled)
        assertEquals("ran", limit.statement(request, field = null) { "ran" })
    }

    @Test
    fun `a cancelled request stops its statement, before or while it runs, and is not the limit`() {
        val limit = ReadLimit(limitMs = 60_000)
        val request = CancellationSignal()
        val running = Executors.newSingleThreadExecutor()
        val statement = running.submit<Unit> { limit.statement(request, field = null) { waitForCancel(it) } }
        Thread.sleep(100)

        request.cancel()

        val thrown = runCatching { statement.get(5, TimeUnit.SECONDS) }.exceptionOrNull()?.cause
        assertTrue("$thrown", thrown is OperationCanceledException)
        assertThrows(OperationCanceledException::class.java) { limit.statement(request, field = null) { waitForCancel(it) } }
        running.shutdown()
    }

    @Test
    fun `the limit applies to a file that the tab opens out of WAL mode, not to a WAL file or the app's handle`() {
        val rollback = Fixtures.file(databases, "rollback.db")
        val wal = Fixtures.file(databases, "wal.db", wal = true)
        val live = SQLiteDatabase.openDatabase(rollback.path, null, SQLiteDatabase.OPEN_READWRITE)
        val limit = ReadLimit()

        assertTrue(FileSource(rollback).open().use { limit.applies(it, CancellationSignal()) })
        assertFalse(FileSource(wal).open().use { limit.applies(it, CancellationSignal()) })
        assertFalse(FrameworkSource { live }.open().use { limit.applies(it, CancellationSignal()) })
        live.close()
    }

    @Test
    fun `a page of a file out of WAL mode stops at the limit, before a write of the app gives up on the lock`() {
        val file = Fixtures.file(databases, "files.db")
        val app = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        app.execSQL("CREATE VIEW endless AS WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM n) SELECT count(*) AS c FROM notes, n")
        val routes = SqliteRoutes(SqliteRegistry(listOf(SqliteDb.file("files", file)), directory = null))
        val request = io.github.twinsen81.lustro.DebugRequest("rows", "GET", mapOf("db" to listOf("files"), "table" to listOf("endless")))
        val running = Executors.newSingleThreadExecutor()
        val page = running.submit<DebugResponse> { routes.handle(request) }
        try {
            Thread.sleep(300)
            assertFalse(page.isDone)

            val started = System.nanoTime()
            app.execSQL("INSERT INTO notes (id, title) VALUES (4, 'after the limit')")
            val waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

            assertTrue("the write waited $waitedMs ms", waitedMs in 1_000 until 2_500)
            val response = page.get(5, TimeUnit.SECONDS)
            assertEquals(504, response.status)
            assertEquals("read_limit", response.json().getString("code"))
        } finally {
            request.cancel()
        }
        // The other routes read the file as before.
        assertEquals(200, Fixtures.get(routes, "tables", "db" to "files").status)
        assertEquals(4, Fixtures.get(routes, "rows", "db" to "files", "table" to "notes").json().getJSONArray("items").length())
        running.shutdown()
        app.close()
    }

    @Test
    fun `a count that the limit stops leaves the table without a count, and the other tables are listed`() {
        val file = Fixtures.file(databases, "slow.db")
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            // A full-text table whose content is an endless view: a count of it never ends.
            db.execSQL("CREATE VIEW endless AS WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM n) SELECT x AS rowid, 'a' AS body FROM n")
            db.execSQL("CREATE VIRTUAL TABLE big USING fts4(content=\"endless\", body)")
        }
        val routes = SqliteRoutes(SqliteRegistry(listOf(SqliteDb.file("slow", file)), directory = null), ReadLimit(limitMs = 300))

        val response = Fixtures.get(routes, "tables", "db" to "slow")

        assertEquals(200, response.status)
        val items = response.json().getJSONArray("items")
        val tables = (0 until items.length()).associate { items.getJSONObject(it).getString("name") to items.getJSONObject(it) }
        assertFalse(tables.getValue("big").has("rowCount"))
        assertTrue(tables.getValue("big").getString("error").contains("300 ms"))
        assertEquals(3, tables.getValue("notes").getInt("rowCount"))
    }

    @Test
    fun `a read through the app's handle has no limit but the request's timeout`() {
        val file = Fixtures.file(databases, "live.db")
        val live = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        live.execSQL("CREATE VIEW endless AS WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM n) SELECT count(*) AS c FROM notes, n")
        val routes = SqliteRoutes(SqliteRegistry(listOf(SqliteDb.framework("live") { live }), directory = null), ReadLimit(limitMs = 1))
        val request = io.github.twinsen81.lustro.DebugRequest("rows", "GET", mapOf("db" to listOf("live"), "table" to listOf("endless")))
        val running = Executors.newSingleThreadExecutor()
        val page = running.submit<DebugResponse> { routes.handle(request) }

        Thread.sleep(300)
        assertFalse(page.isDone)
        request.cancel()

        val response = page.get(5, TimeUnit.SECONDS)
        assertEquals(504, response.status)
        assertFalse(response.json().has("code"))
        running.shutdown()
        live.close()
    }

    private fun waitForCancel(signal: CancellationSignal) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!signal.isCanceled && System.currentTimeMillis() < deadline) Thread.sleep(5)
        signal.throwIfCanceled()
    }
}
