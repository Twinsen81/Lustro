package io.github.twinsen81.lustro.internal

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounds the server's request dispatch. Three independent
 * limits, all enforced in the request path because NanoHTTPD provides none of
 * them:
 *
 * - **Concurrency** — a fair [Semaphore] of [maxConcurrent] permits caps the
 *   number of requests executing at once.
 * - **Queue** — an [AtomicInteger] tracks requests *waiting* for a permit. When
 *   `active == maxConcurrent` AND `waiting == queueCapacity`, a new request is
 *   rejected immediately with [Outcome.Rejected] (→ enveloped 503) instead of
 *   blocking unboundedly. Otherwise it waits for a permit.
 * - **Timeout** — the routed work runs on a worker thread; the caller blocks on
 *   the [Future] for at most [timeoutMs]. On timeout the worker thread is
 *   INTERRUPTED ([Future.cancel] `true`) and [Outcome.TimedOut] (→ 504) is
 *   returned; handler cleanup is cooperative.
 *
 * A pooled, daemon [ThreadPoolExecutor] runs the workers. It is unbounded in the
 * sense that the [Semaphore] already caps how many tasks can be submitted at
 * once (active permits == in-flight workers), so the pool never grows past
 * [maxConcurrent] live workers; idle threads die after a short keep-alive.
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

    @Volatile
    private var shuttingDown = false

    /** Number of requests currently holding a permit (executing). */
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

    /** Releases the worker pool. Call once the server is permanently stopped. */
    fun shutdown() {
        shuttingDown = true
        workers.shutdownNow()
    }

    /**
     * Admits the request through the concurrency/queue gate, runs [work] on a
     * worker thread under the per-request [timeoutMs], and returns the typed
     * [Outcome]. The caller maps each non-[Outcome.Completed] outcome to its
     * enveloped error response.
     */
    fun <T> dispatch(work: () -> T): Outcome<T> {
        if (shuttingDown) return Outcome.Rejected

        // Reserve a queue slot. We may take a permit immediately (no real wait),
        // but reserving up-front lets us reject the moment the queue is full
        // without blocking. The reservation is released as soon as we either get
        // a permit or bail out.
        val reserved = reserveQueueSlot() ?: return Outcome.Rejected
        val permitAcquired =
            try {
                permits.acquire()
                true
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            } finally {
                // Once past acquire() (or interrupted), we are no longer waiting.
                if (reserved) waiting.decrementAndGet()
            }
        if (!permitAcquired) return Outcome.Rejected

        active.incrementAndGet()
        return try {
            runWithTimeout(work)
        } finally {
            active.decrementAndGet()
            permits.release()
        }
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

    private fun <T> runWithTimeout(work: () -> T): Outcome<T> {
        val future: Future<T> =
            try {
                workers.submit(Callable { work() })
            } catch (_: Exception) {
                // Pool was shut down (drain/stop) between the gate and submit.
                return Outcome.Rejected
            }
        return try {
            Outcome.Completed(future.get(timeoutMs, TimeUnit.MILLISECONDS))
        } catch (_: TimeoutException) {
            future.cancel(/* mayInterruptIfRunning = */ true)
            Outcome.TimedOut
        } catch (_: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            Outcome.TimedOut
        } catch (e: ExecutionException) {
            // The work threw; rethrow so the server's own try/catch maps it to 500.
            throw e.cause ?: e
        }
    }

    /** Typed result of a [dispatch] call. */
    sealed interface Outcome<out T> {
        /** [work] ran to completion; [value] is its result. */
        data class Completed<T>(val value: T) : Outcome<T>

        /** Concurrency + queue both saturated → caller maps to enveloped 503. */
        data object Rejected : Outcome<Nothing>

        /** [work] exceeded the per-request timeout → caller maps to enveloped 504. */
        data object TimedOut : Outcome<Nothing>
    }

    private companion object {
        private const val KEEP_ALIVE_SECONDS = 30L
    }
}
