package io.github.twinsen81.lustro.sqlite

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

/**
 * A database that the app registers with [SqliteDebugTab.create] (no-op build).
 *
 * Mirrors the `:lustro-sqlite` [SqliteDb] public surface so that the app's
 * release code compiles unchanged. The factories keep the arguments, check them
 * as the runtime does, and never call the suppliers.
 *
 * @property name the database's name in the API.
 * @property writable whether a client may change the database through the tab.
 * @property valuePolicy what a client sees of the values.
 */
public class SqliteDb private constructor(
    public val name: String,
    public val writable: Boolean,
    tableHints: Map<String, TableHint>,
    public val valuePolicy: SqliteValuePolicy,
    unmasked: Set<String>,
    internal val file: File?,
) {
    /** What the tables and views hold, by table name. */
    public val tableHints: Map<String, TableHint> = tableHints.toMap()

    /** The column names whose values the runtime tab shows under [SqliteValuePolicy.MASK_SENSITIVE], as the app passed them. */
    public val unmasked: Set<String> = unmasked.toSet()

    init {
        require(name.isNotBlank()) { "name must not be blank" }
    }

    /** Factories for [SqliteDb]. */
    public companion object {
        /** A database that the app has open as a framework [SQLiteDatabase]. No-op build: [database] and [onWrite] are never called. */
        @JvmStatic
        @JvmOverloads
        @Suppress("UNUSED_PARAMETER")
        public fun framework(
            name: String,
            writable: Boolean = false,
            tableHints: Map<String, TableHint> = emptyMap(),
            valuePolicy: SqliteValuePolicy = SqliteValuePolicy.MASK_SENSITIVE,
            onWrite: (() -> Unit)? = null,
            unmasked: Set<String> = emptySet(),
            database: () -> SQLiteDatabase,
        ): SqliteDb = SqliteDb(name, writable, tableHints, valuePolicy, unmasked, file = null)

        /** A database that the app has open as an androidx [SupportSQLiteDatabase]. No-op build: [database] and [onWrite] are never called. */
        @JvmStatic
        @JvmOverloads
        @Suppress("UNUSED_PARAMETER")
        public fun support(
            name: String,
            writable: Boolean = false,
            tableHints: Map<String, TableHint> = emptyMap(),
            valuePolicy: SqliteValuePolicy = SqliteValuePolicy.MASK_SENSITIVE,
            onWrite: (() -> Unit)? = null,
            unmasked: Set<String> = emptySet(),
            database: () -> SupportSQLiteDatabase,
        ): SqliteDb = SqliteDb(name, writable, tableHints, valuePolicy, unmasked, file = null)

        /**
         * A database file. No-op build: the file is never opened.
         *
         * @throws IllegalArgumentException when [file] is not an absolute path.
         */
        @JvmStatic
        @JvmOverloads
        public fun file(
            name: String,
            file: File,
            tableHints: Map<String, TableHint> = emptyMap(),
            valuePolicy: SqliteValuePolicy = SqliteValuePolicy.MASK_SENSITIVE,
            unmasked: Set<String> = emptySet(),
        ): SqliteDb {
            require(file.isAbsolute) { "file must be an absolute path, such as context.getDatabasePath(name): $file" }
            return SqliteDb(name, writable = false, tableHints, valuePolicy, unmasked, file.normalize())
        }
    }
}
