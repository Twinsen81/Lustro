package io.github.twinsen81.lustro.sqlite

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.twinsen81.lustro.sqlite.internal.DbSource
import io.github.twinsen81.lustro.sqlite.internal.FileSource
import io.github.twinsen81.lustro.sqlite.internal.FrameworkSource
import io.github.twinsen81.lustro.sqlite.internal.SupportSource
import java.io.File

/**
 * A database that the app registers with [SqliteDebugTab.create]. Make one with
 * [framework] or [support] for a database that the app has open, or with [file]
 * for a database file that the tab opens itself.
 *
 * Give the tab the app's own handle to a database that the app uses: the tab
 * then reads through the app's connection pool. A second connection that stays
 * open to the same file can conflict with the app's connection, mostly in WAL
 * mode. A [file] database is for a file that the app does not hold open, or
 * holds through a library: the tab opens it read-only for each request and
 * closes it after.
 *
 * @property name the database's name in the API. The routes take it as `db`.
 * @property writable whether a client may change the database through the tab,
 *   with `POST exec`, `edit`, and `delete`, which write through the app's handle.
 *   They answer 403 for a database that is not writable. `POST query` reads
 *   every database, through a connection that SQLite opens read-only. A [file]
 *   database is never writable.
 * @property valuePolicy what a client sees of the values: every value, or all
 *   but those of the columns whose names look like they hold a secret, which is
 *   the default. See [SqliteValuePolicy].
 */
public class SqliteDb private constructor(
    public val name: String,
    public val writable: Boolean,
    tableHints: Map<String, TableHint>,
    public val valuePolicy: SqliteValuePolicy,
    internal val source: DbSource,
    internal val onWrite: (() -> Unit)? = null,
) {
    /** What the tables and views hold, by table name. */
    public val tableHints: Map<String, TableHint> = tableHints.toMap()

    init {
        require(name.isNotBlank()) { "name must not be blank" }
    }

    /** Factories for [SqliteDb]. */
    public companion object {
        /**
         * A database that the app has open as a framework [SQLiteDatabase],
         * such as the one that a `SQLiteOpenHelper` returns.
         *
         * @param onWrite called on a request's thread after each `POST exec`,
         *   `edit`, or `delete` that committed a change to the database, so that
         *   the app can tell its own readers, such as a cache. What it throws is
         *   logged: the write stays committed.
         * @param database returns the app's open instance. The tab calls it on
         *   a request's thread, each time it reads, and never closes what it
         *   returns.
         */
        @JvmStatic
        @JvmOverloads
        public fun framework(
            name: String,
            writable: Boolean = false,
            tableHints: Map<String, TableHint> = emptyMap(),
            valuePolicy: SqliteValuePolicy = SqliteValuePolicy.MASK_SENSITIVE,
            onWrite: (() -> Unit)? = null,
            database: () -> SQLiteDatabase,
        ): SqliteDb = SqliteDb(name, writable, tableHints, valuePolicy, FrameworkSource(database), onWrite)

        /**
         * A database that the app has open as an androidx [SupportSQLiteDatabase].
         * For Room, that is `{ roomDb.openHelper.writableDatabase }`; an
         * encrypted Room database, such as one that SQLCipher opens, works the
         * same way.
         *
         * The app needs `androidx.sqlite` on its classpath, which Room brings;
         * Lustro does not add it.
         *
         * Room's observers, such as a `Flow` of a DAO, look for changes after
         * Room's own transactions only. To make them see a change that the tab
         * writes at once, pass
         * `onWrite = { roomDb.invalidationTracker.refreshVersionsAsync() }`: that
         * public method of Room's `InvalidationTracker` is there for writes that
         * do not go through Room. Without it, they see the change at the app's
         * next write through Room.
         *
         * @param onWrite called on a request's thread after each `POST exec`,
         *   `edit`, or `delete` that committed a change to the database. What it
         *   throws is logged: the write stays committed.
         * @param database returns the app's open instance. The tab calls it on
         *   a request's thread, each time it reads, and never closes what it
         *   returns. Room opens the database on the first call, if the app has
         *   not yet.
         */
        @JvmStatic
        @JvmOverloads
        public fun support(
            name: String,
            writable: Boolean = false,
            tableHints: Map<String, TableHint> = emptyMap(),
            valuePolicy: SqliteValuePolicy = SqliteValuePolicy.MASK_SENSITIVE,
            onWrite: (() -> Unit)? = null,
            database: () -> SupportSQLiteDatabase,
        ): SqliteDb = SqliteDb(name, writable, tableHints, valuePolicy, SupportSource(database), onWrite)

        /**
         * A database file that the tab opens read-only for each request, and
         * closes after it. The file can be in WAL mode, and the app or a library
         * can write to it while the tab reads it.
         *
         * The tab opens the file with the platform's SQLite, which keeps the
         * locks of the app's other connections to it through that SQLite. Do not
         * register a file that the app opens with another copy of SQLite, such as
         * Room's bundled driver or SQLCipher: the platform's SQLite does not know
         * that copy's locks, and the close of the tab's connection drops them,
         * so another process could then write the file at the same time.
         *
         * @param file the database file, such as `context.getDatabasePath("cache.db")`.
         * @throws IllegalArgumentException when [file] is not an absolute path.
         */
        @JvmStatic
        @JvmOverloads
        public fun file(
            name: String,
            file: File,
            tableHints: Map<String, TableHint> = emptyMap(),
            valuePolicy: SqliteValuePolicy = SqliteValuePolicy.MASK_SENSITIVE,
        ): SqliteDb {
            require(file.isAbsolute) { "file must be an absolute path, such as context.getDatabasePath(name): $file" }
            return SqliteDb(name, writable = false, tableHints, valuePolicy, FileSource(file.normalize()))
        }
    }
}
