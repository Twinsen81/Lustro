package io.github.twinsen81.lustro.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure-JVM tests for [RequestLimiter]: concurrency + queue
 * saturation rejects, the per-request timeout interrupts the worker, and a
 * thrown handler propagates so the server can map it to a 500.
 */
class RequestLimiterTest {
    private val limiters = mutableListOf<RequestLimiter>()

    private fun limiter(maxConcurrent: Int, queueCapacity: Int, timeoutMs: Long): RequestLimiter =
        RequestLimiter(maxConcurrent, queueCapacity, timeoutMs).also { limiters.add(it) }

    @org.junit.After
    fun tearDown() {
        limiters.forEach { it.shutdown() }
    }

    @Test
    fun `completed work returns its value`() {
        val limiter = limiter(maxConcurrent = 2, queueCapacity = 2, timeoutMs = 1000)
        val outcome = limiter.dispatch { 42 }
        assertTrue(outcome is RequestLimiter.Outcome.Completed)
        assertEquals(42, (outcome as RequestLimiter.Outcome.Completed).value)
    }

    @Test
    fun `concurrency and queue saturation rejects the overflow request`() {
        val limiter = limiter(maxConcurrent = 1, queueCapacity = 1, timeoutMs = 5000)
        val release = CountDownLatch(1)
        val firstEntered = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(3)
        val rejected = AtomicInteger(0)
        val completed = AtomicInteger(0)
        val done = CountDownLatch(3)

        // Worker 1 holds the single permit; worker 2 occupies the single queue slot;
        // worker 3 must be rejected (queue full AND no free permit).
        pool.submit {
            limiter.dispatch {
                firstEntered.countDown()
                release.await()
            }.also { if (it is RequestLimiter.Outcome.Completed) completed.incrementAndGet() }
            done.countDown()
        }
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS))

        // Worker 2: blocks on the permit (queue slot reserved).
        pool.submit {
            val o = limiter.dispatch { release.await() }
            if (o is RequestLimiter.Outcome.Rejected) rejected.incrementAndGet()
            if (o is RequestLimiter.Outcome.Completed) completed.incrementAndGet()
            done.countDown()
        }
        // Let worker 2 reach the wait state (queue full now).
        Thread.sleep(200)

        // Worker 3: queue full + permit busy -> immediate reject.
        pool.submit {
            val o = limiter.dispatch { release.await() }
            if (o is RequestLimiter.Outcome.Rejected) rejected.incrementAndGet()
            if (o is RequestLimiter.Outcome.Completed) completed.incrementAndGet()
            done.countDown()
        }
        Thread.sleep(200)
        release.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertEquals("exactly one request rejected", 1, rejected.get())
        assertEquals("the other two complete", 2, completed.get())
    }

    @Test
    fun `work exceeding the timeout returns TimedOut and interrupts the worker`() {
        val limiter = limiter(maxConcurrent = 1, queueCapacity = 1, timeoutMs = 150)
        val interrupted = AtomicInteger(0)
        val outcome =
            limiter.dispatch {
                try {
                    Thread.sleep(5000)
                } catch (_: InterruptedException) {
                    interrupted.incrementAndGet()
                }
            }
        assertTrue(outcome is RequestLimiter.Outcome.TimedOut)
        // Give the interrupt a moment to land on the worker thread.
        Thread.sleep(200)
        assertEquals("the worker thread was interrupted", 1, interrupted.get())
    }

    @Test(expected = IllegalStateException::class)
    fun `a thrown handler propagates out of dispatch`() {
        val limiter = limiter(maxConcurrent = 1, queueCapacity = 1, timeoutMs = 1000)
        limiter.dispatch { error("boom") }
    }

    @Test
    fun `beginDrain rejects new work`() {
        val limiter = limiter(maxConcurrent = 1, queueCapacity = 1, timeoutMs = 1000)
        limiter.beginDrain()
        assertTrue(limiter.dispatch { 1 } is RequestLimiter.Outcome.Rejected)
        limiter.reopen()
        assertTrue(limiter.dispatch { 1 } is RequestLimiter.Outcome.Completed)
    }
}
