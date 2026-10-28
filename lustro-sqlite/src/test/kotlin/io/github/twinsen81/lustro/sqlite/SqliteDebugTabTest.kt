package io.github.twinsen81.lustro.sqlite

import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SqliteDebugTabTest {
    private val unused: () -> SQLiteDatabase = { error("The tab must not open a database when it is created") }

    @Test
    fun `two databases with the same name are refused`() {
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                SqliteDebugTab.create(listOf(SqliteDb.framework("app", database = unused), SqliteDb.file("app", File("/data/app.db"))))
            }
        assertTrue(failure.message!!.contains("\"app\""))
    }

    @Test
    fun `two file databases with the same file are refused, also when the paths are written differently`() {
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                SqliteDebugTab.create(listOf(SqliteDb.file("a", File("/data/db/cache.db")), SqliteDb.file("b", File("/data/db/../db/./cache.db"))))
            }
        assertTrue(failure.message!!.contains("/data/db/cache.db"))
    }

    @Test
    fun `a relative file and a blank name are refused`() {
        assertThrows(IllegalArgumentException::class.java) { SqliteDb.file("cache", File("cache.db")) }
        assertThrows(IllegalArgumentException::class.java) { SqliteDb.framework(" ", database = unused) }
    }

    @Test
    fun `a registration keeps its flags and a copy of its hints, and a file is never writable`() {
        val hints = mutableMapOf("notes" to TableHint("Note", "A note."))
        val live = SqliteDb.framework("app", writable = true, tableHints = hints, database = unused)
        hints.clear()

        assertTrue(live.writable)
        assertEquals("Note", live.tableHints.getValue("notes").entity)
        assertFalse(SqliteDb.file("cache", File("/data/cache.db")).writable)
        assertFalse(SqliteDb.framework("other", database = unused).writable)
    }

    @Test
    fun `the tab is in the tab bar, under the id sqlite, with its view, and creating it opens nothing`() {
        val tab = SqliteDebugTab.create(listOf(SqliteDb.framework("app", database = unused), SqliteDb.file("cache", File("/data/cache.db"))))

        assertEquals("sqlite", tab.id)
        assertTrue(tab.showInTabBar)
        assertTrue(tab.renderContent().contains("id=\"sqlite-root\""))
    }
}
