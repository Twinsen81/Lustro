package io.github.twinsen81.lustro.work.internal

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** What the tab needs from the app's WorkManager, checked without calling it. */
internal object WorkSupport {
    /** The first WorkManager release with every call that the tab makes. */
    const val MIN_VERSION: String = "2.12.0"

    /**
     * Why the tab can't read the app's WorkManager, or `null` when it can: the app
     * has WorkManager [MIN_VERSION] or newer. Asks the class loader, so an app
     * without WorkManager, or with an older one, never runs a call that is not there.
     */
    fun missing(loader: ClassLoader?): String? =
        when {
            !loads("androidx.work.WorkManager", loader) -> "The app has no WorkManager (androidx.work:work-runtime)"
            // The listeners and WorkInfo.workerClassName came in 2.12.0, together.
            !loads("androidx.work.ExecutionEventListener", loader) -> "The tab needs WorkManager $MIN_VERSION or newer; the app has an older one"
            else -> null
        }

    /**
     * The methods of the listener interface [listener] that [wrapper] does not
     * implement itself, by name. A WorkManager newer than this tab can add one: an
     * abstract one would make the wrapper incomplete, and a default one would run
     * WorkManager's empty default instead of the app's listener. Either way the
     * tab must not install the wrapper.
     */
    fun unknownMethods(listener: Class<*>, wrapper: Class<*>): List<String> =
        listener.methods
            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .filterNot { implements(wrapper, it) }
            .map { it.name }
            .sorted()

    private fun implements(wrapper: Class<*>, method: Method): Boolean =
        wrapper.declaredMethods.any {
            it.name == method.name && it.parameterTypes.contentEquals(method.parameterTypes) && !Modifier.isAbstract(it.modifiers)
        }

    private fun loads(name: String, loader: ClassLoader?): Boolean =
        try {
            Class.forName(name, false, loader)
            true
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: LinkageError) {
            false
        }
}
