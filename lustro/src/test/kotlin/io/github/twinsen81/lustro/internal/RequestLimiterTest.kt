package io.github.twinsen81.lustro.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Pure-JVM tests for [RequestLimiter]: concurrency + queue
 * saturation rejects, the per-request timeout cancels and interrupts the work,
 * which keeps its permit until it returns, and a thrown handler, Errors
 * included, propagates so the server can map it to a 500.
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
    fun `an Error thrown by the work propagates and frees the permit`() {
        // No queue: if the failed dispatch kept its permit, the next one would be rejected.
        val limiter = limiter(maxConcurrent = 1, queueCapacity = 0, timeoutMs = 1000)
        assertThrows(NotImplementedError::class.java) { limiter.dispatch { TODO("boom") } }
        assertTrue(limiter.dispatch { 1 } is RequestLimiter.Outcome.Completed)
    }

    @Test
    fun `a timed-out handler keeps its permit until it returns`() {
        val limiter = limiter(maxConcurrent = 1, queueCapacity = 0, timeoutMs = 100)
        val release = CountDownLatch(1)

        val outcome = limiter.dispatch { awaitIgnoringInterrupts(release) }

        assertTrue(outcome is RequestLimiter.Outcome.TimedOut)
        assertEquals("the handler still holds the permit", 1, limiter.activeCount())
        assertTrue(limiter.dispatch { 42 } is RequestLimiter.Outcome.Rejected)

        release.countDown()
        awaitTrue("the permit is freed once the handler returns") { limiter.activeCount() == 0 }
        assertTrue(limiter.dispatch { 42 } is RequestLimiter.Outcome.Completed)
    }

    @Test
    fun `a request queued behind a timed-out handler gives up after the timeout`() {
        val limiter = limiter(maxConcurrent = 1, queueCapacity = 1, timeoutMs = 100)
        val release = CountDownLatch(1)
        try {
            assertTrue(limiter.dispatch { awaitIgnoringInterrupts(release) } is RequestLimiter.Outcome.TimedOut)

            val started = System.nanoTime()
            val outcome = limiter.dispatch { 42 }
            val waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

            assertEquals(RequestLimiter.Outcome.Rejected, outcome)
            assertTrue("waited for the permit first (${waitedMs}ms)", waitedMs >= 90)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `timing out cancels the work so it can stop`() {
        val limiter = limiter(maxConcurrent = 1, queueCapacity = 0, timeoutMs = 100)
        val cancelled = CountDownLatch(1)
        val returned = CountDownLatch(1)

        val outcome =
            limiter.dispatch { cancellation ->
                cancellation.onCancel { cancelled.countDown() }
                awaitIgnoringInterrupts(cancelled)
                returned.countDown()
            }

        assertTrue(outcome is RequestLimiter.Outcome.TimedOut)
        assertTrue("the work saw its cancellation and returned", returned.await(5, TimeUnit.SECONDS))
        awaitTrue("the cancelled work frees its permit") { limiter.activeCount() == 0 }
    }

    @Test
    fun `a throwing cancel action does not break the timeout`() {
        val limiter = limiter(maxConcurrent = 1, queueCapacity = 0, timeoutMs = 100)
        val outcome =
            limiter.dispatch { cancellation ->
                cancellation.onCancel { throw IllegalStateException("boom") }
                Thread.sleep(5000)
            }
        assertTrue(outcome is RequestLimiter.Outcome.TimedOut)
    }

    @Test
    fun `shutdown cancels running work and releases its caller`() {
        val limiter = limiter(maxConcurrent = 1, queueCapacity = 0, timeoutMs = 10_000)
        val cancelled = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val outcome = AtomicReference<RequestLimiter.Outcome<Unit>>()
        val caller =
            thread {
                outcome.set(
                    limiter.dispatch { cancellation ->
                        cancellation.onCancel { cancelled.countDown() }
                        entered.countDown()
                        // Ignores the cancellation, so shutdown() always finds it running.
                        awaitIgnoringInterrupts(release)
                    },
                )
            }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        try {
            limiter.shutdown()

            caller.join(5_000)
            assertEquals(RequestLimiter.Outcome.Rejected, outcome.get())
            assertEquals("the work was cancelled", 0L, cancelled.count)
            assertEquals("the work keeps its permit until it returns", 1, limiter.activeCount())
        } finally {
            release.countDown()
        }
        awaitTrue("the work frees its permit once it returns") { limiter.activeCount() == 0 }
    }

    @Test
    fun `onRelease runs once, when timed-out work returns`() {
        val limiter = limiter(maxConcurrent = 1, queueCapacity = 0, timeoutMs = 100)
        val release = CountDownLatch(1)
        val released = AtomicInteger(0)

        val outcome = limiter.dispatch(onRelease = { released.incrementAndGet() }) { awaitIgnoringInterrupts(release) }

        assertTrue(outcome is RequestLimiter.Outcome.TimedOut)
        assertEquals("not while the work still runs", 0, released.get())
        release.countDown()
        awaitTrue("once the work returns") { released.get() == 1 }
        Thread.sleep(50)
        assertEquals("only once", 1, released.get())
    }

    @Test
    fun `onRelease runs before dispatch returns for work that completes or throws`() {
        val limiter = limiter(maxConcurrent = 1, queueCapacity = 0, timeoutMs = 1000)
        val released = AtomicInteger(0)

        limiter.dispatch(onRelease = { released.incrementAndGet() }) { 42 }
        assertEquals(1, released.get())

        assertThrows(IllegalStateException::class.java) {
            limiter.dispatch(onRelease = { released.incrementAndGet() }) { error("boom") }
        }
        assertEquals(2, released.get())
    }

    @Test
    fun `onRelease runs right away for a request turned away`() {
        val released = AtomicInteger(0)
        val onRelease = { released.incrementAndGet(); Unit }

        val draining = limiter(maxConcurrent = 1, queueCapacity = 0, timeoutMs = 100)
        draining.beginDrain()
        assertEquals(RequestLimiter.Outcome.Rejected, draining.dispatch(onRelease) { 1 })
        assertEquals("rejected while draining", 1, released.get())

        val release = CountDownLatch(1)
        try {
            // No queue: turned away at once while the only permit is taken.
            val full = limiter(maxConcurrent = 1, queueCapacity = 0, timeoutMs = 100)
            thread { full.dispatch { awaitIgnoringInterrupts(release) } }
            awaitTrue("the permit is taken") { full.activeCount() == 1 }
            assertEquals(RequestLimiter.Outcome.Rejected, full.dispatch(onRelease) { 1 })
            assertEquals("rejected with the queue full", 2, released.get())

            // A queue slot: turned away after waiting the timeout for the permit.
            val queued = limiter(maxConcurrent = 1, queueCapacity = 1, timeoutMs = 100)
            thread { queued.dispatch { awaitIgnoringInterrupts(release) } }
            awaitTrue("the permit is taken") { queued.activeCount() == 1 }
            assertEquals(RequestLimiter.Outcome.Rejected, queued.dispatch(onRelease) { 1 })
            assertEquals("rejected after waiting for the permit", 3, released.get())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `beginDrain rejects new work`() {
        val limiter = limiter(maxConcurrent = 1, queueCapacity = 1, timeoutMs = 1000)
        limiter.beginDrain()
        assertTrue(limiter.dispatch { 1 } is RequestLimiter.Outcome.Rejected)
        limiter.reopen()
        assertTrue(limiter.dispatch { 1 } is RequestLimiter.Outcome.Completed)
    }

    /** Blocks until [release] opens, swallowing interrupts like work that ignores them. */
    private fun awaitIgnoringInterrupts(release: CountDownLatch) {
        while (true) {
            try {
                release.await()
                return
            } catch (_: InterruptedException) {
                // Keep waiting.
            }
        }
    }

    private fun awaitTrue(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            assertTrue(message, System.nanoTime() < deadline)
            Thread.sleep(10)
        }
    }
}
