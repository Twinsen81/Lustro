package io.github.twinsen81.lustro.screen

import android.app.Application
import android.content.Context
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugTab
import io.github.twinsen81.lustro.screen.internal.ActivityTracker
import io.github.twinsen81.lustro.screen.internal.ScreenRoutes
import io.github.twinsen81.lustro.screen.internal.ScreenSource

/**
 * The Screen tab: the app's windows and the UI tree in each, under
 * `/api/v1/screen/`, for agents and scripts that drive the app.
 *
 * Register it from debug code:
 *
 * ```
 * // src/debug, LustroBootstrap
 * val screen = ScreenDebugTab.create(app) { """{"route":"${navController.currentRoute()}"}""" }
 * Lustro.builder(app).addTab(screen).build().start()
 * ```
 *
 * `GET tree` returns every window of the app's process, the activity and each
 * dialog, popup, and bottom sheet over it, in the order the app added them, with
 * the nodes of each: the classic Views, and the Compose semantics nodes of each
 * Compose host. A node carries its id, test tag, text, content description, role,
 * its bounds in the window and on the screen in pixels, the part of it that is
 * visible on the screen, whether it is enabled, its click and other semantics
 * actions, its selected and toggle state, focus, editable text, and scroll
 * position and range. A window carries whether it has the window focus: a window of
 * another app, such as a permission dialog, can cover the app's while the app
 * still answers, and then no window of the app has the focus.
 *
 * The tab reads the same data as `uiautomator dump`, in the app's process: a read
 * takes milliseconds instead of a second or more, sees every test tag, and does not
 * wait for the screen to stop changing. `GET tree?settle=500` waits, for up to that
 * many milliseconds, until two reads in a row on consecutive frames give the same
 * tree.
 *
 * Compose is optional. The tab checks at runtime that the app has Compose UI and
 * reads the semantics tree of each Compose host only then; the View tree it reads
 * in every app. The text of a password field comes as one dot for each character,
 * as the screen shows it.
 *
 * The tab reads the windows on the main thread. A main thread that does not run
 * the read within five seconds, plus the settle time, gets the client a `504` with
 * the code `main_thread_blocked`, so a client of an app that does not respond does
 * not wait for the request timeout.
 *
 * The tab has no console view: it stays out of the tab bar, and `_meta` lists it.
 * `GET /api/v1/screen/_schema` describes its routes.
 */
public class ScreenDebugTab private constructor(private val source: ScreenSource) : DebugTab() {
    override val id: String = "screen"
    override val title: String = "Screen"
    override val icon: String = "📱"
    override val order: Int = 60

    /** `false`: the tab has no console view yet. `_meta` lists it, and its routes answer. */
    override val showInTabBar: Boolean = false

    private val routes = ScreenRoutes(source)

    /** Serves the routes under `/api/v1/screen/`; the static `screen.openapi.json` describes them. */
    override fun handle(request: DebugRequest): DebugResponse? = routes.handle(request)

    /** Factory for [ScreenDebugTab]. */
    public companion object {
        /**
         * Creates a [ScreenDebugTab].
         *
         * @param context a context of the app. The tab registers activity lifecycle
         *   callbacks on its application, to find the activities' windows on a device
         *   where it can't list the windows of the process.
         * @param contextProvider returns what only the app knows about the screen, such
         *   as its navigation back stack, as JSON; the tree response carries it as
         *   `context`. It runs on the main thread.
         */
        @JvmStatic
        @JvmOverloads
        public fun create(context: Context, contextProvider: ScreenContextProvider? = null): ScreenDebugTab {
            val app = context.applicationContext ?: context
            val activities = ActivityTracker()
            (app as? Application)?.registerActivityLifecycleCallbacks(activities)
            return ScreenDebugTab(ScreenSource(app, activities, contextProvider))
        }

        /** Creates a tab over [source], which tests build with a timeout of their own. */
        internal fun create(source: ScreenSource): ScreenDebugTab = ScreenDebugTab(source)
    }
}
