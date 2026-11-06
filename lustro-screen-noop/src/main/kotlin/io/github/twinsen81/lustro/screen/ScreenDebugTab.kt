package io.github.twinsen81.lustro.screen

import android.content.Context
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugTab

/**
 * The Screen tab (no-op build).
 *
 * Mirrors the `:lustro-screen` [ScreenDebugTab] public surface so that the app's
 * release code compiles unchanged. The tab's identity ([id], [title], [icon],
 * [order], [showInTabBar]) is the same, but it reads no window, never calls the
 * app's [ScreenContextProvider], and [handle] returns `null`.
 */
public class ScreenDebugTab private constructor() : DebugTab() {
    override val id: String = "screen"
    override val title: String = "Screen"
    override val icon: String = "📱"
    override val order: Int = 60

    /** `false`, as in the runtime build. */
    override val showInTabBar: Boolean = false

    /** Returns `null`; the no-op build routes nothing. */
    override fun handle(request: DebugRequest): DebugResponse? = null

    /** Factory for [ScreenDebugTab]. */
    public companion object {
        /**
         * Creates a [ScreenDebugTab].
         *
         * @param context a context of the app.
         * @param contextProvider what only the app knows about the screen.
         *
         * No-op build: the tab registers no callbacks and never calls [contextProvider].
         */
        @JvmStatic
        @JvmOverloads
        @Suppress("UNUSED_PARAMETER")
        public fun create(context: Context, contextProvider: ScreenContextProvider? = null): ScreenDebugTab = ScreenDebugTab()
    }
}
