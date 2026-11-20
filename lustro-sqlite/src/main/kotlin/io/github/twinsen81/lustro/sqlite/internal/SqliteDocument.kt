package io.github.twinsen81.lustro.sqlite.internal

import io.github.twinsen81.lustro.DebugRoutes.Companion.ref
import io.github.twinsen81.lustro.ExperimentalDebugRoutes

/**
 * The text of the SQLite tab's OpenAPI document that the route declarations in [SqliteRoutes]
 * do not make: the descriptions and the response schemas. [SQLITE_SCHEMAS] has the shared schemas.
 */
@OptIn(ExperimentalDebugRoutes::class)
internal object SqliteDocument {
    const val TITLE: String = "Lustro SQLite debug tab"

    const val DESCRIPTION: String =
        "The app's SQLite and Room databases: the ones that the app registers, and, when the app turns discovery on, the " +
        "other database files in its databases directory and in its noBackupFilesDir, where WorkManager keeps " +
        "androidx.work.workdb. A registered database is the app's own open handle, a framework SQLiteDatabase or an " +
        "androidx SupportSQLiteDatabase such as Room's, or a file that the tab opens read-only for each request; a " +
        "discovered file is opened the same way. The GET routes run only SQL that the tab builds: each database, table, " +
        "and column that a client names is checked against the database's schema and quoted as an identifier, and a " +
        "filter value is a bound parameter. Each database has a value policy, visible or mask_sensitive (the default, and " +
        "always for a discovered file): mask_sensitive leaves out the values of the columns whose names look like they " +
        "hold a secret, such as access_token or client_secret, by the same name check as the Network tab's redaction, in " +
        "GET rows and POST query; such a cell is {type, masked: true}. A page of rows holds each cell with its storage " +
        "class: at most 4096 characters of a text, with the whole length when it is cut, and only the size of a blob. " +
        "POST query runs a client's statement on a connection that SQLite opens read-only for the request, so SQLite " +
        "refuses every write. POST exec, edit, and delete write through the app's handle, only to a database that the app " +
        "registers with writable = true, and answer 403 with code read_only for any other. Both query and exec refuse " +
        "BEGIN, COMMIT, END, ROLLBACK, SAVEPOINT, RELEASE, ATTACH, DETACH, and VACUUM before they run; exec also refuses " +
        "every PRAGMA, and query a pragma with a value, which sets it, and the pragmas that set a value for the whole " +
        "process. Each read stops when the request times out, and the client then gets a 504. On a connection that the " +
        "tab opens itself (a file database, registered or discovered, and every POST query), a database out of WAL mode " +
        "makes the app's writes wait for the tab's lock, and Android's SQLite fails a write that waited 2.5 s: there a " +
        "statement stops after 2 s, with a 504 with code read_limit. All routes are token-authenticated (Bearer header or " +
        "HttpOnly cookie) and live under /api/v1/sqlite. Errors use the shared error envelope; a statement that SQLite " +
        "refuses in a GET route, such as one on a corrupt file, is a 500 with code sqlite_error and SQLite's message."

    const val DATABASES_DESCRIPTION: String =
        "The registered databases, in the order the app registered them, then the discovered files: those in the " +
        "databases directory by file name, then those in noBackupFilesDir by file name, or as no_backup/<file name> when " +
        "a registration or a file in the databases directory has that name. Each one is opened, to read its schema " +
        "version: Room opens its database here if the app has not yet. A database that does not open is listed with " +
        "schemaVersion null and the reason in error."

    const val TABLES_DESCRIPTION: String =
        "Every table and view in sqlite_master, by name, as SQLite sorts names without case. A table's rows are counted; " +
        "a view's are not, because that runs the view's query. The tables that SQLite, Android, and Room keep for " +
        "themselves, and the shadow tables of a full-text or R*Tree index, are listed with internal true, so that a view " +
        "can hide them. On a file database that is not in WAL mode, a statement stops after 2 s: a table whose count " +
        "stops is listed without rowCount, with the reason in error, and another statement that stops is a 504 with code " +
        "read_limit."

    const val SCHEMA_DESCRIPTION: String =
        "What PRAGMA table_xinfo (or table_info, before SQLite 3.26) and PRAGMA foreign_key_list say, the statement that " +
        "created it, and its hint. Generated columns are included; the hidden columns of a virtual table are not. The " +
        "columns are in their order in the table, which is the order of the cells in GET rows. On a file database that is " +
        "not in WAL mode, a statement stops after 2 s: 504 with code read_limit."

    const val ROWS_DESCRIPTION: String =
        "A page of the rows of a table or view in the pagination envelope. Without a sort, a table's rows come in the " +
        "order of their rowid, or of the primary key for a table WITHOUT ROWID; a view's in the order SQLite gives. A " +
        "sort on a column also sorts by that key, so that rows with the same value keep their order from page to page. " +
        "nextCursor is null on the last page; pass it as cursor for the next one. A cursor holds a place in the rows, so " +
        "a page can start at another row when the rows change between two requests. On a file database that is not in WAL " +
        "mode, a statement stops after 2 s: 504 with code read_limit."

    const val QUERY_DESCRIPTION: String =
        "Runs a client's statement on a connection that SQLite opens read-only to the database's file, for this request " +
        "only; never on the app's handle, on which any statement, a DELETE too, could write. SQLite refuses every write " +
        "there, so a statement that writes is a 403 with code read_only and field sql, and changes nothing. A pragma that " +
        "only reads works: PRAGMA user_version, and a pragma whose value names what to read, such as PRAGMA " +
        "table_info(notes). BEGIN, COMMIT, END, ROLLBACK, SAVEPOINT, RELEASE, ATTACH, DETACH, and VACUUM are refused " +
        "before they run, and so is a pragma with a value (PRAGMA journal_mode = DELETE, PRAGMA user_version(9)), which " +
        "sets it, but for foreign_key_check, foreign_key_list, index_info, index_list, index_xinfo, integrity_check, " +
        "quick_check, table_info, table_list, and table_xinfo, and the pragmas that set a value for every connection of " +
        "the process (hard_heap_limit, soft_heap_limit, temp_store_directory, data_store_directory), also after comments, " +
        "white space, a byte order mark, empty statements, and EXPLAIN: 400 with code statement_refused and field sql. A " +
        "database in memory has no file, and a live handle on another copy of SQLite than the platform's, such as " +
        "SQLCipher's, would not share its locks with the tab's connection: both are a 400 with code query_unavailable and " +
        "field db; read them with GET rows. A result has at most 1000 rows, and fewer when its texts pass 4 Mi characters " +
        "in all; truncated says that rows were left out. A text comes whole, and a blob is only its size. With the value " +
        "policy mask_sensitive, a result column whose name looks like it holds a secret has only the storage class of " +
        "each cell, with masked: true. The name is the result's, as the statement names it: Android does not say which " +
        "table column a result column comes from, so SELECT access_token AS t shows the value, while length(access_token) " +
        "without an alias is masked by its name. Android's cursor steps through the whole result once, to count it, so a " +
        "statement over a large table should have a LIMIT. A row that does not fit Android's cursor window, about 2 MB, " +
        "is a 400 with code row_too_big. A statement that SQLite can't run is a 400 with code sqlite_error and SQLite's " +
        "message; one that fails on the file or the device, such as a corrupt file, a 500 with that code. A statement " +
        "stops at the request's timeout, with a 504. On a database that is not in WAL mode, a read holds a lock that the " +
        "app's writes wait for, and Android's SQLite gives up a write after 2.5 s: there a statement stops after 2 s, " +
        "with a 504 with code read_limit and field sql."

    const val EXEC_DESCRIPTION: String =
        "Runs a client's statement through the app's handle of a writable database, in a transaction of its own that " +
        "takes the write lock, and commits it. The statement must return no rows: one that returns rows, such as a SELECT " +
        "or a write with RETURNING, is a 400 with code statement_refused and field sql, and does not run; read it with " +
        "POST query. The statements that query refuses are refused here too, and every PRAGMA also, because a pragma can " +
        "change a setting of the app's connection, such as foreign_keys or query_only: 400 with code statement_refused " +
        "and field sql. A statement that creates or drops a TEMP table, view, index, or trigger, which would stay on the " +
        "app's connection, is rolled back, with the same error; a TEMP trigger that SQLite drops or rewrites with its " +
        "table, such as Room's on a table that it observes, does not count. When the app has the foreign keys on, a " +
        "statement that breaks a deferred foreign key (DEFERRABLE INITIALLY DEFERRED) is rolled back before the commit, " +
        "with 400 and code sqlite_error: SQLite checks such a key at COMMIT, and a COMMIT that fails would keep the " +
        "transaction open on the app's connection. A statement that SQLite refuses, such as one that breaks a constraint, " +
        "is rolled back: 400 with code sqlite_error and SQLite's message. SQLite can't stop a write at the request's " +
        "timeout: the statement runs to its end and is then rolled back. Only a timeout during the commit itself can " +
        "leave the change committed, so read the data again after a 504 before sending the write again. Room's observers " +
        "see the change at once when the app registers the database with an onWrite that refreshes Room's " +
        "InvalidationTracker, and otherwise at the app's next write through Room."

    const val EDIT_DESCRIPTION: String =
        "Sets the values of one row of a table, through the app's handle of a writable database, in a transaction of its " +
        "own. 400 with field values when values is missing or empty, names a column that the table does not have or a " +
        "generated one, or has a value that is not a string, a number, or null. The row must be the only one with its " +
        "rowid or key: 404 with the field rowid or key when no row has it, and 400 with that field when more than one row " +
        "has it, which a NULL in a primary key column allows. The change is rolled back when SQLite refuses it, such as " +
        "for a constraint, also a deferred foreign key, which the tab checks before the commit: 400 with code " +
        "sqlite_error. The tab finds each name in the table's schema and binds each value, as in the GET routes. A column " +
        "that the value policy masks can be written, as in the Preferences tab; the console does not offer it."

    const val DELETE_DESCRIPTION: String =
        "Deletes one row of a table, through the app's handle of a writable database, in a transaction of its own. The " +
        "row must be the only one with its rowid or key: 404 with the field rowid or key when no row has it, and 400 with " +
        "that field when more than one row has it, which a NULL in a primary key column allows. The change is rolled back " +
        "when SQLite refuses it, such as for a constraint, also a deferred foreign key, which the tab checks before the " +
        "commit: 400 with code sqlite_error. The tab finds each name in the table's schema and binds each value, as in " +
        "the GET routes."

    const val DB: String =
        "The database's name, as GET databases lists it. 400 with field db when it is missing; 404 with field db when " +
        "there is no database of this name; 500 with code open_failed and field db when the database does not open."

    const val TABLE: String =
        "The table's or view's name, as GET tables lists it. A name in another case finds it too, as in SQL. 400 with " +
        "field table when it is missing; 404 with field table when the database has no table or view of this name."

    const val CURSOR: String =
        "The nextCursor of the page before. Without it, the first page. 400 with field cursor when it is not a cursor " +
        "that this route returned."

    const val LIMIT: String =
        "The most rows in the page. 400 with field limit when it is not a whole number from 1 to 500."

    const val FILTER: String =
        "Only the rows where a cell, as text, contains this text. Case does not matter for ASCII letters. % and _ are " +
        "plain characters here. A blob never matches. A column that the value policy masks is not searched."

    const val SORT: String =
        "The column to sort by, as GET schema names it, or rowid when the table has one and no column has that name. 400 " +
        "with field sort when the table has no such column. A masked column sorts too: the order of the rows shows the " +
        "order of its values, but not the values."

    const val DIR: String =
        "The sort's direction; without a sort, the direction of the rowid or the primary key. 400 with field dir when it " +
        "is neither."

    const val SQL: String =
        "One statement. SQLite runs only the first statement of the text and ignores the rest. 400 with field sql when it " +
        "is missing, or has only white space and comments."

    const val WRITABLE_DB: String =
        "The database's name, as GET databases lists it. 400 with field db when it is missing; 404 with field db when " +
        "there is no database of this name; 500 with code open_failed and field db when the database does not open. 403 " +
        "with code read_only and field db when the database is not writable, before the other values of the body are " +
        "checked; only a required field that is missing, or a field that is not of its JSON type, is a 400 first."

    const val WRITABLE_TABLE: String =
        "The table's name, as GET tables lists it. A name in another case finds it too, as in SQL. 400 with field table " +
        "when it is missing, or names a view; 404 with field table when the database has no table of this name."

    const val ROWID: String =
        "The row's rowid, as GET rows gives it. Name the row by rowid or by key, not both. 400 with field rowid when it " +
        "is not a whole number, when the table has no rowid (WITHOUT ROWID), and when the body has neither rowid nor key."

    const val KEY: String =
        "The row's primary key: each column of the key, and only those, with its value. A text value matches a number in " +
        "a column with a numeric type. 400 with field key when the columns are not those of the primary key, or the table " +
        "has none."

    const val VALUES: String = "Each column to set, by its name as GET schema gives it, with its value."

    const val DIR_SCHEMA: String = """
        {
          "type": "string",
          "enum": ["asc", "desc"],
          "default": "asc"
        }
    """

    const val ROWID_SCHEMA: String = """
        {
          "type": ["string", "integer"],
          "pattern": "^-?[0-9]+${'$'}"
        }
    """

    val KEY_SCHEMA: String = """
        {
          "type": "object",
          "additionalProperties": ${ref("SqliteValue")}
        }
    """

    val VALUES_SCHEMA: String = """
        {
          "type": "object",
          "minProperties": 1,
          "additionalProperties": ${ref("SqliteValue")}
        }
    """

    val DATABASES_RESPONSE_SCHEMA: String = """
        {
          "type": "object",
          "required": ["items"],
          "properties": {
            "items": {
              "type": "array",
              "items": ${ref("SqliteDatabase")}
            }
          },
          "additionalProperties": false
        }
    """

    val TABLES_RESPONSE_SCHEMA: String = """
        {
          "type": "object",
          "required": ["database", "writable", "items"],
          "properties": {
            "database": {
              "type": "string",
              "description": "The database's name."
            },
            "writable": {
              "type": "boolean",
              "description": "As in GET databases."
            },
            "items": {
              "type": "array",
              "items": ${ref("SqliteTable")}
            }
          },
          "additionalProperties": false
        }
    """
}
