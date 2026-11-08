package io.github.twinsen81.lustro.screen.internal

/**
 * Whether the app has Compose UI. This object names no Compose type, so an app without
 * Compose can initialize it: the tab creates a [ComposeReader], which does, only when
 * [isAvailable] says yes.
 */
internal object ComposeSupport {
    /** Asks the class loader, without initializing the class, so that a Compose class runs nothing. */
    fun isAvailable(loader: ClassLoader?): Boolean =
        try {
            Class.forName("androidx.compose.ui.platform.ViewRootForTest", false, loader)
            true
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: LinkageError) {
            false
        }
}
