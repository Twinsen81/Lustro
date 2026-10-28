package io.github.twinsen81.lustro.sqlite.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SqliteStatementsTest {
    @Test
    fun `transaction control, ATTACH, DETACH, and VACUUM are refused in query and in exec`() {
        val refused =
            listOf(
                "BEGIN", "begin immediate", "BEGIN TRANSACTION", "COMMIT", "END", "end transaction", "ROLLBACK", "ROLLBACK TO sp",
                "SAVEPOINT sp", "RELEASE sp", "RELEASE SAVEPOINT sp", "ATTACH DATABASE 'x.db' AS x", "DETACH x", "VACUUM", "VACUUM INTO '/tmp/copy.db'",
            )
        for (sql in refused) {
            assertNotNull(sql, SqliteStatements.refusal(sql, exec = false))
            assertNotNull(sql, SqliteStatements.refusal(sql, exec = true))
        }
    }

    @Test
    fun `the keyword is found past white space, comments, empty statements, and EXPLAIN`() {
        val disguised =
            listOf(
                "  \t\n\u000B\u000C\r BEGIN",
                ";BEGIN",
                " ; ;\n; savepoint s",
                "-- a comment\nBEGIN",
                "-- one\n-- two\r\nBEGIN",
                "/* a comment */BEGIN",
                "/* one */ /* two\n */ ; -- three\n BEGIN",
                "/**/BEGIN",
                "EXPLAIN BEGIN",
                "explain query plan /* x */ SAVEPOINT s",
                "EXPLAIN\n-- x\nQUERY PLAN ROLLBACK",
            )
        for (sql in disguised) assertNotNull(sql, SqliteStatements.refusal(sql, exec = false))
    }

    @Test
    fun `exec also refuses every PRAGMA, also one that EXPLAIN compiles, and query reads one`() {
        for (sql in listOf("PRAGMA foreign_keys = OFF", "pragma query_only(1)", "PRAGMA user_version", "/* x */ PRAGMA journal_mode", "EXPLAIN PRAGMA query_only = 1")) {
            assertNotNull(sql, SqliteStatements.refusal(sql, exec = true))
            assertNull(sql, SqliteStatements.refusal(sql, exec = false))
        }
    }

    @Test
    fun `a byte order mark, which SQLite takes as white space, does not hide a keyword`() {
        for (sql in listOf("\uFEFFBEGIN", "BEGIN\uFEFF", "\uFEFF\uFEFF;\uFEFFSAVEPOINT s", "EXPLAIN\uFEFFBEGIN", "/* x */\uFEFFATTACH 'x.db' AS x")) {
            assertNotNull(sql, SqliteStatements.refusal(sql, exec = false))
        }
        assertNotNull(SqliteStatements.refusal("\uFEFFPRAGMA query_only = ON", exec = true))
        assertFalse(SqliteStatements.hasStatement("\uFEFF ;\uFEFF"))
    }

    @Test
    fun `query refuses the pragmas that set a value for every connection of the process, by any form of their name`() {
        val global =
            listOf(
                "PRAGMA hard_heap_limit = 1", "pragma HARD_HEAP_LIMIT(1)", "PRAGMA main.hard_heap_limit = 1", "PRAGMA main . \"hard_heap_limit\" = 1",
                "PRAGMA [soft_heap_limit] = 1", "PRAGMA `soft_heap_limit`(1)", "PRAGMA 'temp_store_directory' = '/x'", "PRAGMA /* x */ data_store_directory = '/x'",
                "EXPLAIN PRAGMA hard_heap_limit = 1", "\uFEFFPRAGMA\uFEFFhard_heap_limit = 1", "PRAGMA hard_heap_limit",
            )
        for (sql in global) assertNotNull(sql, SqliteStatements.refusal(sql, exec = false))
        // A name that the tab can't read is refused; SQLite would refuse it too.
        assertNotNull(SqliteStatements.refusal("PRAGMA (1)", exec = false))
        assertNotNull(SqliteStatements.refusal("PRAGMA \"open", exec = false))
        for (sql in listOf("PRAGMA user_version", "PRAGMA table_info(notes)", "PRAGMA main.table_info('notes')", "PRAGMA \"index_list\"(notes)", "PRAGMA hard_heap_limit_x")) {
            assertNull(sql, SqliteStatements.refusal(sql, exec = false))
        }
    }

    @Test
    fun `the other statements go to SQLite, also those that only look like a refused one`() {
        val allowed =
            listOf(
                "SELECT 1", "WITH x AS (SELECT 1) DELETE FROM t", "DELETE FROM t", "INSERT INTO t VALUES (1)", "CREATE TABLE t (a)",
                "EXPLAIN SELECT 1", "EXPLAIN QUERY PLAN SELECT 1", "BEGINNING", "BEGIN_X", "BEGIN\$x", "BEGIN1", "\"BEGIN\"", "[BEGIN]", "`BEGIN`",
                "SELECT 'BEGIN'", "SELECT 1; BEGIN", "VACUUMS", "PRAGMATIC", "-BEGIN", "(BEGIN)",
            )
        for (sql in allowed) assertNull(sql, SqliteStatements.refusal(sql, exec = true))
    }

    @Test
    fun `a text of white space, comments, and semicolons has no statement`() {
        for (sql in listOf("", "   ", ";", " ; ; ", "-- only a comment", "/* open comment", "/* a */ -- b\n ;")) {
            assertFalse(sql, SqliteStatements.hasStatement(sql))
        }
        assertTrue(SqliteStatements.hasStatement("/* a */ SELECT 1"))
        assertTrue(SqliteStatements.hasStatement("- 1"))
    }

    @Test
    fun `a refusal says what is refused`() {
        assertEquals(true, SqliteStatements.refusal("/* */ savepoint s", exec = false)?.startsWith("SAVEPOINT"))
    }
}
