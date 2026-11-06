package io.github.twinsen81.lustro.screen.internal

import android.annotation.SuppressLint
import android.os.Build
import android.view.View
import android.view.inspector.WindowInspector

/**
 * The root views of the app's windows, in the order the app added them, and how
 * the tab found them. Runs on the main thread, where the app adds and removes its
 * windows.
 *
 * From API 29, `WindowInspector` lists them. Before that, the tab reads the list
 * that `WindowManagerGlobal` keeps, by reflection, as Espresso does; when that
 * fails, it reads the decor views of the started activities, without their dialogs
 * and popups.
 */
internal class WindowRoots(private val activities: ActivityTracker) {
    @Volatile
    private var reflectionFailed = false

    fun roots(): Pair<List<View>, String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return WindowInspector.getGlobalWindowViews() to SOURCE_INSPECTOR
        if (!reflectionFailed) {
            val views = reflected()
            if (views != null) return views to SOURCE_REFLECTION
            reflectionFailed = true
        }
        return activities.decorViews() to SOURCE_ACTIVITIES
    }

    // WindowManagerGlobal and its mViews are hidden API, on the list that apps may use
    // before API 29, where the tab needs them; from API 29 it uses WindowInspector.
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun reflected(): List<View>? =
        try {
            val global = Class.forName("android.view.WindowManagerGlobal")
            val instance = global.getMethod("getInstance").invoke(null)
            val field = global.getDeclaredField("mViews").apply { isAccessible = true }
            (field.get(instance) as? List<*>)?.filterIsInstance<View>()
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        } catch (_: LinkageError) {
            null
        }

    companion object {
        const val SOURCE_INSPECTOR: String = "inspector"
        const val SOURCE_REFLECTION: String = "reflection"
        const val SOURCE_ACTIVITIES: String = "activities"
    }
}
