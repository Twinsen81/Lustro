package io.github.twinsen81.lustro.screen.internal

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
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
) {
    private val roots = WindowRoots(activities)
    private val compose = if (composeAvailable) ComposeReader() else null
    private val reader = TreeReader(compose)
    private val main = Handler(Looper.getMainLooper())

    val composeAvailable: Boolean get() = compose != null

    /**
     * Reads the tree. With [settleMs] above zero, reads it on each frame until two
     * reads in a row are equal, for up to [settleMs].
     *
     * @throws MainThreadBlocked when the main thread does not start the read in time.
     * @throws InterruptedException when the runtime gives up on the request.
     */
    fun tree(request: DebugRequest, merged: Boolean, settleMs: Int): ScreenTree {
        if (Looper.myLooper() == Looper.getMainLooper()) return readOnMainThread(merged, settleMs)
        val result = AtomicReference<Result<ScreenTree>>()
        val started = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val done = CountDownLatch(1)
        request.onCancel { cancelled.set(true) }
        val complete = { outcome: Result<ScreenTree> ->
            result.set(outcome)
            done.countDown()
        }
        main.post {
            if (cancelled.get()) return@post
            started.set(true)
            // What the read throws, from a View of the app among others, goes to the
            // request's thread: on the main looper it would end the app's process.
            contained(complete) {
                if (settleMs > 0) {
                    Settle(merged, settleMs, cancelled, complete).start()
                } else {
                    complete(Result.success(finish(readNow(merged), merged, settle = null)))
                }
            }
        }
        if (!done.await(mainThreadTimeoutMs + settleMs, TimeUnit.MILLISECONDS)) {
            cancelled.set(true)
            throw MainThreadBlocked(if (started.get()) mainThreadTimeoutMs + settleMs else mainThreadTimeoutMs, started.get())
        }
        return result.get().getOrElse { throw ReadFailed(it) }
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

    private inline fun contained(noinline complete: (Result<ScreenTree>) -> Unit, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            complete(Result.failure(t))
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

    /** Reads on each frame until two reads in a row are equal, or the time is over. Runs on the main thread. */
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
            contained(complete) {
                frames++
                val read = readNow(merged)
                val waited = SystemClock.uptimeMillis() - startedAt
                val settled = read == last
                if (settled || waited >= settleMs) {
                    complete(Result.success(finish(read, merged, SettleResult(settleMs, settled, waited, frames))))
                } else {
                    last = read
                    Choreographer.getInstance().postFrameCallback(this)
                }
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
    }
}
