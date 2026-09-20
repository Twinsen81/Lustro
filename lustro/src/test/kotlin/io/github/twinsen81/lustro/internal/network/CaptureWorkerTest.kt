package io.github.twinsen81.lustro.internal.network

import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureWorkerTest {
    private val executor = ManualExecutor()
    private val ran = mutableListOf<String>()

    private fun CaptureWorker.submitNamed(name: String, transaction: String = name, chars: Long = 0L) {
        submit(transaction, chars) { ran += name }
    }

    @Test
    fun `tasks run on the worker in the order they were submitted`() {
        val worker = CaptureWorker(executor)

        worker.submitNamed("a")
        worker.submitNamed("b")

        assertEquals(emptyList<String>(), ran)
        executor.runAll()
        assertEquals(listOf("a", "b"), ran)
    }

    @Test
    fun `once the backlog is full a task with nothing queued runs on the caller right away`() {
        val worker = CaptureWorker(executor, maxBacklogChars = 10_000)

        worker.submitNamed("a", chars = 8_000)
        worker.submitNamed("b", chars = 4_000)

        assertEquals(listOf("b"), ran)
        executor.runAll()
        assertEquals(listOf("b", "a"), ran)
    }

    @Test
    fun `once the backlog is full a task still waits behind its own transaction`() {
        val worker = CaptureWorker(executor, maxBacklogChars = 10_000)

        worker.submitNamed("begin", transaction = "tx", chars = 8_000)
        worker.submitNamed("complete", transaction = "tx", chars = 4_000)

        assertEquals(emptyList<String>(), ran)
        executor.runAll()
        assertEquals(listOf("begin", "complete"), ran)
    }

    @Test
    fun `a transaction's next task runs on the caller once its queued ones have run`() {
        val worker = CaptureWorker(executor, maxBacklogChars = 10_000)
        worker.submitNamed("begin", transaction = "tx")
        worker.submitNamed("other", chars = 7_000)
        executor.runNext()

        worker.submitNamed("complete", transaction = "tx", chars = 4_000)

        assertEquals(listOf("begin", "complete"), ran)
        executor.runAll()
        assertEquals(listOf("begin", "complete", "other"), ran)
    }

    @Test
    fun `the worker takes tasks again once the backlog drains`() {
        val worker = CaptureWorker(executor, maxBacklogChars = 10_000)
        worker.submitNamed("a", chars = 8_000)
        executor.runAll()

        worker.submitNamed("b", chars = 8_000)

        assertEquals(listOf("a"), ran)
        assertEquals(1, executor.pending)
    }

    @Test
    fun `a task over the limit still goes to the worker when nothing is waiting`() {
        val worker = CaptureWorker(executor, maxBacklogChars = 10_000)

        worker.submitNamed("a", chars = 50_000)

        assertEquals(emptyList<String>(), ran)
        executor.runAll()
        assertEquals(listOf("a"), ran)
    }

    @Test
    fun `when the worker is stuck, tasks that would have to queue are dropped`() {
        val worker = CaptureWorker(executor, maxBacklogChars = 10_000)
        worker.submitNamed("begin", transaction = "tx")
        worker.submitNamed("complete", transaction = "tx", chars = 45_000)

        worker.submitNamed("progress", transaction = "tx")
        worker.submitNamed("other")

        assertEquals(listOf("other"), ran)
        executor.runAll()
        assertEquals(listOf("other", "begin", "complete"), ran)
    }

    @Test
    fun `a failing task is dropped and later tasks still run`() {
        val worker = CaptureWorker(executor)

        worker.submit("a", 0L) { error("capture failed") }
        worker.submit("b", 0L) { throw StackOverflowError() }
        worker.submitNamed("c")
        executor.runAll()

        assertEquals(listOf("c"), ran)
    }

    @Test
    fun `a task that fails on the caller's thread stays there`() {
        val worker = CaptureWorker(executor, maxBacklogChars = 10_000)
        worker.submitNamed("a", chars = 8_000)

        worker.submit("b", 4_000L) { error("capture failed") }
        executor.runAll()

        assertEquals(listOf("a"), ran)
    }

    @Test
    fun `a capture that can't be queued is dropped without reaching the caller`() {
        val worker = CaptureWorker(executor = { throw OutOfMemoryError("pthread_create failed") }, maxBacklogChars = 10_000)

        worker.submitNamed("a", chars = 8_000)
        // Had the first left its weight in the backlog, this one would run inline.
        worker.submitNamed("b", chars = 8_000)

        assertEquals(emptyList<String>(), ran)
    }

    @Test
    fun `the default executor runs tasks on a daemon capture thread`() {
        val worker = CaptureWorker()
        val thread = AtomicReference<Thread>()

        worker.submit("a", 0L) { thread.set(Thread.currentThread()) }

        assertTrue(worker.awaitIdle(5_000))
        assertNotSame(Thread.currentThread(), thread.get())
        assertEquals("lustro-capture", thread.get().name)
        assertTrue(thread.get().isDaemon)
    }
}
