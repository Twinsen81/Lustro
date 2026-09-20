@file:Suppress("TooGenericExceptionCaught")

package io.github.twinsen81.lustro.internal.network

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs capture work on one background thread, so an app's HTTP call returns as
 * soon as its bytes are read rather than after they're redacted, classified,
 * and stored.
 *
 * Tasks run in the order they're submitted, so a transaction's captures are
 * stored in the order they were reported, and a response never lands before
 * its request.
 *
 * Once more than [maxBacklogChars] of captured text is waiting, a task whose
 * transaction has nothing queued runs right away on the caller's thread. A
 * burst then can't grow the queue, and a heavy sync on several threads
 * redacts in parallel instead of waiting on this one. Such a request can be
 * listed ahead of earlier ones still queued. A task whose transaction does
 * have something queued still waits its turn. Past four times the limit,
 * which in practice means the thread is stuck, say on a custom Redactor that
 * hangs, those tasks are dropped instead.
 *
 * A task that throws is logged and its capture dropped. Nothing reaches the
 * app's call, and the thread keeps running.
 */
internal class CaptureWorker(
    private val executor: Executor = newCaptureExecutor(),
    private val maxBacklogChars: Long = DEFAULT_MAX_BACKLOG_CHARS,
) {
    private val backlogChars = AtomicLong()

    // Queued tasks per transaction id. A transaction with none has nothing to wait for.
    private val queuedTasks = ConcurrentHashMap<String, Int>()

    // Logs the stall once per stretch of drops instead of once per capture.
    @Volatile
    private var dropping = false

    /**
     * Runs [task], a capture for [transactionId] carrying [chars] of captured
     * text, after the tasks already queued, or right away if it's behind.
     */
    fun submit(transactionId: String, chars: Long, task: () -> Unit) {
        val weight = chars + TASK_OVERHEAD_CHARS
        val backlog = backlogChars.get()
        when {
            backlog == 0L || backlog + weight <= maxBacklogChars -> enqueue(transactionId, weight, task)
            !queuedTasks.containsKey(transactionId) -> runContained(task)
            backlog <= maxBacklogChars * STALLED_FACTOR -> enqueue(transactionId, weight, task)
            else -> drop()
        }
    }

    /** Waits up to [timeoutMs] for everything queued so far to run. For tests. */
    fun awaitIdle(timeoutMs: Long): Boolean {
        val done = CountDownLatch(1)
        executor.execute { done.countDown() }
        return done.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun enqueue(transactionId: String, weight: Long, task: () -> Unit) {
        backlogChars.addAndGet(weight)
        queuedTasks.merge(transactionId, 1, Int::plus)
        try {
            executor.execute {
                try {
                    runContained(task)
                } finally {
                    dequeued(transactionId, weight)
                }
            }
            dropping = false
        } catch (t: Throwable) {
            // Starting the thread can fail under memory or thread pressure.
            dequeued(transactionId, weight)
            logCaptureFailure("Could not queue a capture; dropped it", t)
        }
    }

    private fun dequeued(transactionId: String, weight: Long) {
        queuedTasks.computeIfPresent(transactionId) { _, count -> (count - 1).takeIf { it > 0 } }
        backlogChars.addAndGet(-weight)
    }

    private fun runContained(task: () -> Unit) {
        try {
            task()
        } catch (t: Throwable) {
            logCaptureFailure("A capture failed; dropped it", t)
        }
    }

    private fun drop() {
        if (dropping) return
        dropping = true
        try {
            Log.w(CAPTURE_TAG, "The capture thread is stuck; dropping captures until it catches up")
        } catch (_: Throwable) {
            // Logging must not fail the app's call.
        }
    }

    private companion object {
        // About 16 bodies at the default 256 KB capture cap.
        private const val DEFAULT_MAX_BACKLOG_CHARS = 4L * 1024 * 1024

        private const val STALLED_FACTOR = 4

        // Counts the URL and headers of a task with no body, so a flood of those
        // still fills the backlog.
        private const val TASK_OVERHEAD_CHARS = 1024L

        private fun newCaptureExecutor(): Executor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "lustro-capture").apply { isDaemon = true }
            }
    }
}

internal const val CAPTURE_TAG = "LustroCapture"

/**
 * Logs a capture failure by exception class only, since a redactor's exception
 * message can quote the captured text it failed on. Never throws.
 */
internal fun logCaptureFailure(message: String, t: Throwable) {
    try {
        Log.w(CAPTURE_TAG, "$message: ${t.javaClass.name}")
    } catch (_: Throwable) {
        // Logging must not fail the app's call or stop the capture thread.
    }
}
