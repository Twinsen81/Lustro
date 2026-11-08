package io.github.twinsen81.lustro.screen

/**
 * What only the app knows about the screen that it shows, for the Screen tab: for
 * example its navigation back stack, the current route, or the state of the top
 * screen's ViewModel.
 *
 * The tab calls [contextJson] on the main thread, after it reads the windows, and
 * puts the value into the tree response as `context`. Keep it fast: the tree waits
 * for it.
 */
public fun interface ScreenContextProvider {
    /**
     * Returns a JSON value, usually an object such as `{"route":"settings"}`, or
     * `null` for none. A value that is not JSON, or an exception, puts `null` into
     * `context` and the reason into `contextError`.
     */
    public fun contextJson(): String?
}
