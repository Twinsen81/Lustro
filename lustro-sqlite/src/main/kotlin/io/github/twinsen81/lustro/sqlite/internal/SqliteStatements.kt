package io.github.twinsen81.lustro.sqlite.internal

import java.util.Locale

/**
 * The statements that `POST query` and `POST exec` refuse before they run.
 *
 * This is not a read and write classifier: the read-only connection of `query`
 * is what refuses a write. The check finds the first keyword of the statement
 * that SQLite would run, past white space, comments, empty statements, and
 * `EXPLAIN [QUERY PLAN]`, as SQLite's own tokenizer does, and refuses the
 * statements that leave state behind on a connection or in the process, or
 * write a file.
 */
internal object SqliteStatements {
    /** Whether [sql] has a statement, and not only white space, comments, and `;`. */
    fun hasStatement(sql: String): Boolean = skipped(sql) < sql.length

    /** Why [sql] can't run in `POST query` (with [exec] false) or `POST exec`, or `null` when it can. */
    fun refusal(sql: String, exec: Boolean): String? {
        val word = words(sql).withoutExplain().firstOrNull() ?: return null
        val keyword = word.text
        return when {
            keyword in TRANSACTION -> "$keyword is refused: a transaction that a request starts would stay open on the connection after it"
            keyword == "ATTACH" || keyword == "DETACH" -> "$keyword is refused: it changes the databases of the connection, and ATTACH can create a file"
            keyword == "VACUUM" -> "VACUUM is refused: VACUUM INTO writes a copy of the database to a new file"
            keyword != "PRAGMA" -> null
            exec ->
                "PRAGMA is refused in exec: a pragma can change a setting of the app's connection, such as foreign_keys or query_only. " +
                    "Read a pragma with POST query"
            else -> processPragma(sql, word.end)
        }
    }

    // A few pragmas set a value for every connection of the process, the app's too, and SQLite
    // applies them while it compiles the statement. The name can be quoted, and have a schema.
    private fun processPragma(sql: String, from: Int): String? {
        var start = skip(sql, from, semicolons = false)
        var end = nameEnd(sql, start) ?: return UNREADABLE_PRAGMA
        val dot = skip(sql, end, semicolons = false)
        if (dot < sql.length && sql[dot] == '.') {
            start = skip(sql, dot + 1, semicolons = false)
            end = nameEnd(sql, start) ?: return UNREADABLE_PRAGMA
        }
        val name = sql.substring(start, end).let { if (it.first() in QUOTES) it.substring(1, it.length - 1) else it }.lowercase(Locale.ROOT)
        return if (name in PROCESS_PRAGMAS) "PRAGMA $name is refused: it changes a setting of every SQLite connection of the app" else null
    }

    // The end of a name at [from]: a word, or a quoted name, or null when there is none.
    private fun nameEnd(sql: String, from: Int): Int? {
        if (from >= sql.length) return null
        val char = sql[from]
        val close = QUOTES[char] ?: return if (isWordStart(char)) wordEnd(sql, from) else null
        var index = from + 1
        while (index < sql.length) {
            if (sql[index] == close) {
                // A doubled quote is a quote in the name, but for ], which has no escape.
                if (close != ']' && index + 1 < sql.length && sql[index + 1] == close) index += 2 else return index + 1
            } else {
                index++
            }
        }
        return null
    }

    private class Word(val text: String, val end: Int)

    // EXPLAIN compiles the statement that follows it, and SQLite applies some pragmas while it compiles.
    private fun List<Word>.withoutExplain(): List<Word> {
        if (firstOrNull()?.text != "EXPLAIN") return this
        val rest = drop(1)
        return if (rest.size >= 2 && rest[0].text == "QUERY" && rest[1].text == "PLAN") rest.drop(2) else rest
    }

    /** The first keywords of [sql], upper case, until a token that is not a word. */
    private fun words(sql: String): List<Word> {
        val words = ArrayList<Word>(MAX_WORDS)
        var index = skipped(sql)
        while (words.size < MAX_WORDS && index < sql.length && isWordStart(sql[index])) {
            val end = wordEnd(sql, index)
            words += Word(sql.substring(index, end).uppercase(Locale.ROOT), end)
            index = skip(sql, end, semicolons = false)
        }
        return words
    }

    // Where the first statement starts: past white space, comments, and empty statements.
    private fun skipped(sql: String): Int = skip(sql, 0, semicolons = true)

    private fun skip(sql: String, from: Int, semicolons: Boolean): Int {
        var index = from
        while (index < sql.length) {
            val char = sql[index]
            index =
                when {
                    char in WHITE_SPACE || (semicolons && char == ';') -> index + 1
                    sql.startsWith("--", index) -> sql.indexOf('\n', index).let { if (it < 0) sql.length else it + 1 }
                    // A comment that does not end runs to the end of the text, as in SQLite.
                    sql.startsWith("/*", index) -> sql.indexOf("*/", index + 2).let { if (it < 0) sql.length else it + 2 }
                    else -> return index
                }
        }
        return index
    }

    // SQLite's identifier characters: a word that goes on past a keyword, such as BEGIN_X, is a name.
    private fun wordEnd(sql: String, from: Int): Int {
        var index = from
        while (index < sql.length && isWordPart(sql[index])) index++
        return index
    }

    // A byte order mark is white space to SQLite, not a character of a name.
    private fun isWordStart(char: Char): Boolean =
        char in 'A'..'Z' || char in 'a'..'z' || char == '_' || (char.code >= NON_ASCII && char != BYTE_ORDER_MARK)

    private fun isWordPart(char: Char): Boolean = isWordStart(char) || char in '0'..'9' || char == '$'

    // EXPLAIN QUERY PLAN and the statement's keyword.
    private const val MAX_WORDS = 4

    private const val NON_ASCII = 0x80

    private const val BYTE_ORDER_MARK = '﻿'

    // The characters that SQLite's tokenizer takes as white space: ASCII's, and a byte order mark
    // anywhere between two tokens.
    private const val WHITE_SPACE = " \t\n\u000B\u000C\r\uFEFF"

    // Fail closed: SQLite would refuse the statement too.
    private const val UNREADABLE_PRAGMA = "PRAGMA is refused: the tab can't read the pragma's name"

    private val TRANSACTION = setOf("BEGIN", "COMMIT", "END", "ROLLBACK", "SAVEPOINT", "RELEASE")

    // The quotes of a name in SQLite, and the character that ends each.
    private val QUOTES = mapOf('"' to '"', '`' to '`', '[' to ']', '\'' to '\'')

    // The pragmas whose value is the process's: SQLite's heap limits and its directory for temporary files.
    private val PROCESS_PRAGMAS = setOf("hard_heap_limit", "soft_heap_limit", "temp_store_directory", "data_store_directory")
}
