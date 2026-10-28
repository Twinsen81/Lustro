package io.github.twinsen81.lustro.sqlite

import android.content.Context
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugTab
import io.github.twinsen81.lustro.sqlite.internal.FileSource
import io.github.twinsen81.lustro.sqlite.internal.SqliteRegistry
import io.github.twinsen81.lustro.sqlite.internal.SqliteRoutes
import io.github.twinsen81.lustro.sqlite.internal.SqliteView
import java.io.File

/**
 * The SQLite tab: the tables, schemas, and rows of the app's SQLite and Room
 * databases, under `/api/v1/sqlite/`.
 *
 * Register it from debug code, as the Network tab:
 *
 * ```
 * Lustro.builder(app)
 *     .addTab(
 *         SqliteDebugTab.create(
 *             databases = listOf(
 *                 SqliteDb.support("app") { appDatabase.openHelper.writableDatabase },
 *                 SqliteDb.file("cache", app.getDatabasePath("cache.db")),
 *             ),
 *         ),
 *     )
 *     .build()
 *     .start()
 * ```
 *
 * The tab shows the [registered databases][SqliteDb], and, when the app passes
 * a context as `discoverFrom`, the other database files in the app's
 * `databases` directory, such as those of its libraries. It opens a discovered
 * file read-only for each request, as a [SqliteDb.file] database.
 *
 * The routes that browse run only the SQL that the tab builds itself: a client
 * names a database, a table, and a column, and the tab checks each name against
 * the database's schema before it puts it in a statement, as a quoted
 * identifier. A filter value is a bound parameter. A page of rows holds at most
 * 4096 characters of a text value, with its length, and only the size of a
 * blob, so a large cell does not fill the memory or the response. Each read
 * stops when the request times out.
 *
 * `POST query` runs a client's statement on a connection that SQLite opens
 * read-only to the database's file, for one request, so SQLite refuses every
 * write. `POST exec`, `edit`, and `delete` write through the app's handle, only
 * to a [writable][SqliteDb.writable] database. Neither route runs a statement
 * that would leave a transaction or a setting on a connection: `BEGIN`,
 * `SAVEPOINT`, and the other transaction statements, `ATTACH`, `DETACH`, and
 * `VACUUM`, and in `exec` also every `PRAGMA`.
 *
 * Every client that has the token can read every registered database, and write
 * every writable one: register only the databases that you want to show, and
 * make writable only those that a client may change.
 *
 * In the console, the tab lists the databases and their tables, and shows a
 * page of rows, which a client sorts, filters, and pages through, with a row
 * editor for a writable database and a console for a client's own statements.
 * `GET /api/v1/sqlite/_schema` describes its routes.
 */
public class SqliteDebugTab private constructor(registry: SqliteRegistry) : DebugTab() {
    override val id: String = "sqlite"
    override val title: String = "SQLite"
    override val icon: String = "🗄️"
    override val order: Int = 40

    private val routes = SqliteRoutes(registry)

    /** The view's static shell; `sqlite.js` and `sqlite.css` in the assets fill and style it. */
    override fun renderContent(): String = SqliteView.HTML

    /** Serves the routes under `/api/v1/sqlite/`; the static `sqlite.openapi.json` describes them. */
    override fun handle(request: DebugRequest): DebugResponse? = routes.handle(request)

    /** Factory for [SqliteDebugTab]. */
    public companion object {
        /**
         * Creates a [SqliteDebugTab].
         *
         * @param databases the databases to show, in this order, before the
         *   discovered ones.
         * @param discoverFrom a context of the app, to show the other database
         *   files in its `databases` directory too, read-only, or `null` to show
         *   only [databases]. The tab lists the directory on each request, so it
         *   also finds a file that the app creates later. It leaves out a file
         *   that one of [databases] stands for, SQLite's journal, WAL, and lock
         *   files, and a file that is not a SQLite database, which the platform's
         *   SQLite finds out. Leave discovery off when the directory has a
         *   database that the app opens with another copy of SQLite, as
         *   [SqliteDb.file] says.
         * @throws IllegalArgumentException when two of [databases] have the
         *   same name, or two [SqliteDb.file] databases the same file. The tab
         *   does not know the file of a live handle before it reads, so it can't
         *   check those: register each file once.
         */
        @JvmStatic
        @JvmOverloads
        public fun create(databases: List<SqliteDb> = emptyList(), discoverFrom: Context? = null): SqliteDebugTab {
            val names = HashSet<String>()
            val files = HashMap<File, String>()
            for (database in databases) {
                require(names.add(database.name)) { "Two databases are named \"${database.name}\"" }
                val file = (database.source as? FileSource)?.file ?: continue
                val other = files.put(file, database.name)
                require(other == null) { "The databases \"$other\" and \"${database.name}\" are the same file: $file" }
            }
            // Where ContextImpl keeps the files that getDatabasePath names. getDatabasePath() would
            // create the directory, on the caller's thread.
            val directory = discoverFrom?.let { File((it.applicationContext ?: it).dataDir, "databases") }
            return SqliteDebugTab(SqliteRegistry(databases, directory))
        }
    }
}
