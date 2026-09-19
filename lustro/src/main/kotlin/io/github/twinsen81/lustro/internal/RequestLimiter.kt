@file:Suppress("TooGenericExceptionCaught")

package io.github.twinsen81.lustro.internal

import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounds the server's request dispatch. Three independent
 * limits, all enforced in the request path because NanoHTTPD provides none of
 * them:
 *
 * - **Concurrency** — a fair [Semaphore] of [maxConcurrent] permits caps the
 *   number of requests executing at once. A request keeps its permit until its
 *   work returns, even past the timeout, so the permits cap the work actually
 *   running.
 * - **Queue** — an [AtomicInteger] tracks requests *waiting* for a permit. When
 *   `active == maxConcurrent` AND `waiting == queueCapacity`, a new request is
 *   rejected immediately with [Outcome.Rejected] (→ enveloped 503) instead of
 *   blocking unboundedly. Otherwise it waits for a permit, for at most
 *   [timeoutMs]: work that ignores its timeout keeps its permit, and the
 *   requests queued behind it must not wait forever.
 * - **Timeout** — the routed work runs on a worker thread; the caller blocks on
 *   it for at most [timeoutMs]. On timeout the work is CANCELLED (its
 *   [Cancellation] actions run and the worker thread is interrupted) and
 *   [Outcome.TimedOut] (→ 504) is returned; the work stops cooperatively.
 *
 * A pooled, daemon [ThreadPoolExecutor] runs the workers. Its size is uncapped
 * because the [Semaphore] already caps the work running at once; idle threads
 * die after a short keep-alive.
 *
 * The whole class is test-driven: counters are exposed and the limits come from
 * the constructor so unit tests can set tiny caps and assert overflow/timeout.
 */
internal class RequestLimiter(
    private val maxConcurrent: Int,
    private val queueCapacity: Int,
    private val timeoutMs: Long,
) {
    private val permits = Semaphore(maxConcurrent.coerceAtLeast(1), /* fair = */ true)
    private val active = AtomicInteger(0)
    private val waiting = AtomicInteger(0)

    private val workers: ThreadPoolExecutor =
        ThreadPoolExecutor(
            /* corePoolSize = */ 0,
            /* maximumPoolSize = */ Int.MAX_VALUE,
            /* keepAliveTime = */ KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            ThreadFactory { runnable ->
                Thread(runnable, "lustro-request-worker").apply { isDaemon = true }
            },
        )

    private val running: MutableSet<Task<*>> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var shuttingDown = false

    /**
     * Number of requests currently holding a permit: being served, or timed out
     * with their work still running.
     */
    fun activeCount(): Int = active.get()

    /** Number of requests currently blocked waiting for a permit. */
    fun waitingCount(): Int = waiting.get()

    /**
     * Stops accepting new work: subsequent [dispatch] calls short-circuit to
     * [Outcome.Rejected]. In-flight workers keep running (the drain in the
     * lifecycle waits for [activeCount] to reach 0). Idempotent.
     */
    fun beginDrain() {
        shuttingDown = true
    }

    /**
     * Re-opens dispatch after a [beginDrain], clearing the drained state so new
     * work is admitted again. The production rebind path (Lustro foreground)
     * constructs a fresh [LustroServer] — and thus a fresh limiter — per bind, so
     * this is currently exercised only by tests; it stays as the symmetric
     * counterpart to [beginDrain] for any caller that reuses a limiter instance.
     */
    fun reopen() {
        shuttingDown = false
    }

    /**
     * Stops accepting work, cancels the work still running, and releases the
     * worker pool. Call once the server is permanently stopped.
     */
    fun shutdown() {
        shuttingDown = true
        workers.shutdown()
        running.forEach(::cancel)
    }

    /**
     * Admits the request through the concurrency/queue gate, runs [work] on a
     * worker thread under the per-request [timeoutMs], and returns the typed
     * [Outcome]. [work] gets the [Cancellation] the limiter fires when it stops
     * waiting for it. The caller maps each non-[Outcome.Completed] outcome to its
     * enveloped error response. Whatever [work] throws, Errors included, is
     * rethrown on the calling thread, so the caller must catch [Throwable].
     *
     * [onRelease] runs exactly once, when the request no longer holds a permit:
     * right away if it's turned away before [work] runs, otherwise once [work]
     * returns, which can be after a timeout. Callers use it to free what the
     * request holds for as long as its permit.
     */
    fun <T> dispatch(onRelease: () -> Unit = {}, work: (Cancellation) -> T): Outcome<T> {
        if (shuttingDown) return rejected(onRelease)

        // Reserve a queue slot. We may take a permit immediately (no real wait),
        // but reserving up-front lets us reject the moment the queue is full
        // without blocking. The reservation is released as soon as we either get
        // a permit or bail out.
        val reserved = reserveQueueSlot() ?: return rejected(onRelease)
        val permitAcquired =
            try {
                permits.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            } finally {
                // Once past acquire() (or interrupted), we are no longer waiting.
                if (reserved) waiting.decrementAndGet()
            }
        if (!permitAcquired) return rejected(onRelease)

        active.incrementAndGet()
        val cancellation = Cancellation()
        val task = Task(cancellation, Callable { work(cancellation) }, onRelease)
        running.add(task)
        var submitted = false
        try {
            workers.execute(task)
            submitted = true
        } catch (_: RejectedExecutionException) {
            // Pool was shut down (drain/stop) between the gate and submit.
            return Outcome.Rejected
        } finally {
            if (!submitted) task.release()
        }
        return await(task)
    }

    private fun rejected(onRelease: () -> Unit): Outcome<Nothing> {
        onRelease()
        return Outcome.Rejected
    }

    /**
     * Atomically reserves a waiting slot when there is room. Returns `true` when a
     * slot was reserved (caller must decrement [waiting] once done waiting), or
     * `null` when the queue is full AND every permit is in use (→ reject 503).
     */
    private fun reserveQueueSlot(): Boolean? {
        while (true) {
            val currentWaiting = waiting.get()
            // Room to wait if the queue isn't full. When it IS full, only reject if
            // no permit is currently free (otherwise this request would proceed
            // straight through without really queueing).
            if (currentWaiting >= queueCapacity && permits.availablePermits() == 0) {
                return null
            }
            if (waiting.compareAndSet(currentWaiting, currentWaiting + 1)) {
                return true
            }
        }
    }

    private fun <T> await(task: Task<T>): Outcome<T> =
        try {
            Outcome.Completed(task.get(timeoutMs, TimeUnit.MILLISECONDS))
        } catch (_: TimeoutException) {
            cancel(task)
            Outcome.TimedOut
        } catch (_: InterruptedException) {
            cancel(task)
            Thread.currentThread().interrupt()
            Outcome.TimedOut
        } catch (_: CancellationException) {
            // shutdown() cancelled the work while we waited for it.
            Outcome.Rejected
        } catch (e: ExecutionException) {
            // The work threw; rethrow so the server's Throwable guard maps it to 500.
            throw e.cause ?: e
        } finally {
            // Work that returned frees its permit now, before the response goes
            // out. Cancelled work frees it on its own thread once it returns.
            if (task.isDone && !task.isCancelled) task.release()
        }

    private fun cancel(task: Task<*>) {
        if (task.isDone) return
        task.cancellation.cancel()
        task.cancel(/* mayInterruptIfRunning = */ true)
    }

    /**
     * Lets dispatched work react when the limiter stops waiting for it: on its
     * timeout, or at [shutdown].
     */
    class Cancellation {
        // Null once cancelled. Guarded by this.
        private var actions: MutableList<Runnable>? = ArrayList(1)

        /**
         * Runs [action] once the work is cancelled, or right away if it already
         * is. Anything [action] throws is ignored, so it can't stop the 504.
         */
        fun onCancel(action: Runnable) {
            synchronized(this) {
                val pending = actions
                if (pending != null) {
                    pending.add(action)
                    return
                }
            }
            runIgnoringFailure(action)
        }

        fun cancel() {
            val pending = synchronized(this) { actions.also { actions = null } } ?: return
            pending.forEach(::runIgnoringFailure)
        }

        private fun runIgnoringFailure(action: Runnable) {
            try {
                action.run()
            } catch (_: Throwable) {
                // Callers that care log it themselves.
            }
        }
    }

    // Holds its request's permit until the work returns, even when the caller
    // stopped waiting. run() releases it even when shutdown() cancelled the task
    // before it started and the work never ran.
    private inner class Task<T>(
        val cancellation: Cancellation,
        work: Callable<T>,
        private val onRelease: () -> Unit,
    ) : FutureTask<T>(work) {
        private val released = AtomicBoolean(false)

        override fun run() {
            try {
                super.run()
            } finally {
                release()
            }
        }

        fun release() {
            if (!released.compareAndSet(false, true)) return
            running.remove(this)
            active.decrementAndGet()
            permits.release()
            onRelease()
        }
    }

    /** Typed result of a [dispatch] call. */
    sealed interface Outcome<out T> {
        /** [work] ran to completion; [value] is its result. */
        data class Completed<T>(val value: T) : Outcome<T>

        /** Concurrency + queue saturated, or shutting down → caller maps to enveloped 503. */
        data object Rejected : Outcome<Nothing>

        /** [work] exceeded the per-request timeout → caller maps to enveloped 504. */
        data object TimedOut : Outcome<Nothing>
    }

    private companion object {
        private const val KEEP_ALIVE_SECONDS = 30L
    }
}
