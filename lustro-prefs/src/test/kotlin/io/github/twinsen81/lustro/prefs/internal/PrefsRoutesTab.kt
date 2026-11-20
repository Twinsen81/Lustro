package io.github.twinsen81.lustro.prefs.internal

import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugRoutes
import io.github.twinsen81.lustro.DebugTab
import io.github.twinsen81.lustro.ExperimentalDebugRoutes
import java.util.WeakHashMap

/** A tab with only the routes that [prefsRoutes] declares, so that a test calls them without the server. */
private class PrefsRoutesTab(private val prefsRoutes: PrefsRoutes) : DebugTab() {
    override val id: String = "prefs"
    override val title: String = "Preferences"
    override val icon: String = ""

    @OptIn(ExperimentalDebugRoutes::class)
    override fun routes(routes: DebugRoutes) {
        prefsRoutes.declare(routes)
    }
}

private val tabs = WeakHashMap<PrefsRoutes, DebugTab>()

/** Answers [request] as the Preferences tab does, with its route declarations. */
internal fun PrefsRoutes.handle(request: DebugRequest): DebugResponse? =
    synchronized(tabs) { tabs.getOrPut(this) { PrefsRoutesTab(this) } }.handle(request)
