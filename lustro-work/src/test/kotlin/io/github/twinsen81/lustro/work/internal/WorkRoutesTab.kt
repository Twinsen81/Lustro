package io.github.twinsen81.lustro.work.internal

import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugRoutes
import io.github.twinsen81.lustro.DebugTab
import io.github.twinsen81.lustro.ExperimentalDebugRoutes
import java.util.WeakHashMap

/** A tab with only the routes that [workRoutes] declares, so that a test calls them without the server. */
private class WorkRoutesTab(private val workRoutes: WorkRoutes) : DebugTab() {
    override val id: String = "work"
    override val title: String = "WorkManager"
    override val icon: String = ""

    @OptIn(ExperimentalDebugRoutes::class)
    override fun routes(routes: DebugRoutes) {
        workRoutes.declare(routes)
    }
}

private val tabs = WeakHashMap<WorkRoutes, DebugTab>()

/** Answers [request] as the WorkManager tab does, with its route declarations. */
internal fun WorkRoutes.handle(request: DebugRequest): DebugResponse? =
    synchronized(tabs) { tabs.getOrPut(this) { WorkRoutesTab(this) } }.handle(request)
