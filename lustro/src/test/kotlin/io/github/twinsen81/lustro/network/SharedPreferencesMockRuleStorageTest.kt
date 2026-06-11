package io.github.twinsen81.lustro.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.internal.network.MockRuleImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric round-trip tests for [SharedPreferencesMockRuleStorage]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedPreferencesMockRuleStorageTest {
    private fun storage(): SharedPreferencesMockRuleStorage {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("lustro-test-rules", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return SharedPreferencesMockRuleStorage(prefs)
    }

    @Test
    fun `empty store loads no rules`() {
        assertTrue(storage().load().isEmpty())
    }

    @Test
    fun `save then load round-trips rule fields`() {
        val storage = storage()
        val original =
            MockRuleImpl(
                id = "r1",
                enabled = false,
                name = "My Mock",
                urlPattern = "regex:.*/v2/.*",
                method = "POST",
                statusCode = 503,
                responseHeaders = Headers.of("Content-Type" to "application/json", "X-Custom" to "v"),
                responseBody = """{"ok":false}""",
                hitCount = 7,
            )
        storage.save(listOf(original))

        val loaded = storage.load().single()
        assertEquals("r1", loaded.id)
        assertEquals(false, loaded.enabled)
        assertEquals("My Mock", loaded.name)
        assertEquals("regex:.*/v2/.*", loaded.urlPattern)
        assertEquals("POST", loaded.method)
        assertEquals(503, loaded.statusCode)
        assertEquals("""{"ok":false}""", loaded.responseBody)
        assertEquals("application/json", loaded.responseHeaders.get("Content-Type"))
        assertEquals("v", loaded.responseHeaders.get("X-Custom"))
        // hitCount is intentionally not persisted (runtime-only counter).
        assertEquals(0, loaded.hitCount)
    }

    @Test
    fun `null method round-trips as null`() {
        val storage = storage()
        storage.save(listOf(MockRuleImpl(id = "r", name = "n", urlPattern = "/x", method = null)))
        assertNull(storage.load().single().method)
    }

    @Test
    fun `rules with blank id or pattern are dropped on load`() {
        val storage = storage()
        storage.save(
            listOf(
                MockRuleImpl(id = "good", name = "g", urlPattern = "/ok"),
                MockRuleImpl(id = "", name = "blank-id", urlPattern = "/x"),
                MockRuleImpl(id = "blank-pattern", name = "bp", urlPattern = ""),
            ),
        )
        // The blank-id and blank-pattern entries are rejected on the way back in.
        assertEquals(listOf("good"), storage.load().map { it.id })
    }

    @Test
    fun `save replaces previously stored rules`() {
        val storage = storage()
        storage.save(listOf(MockRuleImpl(id = "a", name = "a", urlPattern = "/a")))
        storage.save(listOf(MockRuleImpl(id = "b", name = "b", urlPattern = "/b")))
        assertEquals(listOf("b"), storage.load().map { it.id })
    }
}
