package io.github.twinsen81.lustro.screen.internal

import io.github.twinsen81.lustro.screen.internal.ScreenFixtures.byTag
import io.github.twinsen81.lustro.screen.internal.ScreenFixtures.get
import io.github.twinsen81.lustro.screen.internal.ScreenFixtures.json
import io.github.twinsen81.lustro.screen.internal.ScreenFixtures.nodes
import io.github.twinsen81.lustro.screen.internal.ScreenFixtures.strings
import io.github.twinsen81.lustro.screen.internal.ScreenFixtures.tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class ScreenTreeTest {
    @Test
    fun `a Compose screen comes as its merged semantics nodes, under the View that hosts them`() {
        ScreenFixtures.composeActivity()
        val tree = json(get(tab(), "tree"))
        assertTrue(tree.getBoolean("merged"))
        assertTrue(tree.getBoolean("compose"))
        assertEquals("inspector", tree.getString("windowSource"))
        assertEquals(2.0, tree.getDouble("density"), 0.0)

        val window = tree.getJSONArray("windows").getJSONObject(0)
        assertEquals("activity", window.getString("kind"))
        assertEquals(1, window.getInt("type"))
        val all = nodes(tree)
        val host = all.single { it.optString("className") == "androidx.compose.ui.platform.AndroidComposeView" }
        val save = byTag(tree, "save")
        assertEquals("compose", save.getString("kind"))
        assertEquals("c" + host.getString("id").removePrefix("v") + ".", save.getString("id").substringBefore('.') + ".")
        // The merged node of a clickable carries its children's text.
        assertEquals(listOf("Save"), strings(save.getJSONArray("text")))
        assertEquals("Button", save.getString("role"))
        assertTrue(save.getBoolean("clickable"))
        assertEquals(listOf("OnClick"), strings(save.getJSONArray("actions")))
        assertFalse(save.has("enabled"))
        assertTrue(all.none { strings(it.optJSONArray("text")) == listOf("Save") && it.optString("testTag") != "save" })

        val dark = byTag(tree, "dark")
        assertEquals("Switch", dark.getString("role"))
        assertEquals("off", dark.getString("toggle"))
        assertEquals(listOf("Dark theme"), strings(dark.getJSONArray("text")))
    }

    @Test
    fun `the unmerged tree keeps each text in a node of its own`() {
        ScreenFixtures.composeActivity()
        val tree = json(get(tab(), "tree", "merged" to "false"))
        assertFalse(tree.getBoolean("merged"))
        val save = byTag(tree, "save")
        assertFalse(save.has("text"))
        val label = nodes(tree).single { strings(it.optJSONArray("text")) == listOf("Save") }
        assertEquals(save.getString("id"), label.getString("parent"))
        // A node keeps its id in both trees.
        assertEquals(byTag(json(get(tab(), "tree")), "save").getString("id"), save.getString("id"))
    }

    @Test
    fun `a field carries its editable text, and a password field one dot for each character`() {
        ScreenFixtures.composeActivity()
        val tree = json(get(tab(), "tree"))
        val name = byTag(tree, "name")
        assertEquals("Ada", name.getString("editableText"))
        assertTrue(strings(name.getJSONArray("actions")).contains("SetText"))
        assertFalse(name.has("password"))
        val secret = byTag(tree, "secret")
        assertEquals("•••••••", secret.getString("editableText"))
        assertTrue(secret.getBoolean("password"))
        assertFalse(tree.toString().contains("hunter2"))
    }

    @Test
    fun `a list carries its scroll range and size, and a row out of view has no visible part`() {
        ScreenFixtures.composeActivity()
        val tree = json(get(tab(), "tree"))
        val list = byTag(tree, "list")
        val scroll = list.getJSONObject("verticalScroll")
        assertEquals(0.0, scroll.getDouble("value"), 0.0)
        assertTrue(scroll.getBoolean("forward"))
        assertFalse(scroll.getBoolean("backward"))
        assertTrue(strings(list.getJSONArray("actions")).containsAll(listOf("ScrollBy", "ScrollToIndex")))
        assertEquals(1, list.getJSONObject("collection").getInt("columns"))
        // A lazy list composes the rows in view only.
        assertTrue(nodes(tree).none { it.optString("testTag") == "row 40" })
        val avatar = byTag(tree, "avatar")
        assertEquals(listOf("Avatar"), strings(avatar.getJSONArray("contentDescription")))
        assertTrue(avatar.isNull("visibleBounds"))
    }

    @Test
    fun `a dialog is a window of its own, after the activity's`() {
        ScreenFixtures.composeActivity(dialog = true)
        val tree = json(get(tab(), "tree"))
        val windows = tree.getJSONArray("windows")
        assertEquals(2, windows.length())
        assertEquals("activity", windows.getJSONObject(0).getString("kind"))
        val dialog = windows.getJSONObject(1)
        assertEquals("dialog", dialog.getString("kind"))
        assertEquals(2, dialog.getInt("type"))
        val confirm = byTag(tree, "confirm", window = 1)
        assertEquals(listOf("Delete the profile?"), strings(confirm.getJSONArray("text")))
        assertEquals(dialog.getString("id"), nodes(tree, window = 1).first().getString("id"))
    }

    @Test
    fun `a classic View screen comes as its Views, with ids, text, toggles, and scroll ranges`() {
        ScreenFixtures.viewActivity()
        val tree = json(get(tab(), "tree"))
        val all = nodes(tree)
        assertTrue(all.all { it.getString("kind") == "view" })
        assertEquals("com.android.internal.policy.DecorView", all.first().getString("className"))
        assertTrue(all.first().isNull("visibleBounds").not())
        assertEquals("android:id/content", all.single { it.optString("resourceId") == "android:id/content" }.getString("resourceId"))
        assertEquals("Settings", strings(byTag(tree, "title").getJSONArray("text")).single())

        val go = all.single { it.optString("className") == "android.widget.Button" }
        assertEquals(listOf("Go on"), strings(go.getJSONArray("contentDescription")))
        assertTrue(go.getBoolean("clickable"))
        val password = all.single { it.optString("className") == "android.widget.EditText" }
        assertEquals("••••••", password.getString("editableText"))
        assertTrue(password.getBoolean("password"))
        assertFalse(password.has("text"))
        assertEquals("on", all.single { it.optString("className") == "android.widget.CheckBox" }.getString("toggle"))

        val scroll = all.single { it.optString("className") == "android.widget.ScrollView" }
        val range = scroll.getJSONObject("verticalScroll")
        assertEquals(0.0, range.getDouble("value"), 0.0)
        assertEquals(280.0, range.getDouble("max"), 0.0)
        assertTrue(range.getBoolean("forward"))
        val content = all.single { it.optString("parent") == scroll.getString("id") }
        assertTrue(content.getBoolean("clipped"))
        val rows = all.filter { it.optString("parent") == content.getString("id") }
        assertEquals(12, rows.size)
        assertFalse(rows.first().isNull("visibleBounds"))
        assertTrue(rows.last().isNull("visibleBounds"))
        // Each View keeps its id from read to read.
        assertEquals(all.map { it.getString("id") }, nodes(json(get(tab(), "tree"))).map { it.getString("id") })
    }

    @Test
    fun `without Compose UI, a Compose host is a View with no semantics nodes`() {
        ScreenFixtures.composeActivity()
        val tree = json(get(tab(compose = false), "tree"))
        assertFalse(tree.getBoolean("compose"))
        assertTrue(nodes(tree).all { it.getString("kind") == "view" })
        assertTrue(nodes(tree).any { it.optString("className") == "androidx.compose.ui.platform.AndroidComposeView" })
    }

    @Test
    fun `the app's context comes as JSON, and what is not JSON as an error`() {
        ScreenFixtures.viewActivity()
        val ok = json(get(tab(contextProvider = { """{"route":"settings","stack":["home","settings"]}""" }), "tree"))
        assertEquals("settings", ok.getJSONObject("context").getString("route"))
        assertFalse(ok.has("contextError"))
        assertTrue(json(get(tab(), "tree")).isNull("context"))
        assertTrue(json(get(tab(contextProvider = { null }), "tree")).isNull("context"))
        assertEquals("on", json(get(tab(contextProvider = { "\"on\"" }), "tree")).getString("context"))

        for (bad in listOf("{\"route\":", "settings", "{} {}")) {
            val tree = json(get(tab(contextProvider = { bad }), "tree"))
            assertTrue(bad, tree.isNull("context"))
            assertTrue(bad, tree.getString("contextError").startsWith("The ScreenContextProvider returned text that is not JSON"))
        }
        val thrown = json(get(tab(contextProvider = { error("no route yet") }), "tree"))
        assertTrue(thrown.isNull("context"))
        assertEquals("The ScreenContextProvider threw java.lang.IllegalStateException: no route yet", thrown.getString("contextError"))
    }

    @Test
    fun `settle reads on each frame until two reads in a row are the same`() {
        ScreenFixtures.composeActivity()
        val tree = json(ScreenFixtures.getOffMain(tab(), "tree", "settle" to "500"))
        val settle = tree.getJSONObject("settle")
        assertEquals(500, settle.getInt("requestedMs"))
        assertTrue(settle.getBoolean("settled"))
        assertEquals(1, settle.getInt("frames"))
        assertTrue(settle.getLong("waitedMs") < 500)
        assertEquals(listOf("Save"), strings(byTag(tree, "save").getJSONArray("text")))
        // Without settle there is no settle object.
        assertFalse(json(ScreenFixtures.getOffMain(tab(), "tree")).has("settle"))
    }

    @Test
    fun `a main thread that does not run the read answers 504 with main_thread_blocked`() {
        ScreenFixtures.viewActivity()
        val tab = tab(timeoutMs = 50)
        var response: io.github.twinsen81.lustro.DebugResponse? = null
        // The main looper does not run while this thread waits, as a blocked main thread.
        val thread = Thread { response = get(tab, "tree") }
        thread.start()
        thread.join(5_000)
        assertEquals(504, response!!.status)
        val error = json(response!!)
        assertEquals("main_thread_blocked", error.getString("code"))
        assertEquals("The main thread did not start the read in 50 ms", error.getString("message"))
        // The read that the main thread runs later sees that the request is over.
        ScreenFixtures.idle()
    }

    @Test
    fun `a request that the runtime gives up on answers 504`() {
        ScreenFixtures.viewActivity()
        val tab = tab(timeoutMs = 60_000)
        var response: io.github.twinsen81.lustro.DebugResponse? = null
        val thread = Thread { response = get(tab, "tree") }
        thread.start()
        Thread.sleep(50)
        thread.interrupt()
        thread.join(5_000)
        assertEquals(504, response!!.status)
        assertEquals("The request timed out or the server stopped", json(response!!).getString("message"))
        ScreenFixtures.idle()
    }

    @Test
    fun `the tree takes GET only, merged true or false, and settle from 0 to 10000`() {
        val tab = tab()
        assertEquals(405, ScreenFixtures.request(tab, "tree", "POST").status)
        assertNull(tab.handle(io.github.twinsen81.lustro.DebugRequest("windows", "GET")))
        val merged = json(get(tab, "tree", "merged" to "yes"))
        assertEquals("bad_request", merged.getString("error"))
        assertEquals("merged", merged.getString("field"))
        for (bad in listOf("-1", "10001", "soon", "1.5")) {
            val settle = json(get(tab, "tree", "settle" to bad))
            assertEquals(bad, "settle", settle.getString("field"))
        }
    }
}
