package io.github.twinsen81.lustro.internal

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Unit tests for [LustroTokenStore]: stability, rotation, reset, and encoding. */
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
}
