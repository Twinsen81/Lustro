package io.github.twinsen81.lustro.sqlite

import android.content.Context
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugTab
import java.io.File

/**
 * The SQLite tab (no-op build).
 *
 * Mirrors the `:lustro-sqlite` [SqliteDebugTab] public surface so that the
 * app's release code compiles unchanged. The tab's identity ([id], [title],
 * [icon], [order]) is the same, but it opens no database, and [handle]
 * returns `null`.
 */
public class SqliteDebugTab private constructor() : DebugTab() {
    override val id: String = "sqlite"
    override val title: String = "SQLite"
    override val icon: String = "🗄️"
    override val order: Int = 40

    /** Returns `null`; the no-op build routes nothing. */
    override fun handle(request: DebugRequest): DebugResponse? = null

    /** Factory for [SqliteDebugTab]. */
    public companion object {
        /**
         * Creates a [SqliteDebugTab].
         *
         * @param databases the databases to show.
         * @param discoverFrom a context of the app, to show the other database
         *   files in its `databases` directory too, or `null`.
         * @throws IllegalArgumentException when two of [databases] have the
         *   same name, or two [SqliteDb.file] databases the same file.
         *
         * No-op build: the tab keeps no context and opens nothing. The
         * databases are only checked, so that a list the runtime rejects fails
         * in release too.
         */
        @JvmStatic
        @JvmOverloads
        @Suppress("UNUSED_PARAMETER")
        public fun create(databases: List<SqliteDb> = emptyList(), discoverFrom: Context? = null): SqliteDebugTab {
            val names = HashSet<String>()
            val files = HashMap<File, String>()
            for (database in databases) {
                require(names.add(database.name)) { "Two databases are named \"${database.name}\"" }
                val file = database.file ?: continue
                val other = files.put(file, database.name)
                require(other == null) { "The databases \"$other\" and \"${database.name}\" are the same file: $file" }
            }
            return SqliteDebugTab()
        }
    }
}
