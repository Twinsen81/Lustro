package io.github.twinsen81.lustro.screen.internal

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.screen.ScreenContextProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Reads the app's windows for a request: on the main thread, where the app changes
 * its views, and waits for the read on the request's thread, at most
 * [mainThreadTimeoutMs] plus the settle time.
 */
internal class ScreenSource(
    private val context: Context,
    activities: ActivityTracker,
    private val contextProvider: ScreenContextProvider?,
    private val mainThreadTimeoutMs: Long = MAIN_THREAD_TIMEOUT_MS,
    composeAvailable: Boolean = ComposeSupport.isAvailable(ScreenSource::class.java.classLoader),
    private val windowFocus: (View) -> Boolean = View::hasWindowFocus,
) {
    private val roots = WindowRoots(activities)
    private val compose = if (composeAvailable) ComposeReader() else null
    private val reader = TreeReader(compose, windowFocus)
    private val main = Handler(Looper.getMainLooper())

    val composeAvailable: Boolean get() = compose != null

    /**
     * Reads the tree. With [settleMs] above zero, reads it on each frame until two
     * reads in a row are equal and the window focus is at rest, for up to [settleMs].
     *
     * @throws MainThreadBlocked when the main thread does not start the read in time.
     * @throws InterruptedException when the runtime gives up on the request.
     */
    fun tree(request: DebugRequest, merged: Boolean, settleMs: Int): ScreenTree {
        if (Looper.myLooper() == Looper.getMainLooper()) return readOnMainThread(merged, settleMs)
        return onMain(request, settleMs) { cancelled, complete ->
            if (settleMs > 0) {
                Settle(merged, settleMs, cancelled, complete).start()
            } else {
                complete(Result.success(finish(readNow(merged), merged, settle = null)))
            }
        }
    }

    /**
     * Runs the action of [action] on the node that its selector finds, in a read of the
     * unmerged tree, and reads the tree after it on each frame until two reads in a row
     * are equal, for up to its settle time, at least one frame.
     *
     * @throws ActionRefused when no node, or more than one, can take the action.
     * @throws ActionFailed when the app's action throws.
     * @throws MainThreadBlocked when the main thread does not start or finish in time.
     * @throws InterruptedException when the runtime gives up on the request.
     */
    fun act(request: DebugRequest, action: ActionRequest): ActionResult {
        val ran = AtomicReference<Pair<ActionTargets.Target, Boolean>>()
        val onMainThread = Looper.myLooper() == Looper.getMainLooper()
        val tree: ScreenTree =
            if (onMainThread) {
                ran.set(performOnMainThread(action))
                readOnMainThread(action.merged, action.settleMs)
            } else {
                onMain(request, action.settleMs + FOCUS_WAIT_MS.toInt()) { cancelled, complete ->
                    // A tap that just opened or closed a dialog has the window focus still on
                    // its way, and a Compose dialog comes only on the next frame: the action
                    // waits for both, so that it acts on the window on top.
                    FocusWait(cancelled, complete) {
                        ran.set(perform(action))
                        Settle(action.merged, action.settleMs, cancelled, complete).start()
                    }.start()
                }
            }
        val (target, handled) = ran.get()
        return ActionResult(action.action, target.matched.id, target.node.id, handled, tree)
    }

    // Only a test calls a tab on the main thread: the read after the action comes before a frame.
    private fun performOnMainThread(action: ActionRequest): Pair<ActionTargets.Target, Boolean> =
        try {
            perform(action)
        } catch (e: ActionRefused) {
            throw e
        } catch (e: ActionFailed) {
            throw e
        } catch (t: Throwable) {
            throw ReadFailed(t)
        }

    /** Finds the target and runs the action, on the main thread. */
    private fun perform(action: ActionRequest): Pair<ActionTargets.Target, Boolean> {
        val live = HashMap<String, Any>()
        val (views, source) = roots.roots()
        val read = reader.read(views, source, merged = false, live = live)
        val target = ActionTargets.resolve(read, action)
        val node = live[target.node.id] ?: throw ActionRefused(404, "no_match", "${target.node.id} left the screen during the read")
        val handled =
            try {
                if (node is View) {
                    ViewActions.perform(node, action.action)
                } else {
                    // Only a ComposeReader gives a node that is not a View.
                    (compose ?: error("No Compose reader for $node")).perform(node, action.action)
                }
            } catch (t: Throwable) {
                throw ActionFailed(target.node.id, action.action.needs, t)
            }
        return target to handled
    }

    /**
     * Runs [task] on the main thread, and waits for what it hands to `complete`, at most
     * [mainThreadTimeoutMs] plus [settleMs]. What the task throws, from a View of the app
     * among others, goes to this thread: on the main looper it would end the app's process.
     */
    private fun <T> onMain(request: DebugRequest, settleMs: Int, task: (cancelled: AtomicBoolean, complete: (Result<T>) -> Unit) -> Unit): T {
        val result = AtomicReference<Result<T>>()
        val started = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val done = CountDownLatch(1)
        request.onCancel { cancelled.set(true) }
        val complete = { outcome: Result<T> ->
            if (result.compareAndSet(null, outcome)) done.countDown()
        }
        main.post {
            if (cancelled.get()) return@post
            started.set(true)
            try {
                task(cancelled, complete)
            } catch (t: Throwable) {
                complete(Result.failure(t))
            }
        }
        if (!done.await(mainThreadTimeoutMs + settleMs, TimeUnit.MILLISECONDS)) {
            cancelled.set(true)
            throw MainThreadBlocked(if (started.get()) mainThreadTimeoutMs + settleMs else mainThreadTimeoutMs, started.get())
        }
        return result.get().getOrElse { failure ->
            throw when (failure) {
                is ActionRefused, is ActionFailed -> failure
                else -> ReadFailed(failure)
            }
        }
    }

    // Only a test calls a tab on the main thread; a frame can't come while it waits.
    private fun readOnMainThread(merged: Boolean, settleMs: Int): ScreenTree {
        val settle = if (settleMs > 0) SettleResult(settleMs, settled = false, waitedMs = 0, frames = 0) else null
        return try {
            finish(readNow(merged), merged, settle)
        } catch (t: Throwable) {
            throw ReadFailed(t)
        }
    }

    private fun readNow(merged: Boolean): ScreenWindows {
        val (views, source) = roots.roots()
        return reader.read(views, source, merged)
    }

    private fun finish(read: ScreenWindows, merged: Boolean, settle: SettleResult?): ScreenTree {
        var json: String? = null
        var error: String? = null
        if (contextProvider != null) {
            try {
                json = contextProvider.contextJson()?.let(::checkedJson)
            } catch (e: InvalidContext) {
                error = e.message
            } catch (t: Throwable) {
                // The app's code: an Error such as the one that TODO() throws is its error too.
                error = "The ScreenContextProvider threw $t"
            }
        }
        return ScreenTree(read, merged, context.resources.displayMetrics.density, composeAvailable, settle, json, error)
    }

    /** Whether the window focus has come to rest, from the windows' roots alone: as [ScreenWindows.focusSettled], without the nodes. */
    private fun focusAtRest(): Boolean {
        val views = roots.roots().first
        val groups = WindowFocus.groups(views)
        return WindowFocus.atRest(
            views.mapIndexed { index, view ->
                WindowFocus.Entry(
                    groups[index],
                    TreeReader.isShownWindow(view),
                    TreeReader.isFocusable(view),
                    windowFocus(view),
                    TreeReader.isPendingWindow(view),
                )
            },
        )
    }

    /**
     * Runs [next] on the first frame on which the window focus is at rest, or after
     * [FOCUS_WAIT_MS]. Never before a frame: a tap that the app has handled can add its
     * window on the next one, as a Compose dialog does. Runs on the main thread.
     */
    private inner class FocusWait(
        private val cancelled: AtomicBoolean,
        private val complete: (Result<ScreenTree>) -> Unit,
        private val next: () -> Unit,
    ) : Choreographer.FrameCallback {
        private val startedAt = SystemClock.uptimeMillis()

        fun start() {
            Choreographer.getInstance().postFrameCallback(this)
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (cancelled.get()) return
            try {
                if (focusAtRest() || SystemClock.uptimeMillis() - startedAt >= FOCUS_WAIT_MS) {
                    next()
                } else {
                    Choreographer.getInstance().postFrameCallback(this)
                }
            } catch (t: Throwable) {
                // A frame callback that throws would end the app's process.
                complete(Result.failure(t))
            }
        }
    }

    /**
     * Reads on each frame until two reads in a row are equal and the window focus is at
     * rest, or the time is over. Runs on the main thread.
     */
    private inner class Settle(
        private val merged: Boolean,
        private val settleMs: Int,
        private val cancelled: AtomicBoolean,
        private val complete: (Result<ScreenTree>) -> Unit,
    ) : Choreographer.FrameCallback {
        private val startedAt = SystemClock.uptimeMillis()
        private var last: ScreenWindows? = null
        private var frames = 0

        fun start() {
            last = readNow(merged)
            Choreographer.getInstance().postFrameCallback(this)
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (cancelled.get()) return
            try {
                frames++
                val read = readNow(merged)
                val waited = SystemClock.uptimeMillis() - startedAt
                // Equal reads before the window focus has moved are not at rest: the next
                // frames move it, and a client would act on the window that loses it.
                val settled = read == last && read.focusSettled
                if (settled || waited >= settleMs) {
                    complete(Result.success(finish(read, merged, SettleResult(settleMs, settled, waited, frames))))
                } else {
                    last = read
                    Choreographer.getInstance().postFrameCallback(this)
                }
            } catch (t: Throwable) {
                // A frame callback that throws would end the app's process.
                complete(Result.failure(t))
            }
        }
    }

    /**
     * The provider's JSON, written again strictly, or [InvalidContext] when it is not one
     * JSON value. Android's parser takes some text that is not JSON, such as a name
     * without quotes; writing the parsed value again makes the response valid JSON.
     */
    private fun checkedJson(text: String): String {
        val tokener = JSONTokener(text)
        val value =
            try {
                tokener.nextValue()
            } catch (e: JSONException) {
                notJson(e.message)
            }
        if (tokener.more()) notJson("more than one value")
        return when (value) {
            is JSONObject, is JSONArray -> value.toString()
            JSONObject.NULL -> "null"
            is Boolean -> value.toString()
            is Number -> JSONObject.numberToString(value)
            // An unquoted word parses as a string: it is not JSON.
            is String -> if (text.trimStart().startsWith('"')) JSONObject.quote(value) else notJson("a word without quotes")
            else -> notJson(null)
        }
    }

    private fun notJson(reason: String?): Nothing =
        throw InvalidContext("The ScreenContextProvider returned text that is not JSON" + (reason?.let { ": $it" } ?: ""))

    private class InvalidContext(message: String) : Exception(message)

    /** The app's [action] on the node [target] threw [cause]. */
    class ActionFailed(val target: String, val action: String, cause: Throwable) : Exception("The $action action of $target threw $cause", cause)

    /** The read threw [cause] on the main thread, such as a View of the app whose getter throws. */
    class ReadFailed(cause: Throwable) : Exception("The read of the windows threw $cause", cause)

    /** The main thread did not start the read, or did not finish it, in [waitedMs]. */
    class MainThreadBlocked(val waitedMs: Long, val started: Boolean) :
        Exception("The main thread did not ${if (started) "finish" else "start"} the read in $waitedMs ms")

    companion object {
        /** How long a request waits for the main thread to read the windows, without the settle time. */
        const val MAIN_THREAD_TIMEOUT_MS: Long = 5_000

        /** The longest settle that a request may ask for. */
        const val MAX_SETTLE_MS: Int = 10_000

        /**
         * How long an action waits for the window focus to come to rest before it finds its
         * node: on a physical device the focus reaches a new dialog 170 to 240 ms after the
         * tap that opened it. A window of another app on top keeps it away the whole time.
         */
        const val FOCUS_WAIT_MS: Long = 500
    }
}
