package io.github.twinsen81.lustro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic tests for [DebugRequest] cancellation. */
class DebugRequestCancellationTest {
    private fun request() = DebugRequest(path = "query", method = "GET")

    @Test
    fun `cancel runs each registered action once, in order`() {
        val request = request()
        val calls = mutableListOf<String>()
        request.onCancel { calls += "first" }
        request.onCancel { calls += "second" }
        assertFalse(request.isCancelled)
        assertEquals(emptyList<String>(), calls)

        request.cancel()
        request.cancel()

        assertTrue(request.isCancelled)
        assertEquals(listOf("first", "second"), calls)
    }

    @Test
    fun `an action registered after cancel runs right away`() {
        val request = request()
        request.cancel()
        var ran = false
        request.onCancel { ran = true }
        assertTrue(ran)
    }

    @Test
    fun `a throwing action does not stop the others and its failure is rethrown`() {
        val request = request()
        val first = IllegalStateException("first")
        val second = NotImplementedError("second")
        var ranLast = false
        request.onCancel { throw first }
        request.onCancel { throw second }
        request.onCancel { ranLast = true }

        val thrown = assertThrows(IllegalStateException::class.java) { request.cancel() }

        assertSame(first, thrown)
        assertSame(second, thrown.suppressed.single())
        assertTrue(ranLast)
        assertTrue(request.isCancelled)
    }
}
