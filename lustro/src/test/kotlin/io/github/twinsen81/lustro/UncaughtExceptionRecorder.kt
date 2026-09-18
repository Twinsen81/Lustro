package io.github.twinsen81.lustro

import org.junit.Assert.assertTrue
import org.junit.rules.ExternalResource
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Replaces the default uncaught-exception handler for the duration of a test and
 * records what reaches it. On Android that handler kills the process, so a
 * throwable recorded here is one that would crash the host app.
 */
class UncaughtExceptionRecorder : ExternalResource() {
    private val recorded = CopyOnWriteArrayList<String>()
    private var previous: Thread.UncaughtExceptionHandler? = null

    override fun before() {
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            recorded += "${thread.name}: $throwable"
        }
    }

    override fun after() {
        Thread.setDefaultUncaughtExceptionHandler(previous)
    }

    fun assertNothingUncaught() {
        assertTrue("reached the default uncaught-exception handler: $recorded", recorded.isEmpty())
    }
}
