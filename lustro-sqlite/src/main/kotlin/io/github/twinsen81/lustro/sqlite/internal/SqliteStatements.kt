package io.github.twinsen81.lustro.sqlite.internal

import java.util.Locale

/**
 * The statements that `POST query` and `POST exec` refuse before they run.
 *
 * This is not a read and write classifier: the read-only connection of `query`
 * is what refuses a write. The check finds the first keyword of the statement
 * that SQLite would run, past white space, comments, empty statements, and
 * `EXPLAIN [QUERY PLAN]`, as SQLite's own tokenizer does, and refuses the
 * statements that leave state behind on a connection, or write a file.
 */
internal object SqliteStatements {
    /** Whether [sql] has a statement, and not only white space, comments, and `;`. */
    fun hasStatement(sql: String): Boolean = skipped(sql) < sql.length

    /** Why [sql] can't run in `POST query` (with [exec] false) or `POST exec`, or `null` when it can. */
    fun refusal(sql: String, exec: Boolean): String? {
        val keyword = keywords(sql).withoutExplain().firstOrNull() ?: return null
        return when {
            keyword in TRANSACTION -> "$keyword is refused: a transaction that a request starts would stay open on the connection after it"
            keyword == "ATTACH" || keyword == "DETACH" -> "$keyword is refused: it changes the databases of the connection, and ATTACH can create a file"
            keyword == "VACUUM" -> "VACUUM is refused: VACUUM INTO writes a copy of the database to a new file"
            exec && keyword == "PRAGMA" ->
                "PRAGMA is refused in exec: a pragma can change a setting of the app's connection, such as foreign_keys or query_only. " +
                    "Read a pragma with POST query"
            else -> null
        }
    }

    // EXPLAIN compiles the statement that follows it, and SQLite applies some pragmas while it compiles.
    private fun List<String>.withoutExplain(): List<String> {
        if (firstOrNull() != "EXPLAIN") return this
        val rest = drop(1)
        return if (rest.size >= 2 && rest[0] == "QUERY" && rest[1] == "PLAN") rest.drop(2) else rest
    }

    /** The first keywords of [sql], upper case, until a token that is not a word. */
    private fun keywords(sql: String): List<String> {
        val words = ArrayList<String>(MAX_WORDS)
        var index = skipped(sql)
        while (words.size < MAX_WORDS && index < sql.length && isWordStart(sql[index])) {
            val end = wordEnd(sql, index)
            words += sql.substring(index, end).uppercase(Locale.ROOT)
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

    private fun isWordStart(char: Char): Boolean = char in 'A'..'Z' || char in 'a'..'z' || char == '_' || char.code >= NON_ASCII

    private fun isWordPart(char: Char): Boolean = isWordStart(char) || char in '0'..'9' || char == '$'

    // EXPLAIN QUERY PLAN and the statement's keyword.
    private const val MAX_WORDS = 4

    private const val NON_ASCII = 0x80

    // The characters that SQLite's tokenizer takes as white space.
    private const val WHITE_SPACE = " \t\n\u000B\u000C\r"

    private val TRANSACTION = setOf("BEGIN", "COMMIT", "END", "ROLLBACK", "SAVEPOINT", "RELEASE")
}
