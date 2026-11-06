package io.github.twinsen81.lustro.screen.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import java.lang.ref.WeakReference

/**
 * The started activities of the app, for a device where the tab can't list the
 * windows of the process: their decor views are the windows that it reads then. It
 * misses dialogs and popups, which have windows of their own. The callbacks and
 * [decorViews] run on the main thread.
 */
internal class ActivityTracker : Application.ActivityLifecycleCallbacks {
    private val started = ArrayList<WeakReference<Activity>>()

    fun decorViews(): List<View> {
        started.removeAll { it.get() == null }
        return started.mapNotNull { it.get()?.window?.peekDecorView() }
    }

    override fun onActivityStarted(activity: Activity) {
        started.removeAll { it.get() == null || it.get() === activity }
        started.add(WeakReference(activity))
    }

    override fun onActivityStopped(activity: Activity) {
        started.removeAll { it.get() == null || it.get() === activity }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
