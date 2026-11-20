package io.github.twinsen81.lustro.logs.internal

import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugRoutes
import io.github.twinsen81.lustro.DebugTab
import io.github.twinsen81.lustro.ExperimentalDebugRoutes
import java.util.WeakHashMap

/** A tab with only the routes that [logRoutes] declares, so that a test calls them without the server. */
private class LogRoutesTab(private val logRoutes: LogRoutes) : DebugTab() {
    override val id: String = "logs"
    override val title: String = "Logs"
    override val icon: String = ""

    @OptIn(ExperimentalDebugRoutes::class)
    override fun routes(routes: DebugRoutes) {
        logRoutes.declare(routes)
    }
}

private val tabs = WeakHashMap<LogRoutes, DebugTab>()

/** Answers [request] as the Logs tab does, with its route declarations. */
internal fun LogRoutes.handle(request: DebugRequest): DebugResponse? =
    synchronized(tabs) { tabs.getOrPut(this) { LogRoutesTab(this) } }.handle(request)
