package io.github.twinsen81.lustro.screen.internal

import io.github.twinsen81.lustro.screen.internal.ScreenFixtures.get
import io.github.twinsen81.lustro.screen.internal.ScreenFixtures.tab
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pins the golden fixtures of the Screen routes to what the routes write, so the
 * fixtures that clients validate against can't drift from the server. The tab numbers
 * Views as it first sees them, and Compose numbers semantics nodes for the process, so
 * the ids differ from run to run: they are compared by where they first appear.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenGoldenTest {
    @Test
    fun `the golden Compose tree is what the route writes`() {
        ScreenFixtures.composeActivity(dialog = true)
        val written = String(get(tab(contextProvider = { """{"route":"profile","stack":["home","profile"]}""" }), "tree").body)
        assertGolden(COMPOSE, written)
    }

    @Test
    fun `the golden View tree is what the route writes`() {
        ScreenFixtures.viewActivity()
        assertGolden(VIEWS, String(get(tab(), "tree").body))
    }

    private fun assertGolden(path: String, written: String) {
        System.getProperty("lustro.screen.golden.out")?.let { File(it, File(path).name).writeText(JSONObject(written).toString(2) + "\n") }
        assertEquals("$path differs from:\n$written\n", canonical(JSONObject(File(path).readText())), canonical(JSONObject(written)))
    }

    private fun canonical(document: JSONObject): Any? {
        val ids = LinkedHashMap<String, String>()
        return canonical(document, ids)
    }

    // Key order is not part of JSON, so objects compare as sorted maps; an id is the order of its first appearance.
    private fun canonical(value: Any?, ids: MutableMap<String, String>): Any? =
        when (value) {
            is JSONObject ->
                value.keys().asSequence().sorted().associateWith { key ->
                    val item = value.get(key)
                    if (key in ID_KEYS && item is String) ids.getOrPut(item) { "#${ids.size + 1}" } else canonical(item, ids)
                }
            is JSONArray -> (0 until value.length()).map { canonical(value.get(it), ids) }
            else -> value
        }

    private companion object {
        val ID_KEYS = setOf("id", "parent")

        // Unit tests run in the module directory.
        const val COMPOSE = "../wire-protocol/v1/golden/screen-tree.json"
        const val VIEWS = "../wire-protocol/v1/golden/screen-tree-views.json"
    }
}
