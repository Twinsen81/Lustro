package io.github.twinsen81.lustro.sample

/**
 * Where the app is, for the Screen tab's context: the screen that shows, the screens
 * under it, and whether the result history is open. [SampleApp] sets it on the main
 * thread after each composition, and the debug bootstrap's context provider reads it
 * there, so no lock is needed.
 */
internal object SampleNavigation {
    private var screen: String = "home"
    private var historyOpen: Boolean = false

    fun update(screen: String, historyOpen: Boolean) {
        this.screen = screen
        this.historyOpen = historyOpen
    }

    /** The navigation state as JSON: `{"screen":"work","stack":["home","work"],"historyOpen":false}`. */
    fun contextJson(): String {
        val stack = if (screen == "home") listOf("home") else listOf("home", screen)
        return "{\"screen\":\"$screen\",\"stack\":[${stack.joinToString(",") { "\"$it\"" }}],\"historyOpen\":$historyOpen}"
    }
}
