package io.github.twinsen81.lustro.sqlite.internal

import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugRoutes
import io.github.twinsen81.lustro.DebugTab
import io.github.twinsen81.lustro.ExperimentalDebugRoutes
import java.util.WeakHashMap

/** A tab with only the routes that [sqliteRoutes] declares, so that a test calls them without the server. */
private class SqliteRoutesTab(private val sqliteRoutes: SqliteRoutes) : DebugTab() {
    override val id: String = "sqlite"
    override val title: String = "SQLite"
    override val icon: String = ""

    @OptIn(ExperimentalDebugRoutes::class)
    override fun routes(routes: DebugRoutes) {
        sqliteRoutes.declare(routes)
    }
}

private val tabs = WeakHashMap<SqliteRoutes, DebugTab>()

/** Answers [request] as the SQLite tab does, with its route declarations. */
internal fun SqliteRoutes.handle(request: DebugRequest): DebugResponse? =
    synchronized(tabs) { tabs.getOrPut(this) { SqliteRoutesTab(this) } }.handle(request)
