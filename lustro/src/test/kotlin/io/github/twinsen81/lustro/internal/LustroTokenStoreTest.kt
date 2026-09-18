package io.github.twinsen81.lustro.internal

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Unit tests for [LustroTokenStore]: stability, rotation, reset, encoding, and lazy I/O. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LustroTokenStoreTest {
    private fun store(): LustroTokenStore =
        LustroTokenStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `token is stable across calls and store instances`() {
        val first = store().token()
        // A fresh instance over the same prefs file returns the same token.
        assertEquals(first, store().token())
    }

    @Test
    fun `token is base64-url no-pad and 256 bits of entropy`() {
        val token = store().token()
        // 32 bytes -> 43 Base64 chars (no padding).
        assertEquals(43, token.length)
        // URL-safe alphabet only: no '+', '/', or '=' padding.
        assertTrue(token.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun `rotate invalidates the previous token`() {
        val s = store()
        val before = s.token()
        val after = s.rotate()
        assertNotEquals(before, after)
        assertEquals(after, s.token())
    }

    @Test
    fun `reset yields a fresh token on next access`() {
        val s = store()
        val before = s.token()
        s.reset()
        val after = s.token()
        assertNotEquals(before, after)
    }

    /** Counts how often the prefs file is opened through it. */
    private class PrefsOpenCountingContext(base: Context) : ContextWrapper(base) {
        val opens = AtomicInteger()

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            opens.incrementAndGet()
            return super.getSharedPreferences(name, mode)
        }
    }

    @Test
    fun `construction does not open the prefs file`() {
        val context = PrefsOpenCountingContext(ApplicationProvider.getApplicationContext())
        val s = LustroTokenStore(context)
        assertEquals(0, context.opens.get())

        s.token()
        s.token()
        assertEquals("opened once, on first use", 1, context.opens.get())
    }

    @Test
    fun `concurrent first reads through separate instances agree on one token`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val readers = 8
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(readers)
        try {
            val reads =
                List(readers) {
                    pool.submit(
                        Callable {
                            go.await()
                            LustroTokenStore(context).token()
                        },
                    )
                }
            go.countDown()
            assertEquals(1, reads.map { it.get(5, TimeUnit.SECONDS) }.toSet().size)
        } finally {
            pool.shutdownNow()
        }
    }
}
