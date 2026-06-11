package io.github.twinsen81.lustro.internal

import io.github.twinsen81.lustro.DebugTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the internal [DebugTabRegistry]. */
class DebugTabRegistryTest {
    private class FakeTab(
        override val id: String,
        override val title: String = id,
        override val icon: String = "x",
        override val order: Int = 100,
        override val showInTabBar: Boolean = true,
    ) : DebugTab()

    @Test
    fun `accepts valid ids`() {
        val registry = DebugTabRegistry()
        // a-z start, then a-z0-9- up to 31 chars total.
        registry.addTab(FakeTab("a"))
        registry.addTab(FakeTab("network"))
        registry.addTab(FakeTab("a-b-c"))
        registry.addTab(FakeTab("tab9"))
        registry.addTab(FakeTab("a".repeat(31)))
        registry.start()
        assertEquals(5, registry.sortedTabs.size)
    }

    @Test
    fun `rejects uppercase id at addTab`() {
        assertThrows(IllegalArgumentException::class.java) {
            DebugTabRegistry().addTab(FakeTab("Network"))
        }
    }

    @Test
    fun `rejects id starting with a digit`() {
        assertThrows(IllegalArgumentException::class.java) {
            DebugTabRegistry().addTab(FakeTab("1abc"))
        }
    }

    @Test
    fun `rejects underscore in id`() {
        assertThrows(IllegalArgumentException::class.java) {
            DebugTabRegistry().addTab(FakeTab("a_b"))
        }
    }

    @Test
    fun `rejects empty id`() {
        assertThrows(IllegalArgumentException::class.java) {
            DebugTabRegistry().addTab(FakeTab(""))
        }
    }

    @Test
    fun `rejects id longer than 31 chars`() {
        assertThrows(IllegalArgumentException::class.java) {
            DebugTabRegistry().addTab(FakeTab("a".repeat(32)))
        }
    }

    @Test
    fun `rejects duplicate ids at start`() {
        val registry = DebugTabRegistry()
        registry.addTab(FakeTab("dup"))
        registry.addTab(FakeTab("dup"))
        assertThrows(IllegalArgumentException::class.java) { registry.start() }
    }

    @Test
    fun `orders by order with alphabetical id tie-break`() {
        val registry = DebugTabRegistry()
        registry.addTab(FakeTab(id = "zebra", order = 100))
        registry.addTab(FakeTab(id = "alpha", order = 100))
        registry.addTab(FakeTab(id = "first", order = 10))
        registry.addTab(FakeTab(id = "mid", order = 50))
        registry.start()

        assertEquals(
            listOf("first", "mid", "alpha", "zebra"),
            registry.sortedTabs.map { it.id },
        )
    }

    @Test
    fun `visibleTabs filters out headless tabs but keeps them findable`() {
        val registry = DebugTabRegistry()
        registry.addTab(FakeTab(id = "visible", order = 10))
        registry.addTab(FakeTab(id = "headless", order = 20, showInTabBar = false))
        registry.start()

        assertEquals(listOf("visible"), registry.visibleTabs().map { it.id })
        assertEquals(2, registry.sortedTabs.size)
        assertEquals("headless", registry.findTab("headless")?.id)
    }

    @Test
    fun `defaultTab is first visible tab`() {
        val registry = DebugTabRegistry()
        registry.addTab(FakeTab(id = "second", order = 20))
        registry.addTab(FakeTab(id = "headless", order = 5, showInTabBar = false))
        registry.addTab(FakeTab(id = "third", order = 10))
        registry.start()
        // headless has the lowest order but is excluded from the tab bar.
        assertEquals("third", registry.defaultTab()?.id)
    }

    @Test
    fun `findTab returns null for unknown id`() {
        val registry = DebugTabRegistry()
        registry.addTab(FakeTab("known"))
        registry.start()
        assertNull(registry.findTab("missing"))
    }

    @Test
    fun `allIds is sorted alphabetically`() {
        val registry = DebugTabRegistry()
        registry.addTab(FakeTab(id = "zebra", order = 1))
        registry.addTab(FakeTab(id = "alpha", order = 2))
        registry.start()
        assertEquals(listOf("alpha", "zebra"), registry.allIds())
    }

    @Test
    fun `read-only after start - addTab throws`() {
        val registry = DebugTabRegistry()
        registry.addTab(FakeTab("one"))
        registry.start()
        assertThrows(IllegalStateException::class.java) {
            registry.addTab(FakeTab("two"))
        }
    }

    @Test
    fun `start is idempotent`() {
        val registry = DebugTabRegistry()
        registry.addTab(FakeTab(id = "b", order = 10))
        registry.addTab(FakeTab(id = "a", order = 20))
        registry.start()
        val firstSnapshot = registry.sortedTabs
        registry.start() // second call is a no-op
        assertSame(firstSnapshot, registry.sortedTabs)
        assertTrue(registry.sortedTabs.isNotEmpty())
    }
}
