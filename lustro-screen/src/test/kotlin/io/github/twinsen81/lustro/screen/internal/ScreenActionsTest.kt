package io.github.twinsen81.lustro.screen.internal

import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.twinsen81.lustro.screen.internal.ScreenFixtures.json
import io.github.twinsen81.lustro.screen.internal.ScreenFixtures.postOffMain
import io.github.twinsen81.lustro.screen.internal.ScreenFixtures.strings
import io.github.twinsen81.lustro.screen.internal.ScreenFixtures.tab
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class ScreenActionsTest {
    // Robolectric gives no window the focus; these tests give it to every window.
    private val everyWindow = tab(focus = { true })
    private var taps = 0

    @Test
    fun `a click by test tag runs the node's click, and the tree after it shows the change`() {
        actions()
        val response = postOffMain(everyWindow, "click", """{"tag":"counter"}""")
        assertEquals(200, response.status)
        val result = json(response)
        assertEquals("ok", result.getString("status"))
        assertEquals("OnClick", result.getString("action"))
        assertTrue(result.getBoolean("handled"))
        assertEquals(result.getString("matched"), result.getString("target"))
        assertEquals(1, taps)
        val tree = result.getJSONObject("tree")
        assertTrue(tree.getJSONObject("settle").getInt("frames") >= 1)
        assertEquals(listOf("Tapped 1"), strings(node(tree, "counter").getJSONArray("text")))
    }

    @Test
    fun `a click by text matches the text's node and runs the click of the node above it`() {
        actions()
        val result = json(postOffMain(everyWindow, "click", """{"text":"Tapped 0"}"""))
        assertEquals(1, taps)
        val tree = result.getJSONObject("tree")
        assertEquals(node(tree, "counter").getString("id"), result.getString("target"))
        assertTrue(result.getString("matched") != result.getString("target"))
    }

    @Test
    fun `a click by id runs that node's click`() {
        actions()
        val tree = json(ScreenFixtures.getOffMain(everyWindow, "tree"))
        val id = node(tree, "counter").getString("id")
        assertEquals(id, json(postOffMain(everyWindow, "click", """{"id":"$id"}""")).getString("target"))
        assertEquals(1, taps)
    }

    @Test
    fun `a selector that matches two nodes, none, a disabled one, or one without the action is refused`() {
        actions()
        val ambiguous = postOffMain(everyWindow, "click", """{"text":"Open"}""")
        assertEquals(409, ambiguous.status)
        val error = json(ambiguous)
        assertEquals("conflict", error.getString("error"))
        assertEquals("ambiguous", error.getString("code"))
        assertTrue(error.getString("message"), error.getString("message").startsWith("2 nodes match text \"Open\": "))
        assertTrue(error.getString("hint").startsWith("Select one by its id"))

        val none = postOffMain(everyWindow, "click", """{"tag":"nothing"}""")
        assertEquals(404, none.status)
        assertEquals("no_match", json(none).getString("code"))

        val disabled = postOffMain(everyWindow, "click", """{"tag":"disabled"}""")
        assertEquals(409, disabled.status)
        assertEquals("disabled", json(disabled).getString("code"))

        val plain = postOffMain(everyWindow, "click", """{"tag":"plain"}""")
        assertEquals(409, plain.status)
        assertEquals("no_action", json(plain).getString("code"))
        assertEquals(0, taps)
    }

    @Test
    fun `a node under a window that has the focus is refused unless the request forces it`() {
        actions(dialog = true)
        val tree = json(ScreenFixtures.getOffMain(everyWindow, "tree"))
        val dialogRoot = android.view.inspector.WindowInspector.getGlobalWindowViews().last()
        assertEquals(2, tree.getJSONArray("windows").length())
        val dialogFocused = tab(focus = { it === dialogRoot })

        val covered = postOffMain(dialogFocused, "click", """{"tag":"counter"}""")
        assertEquals(409, covered.status)
        val error = json(covered)
        assertEquals("not_in_focused_window", error.getString("code"))
        assertTrue(error.getString("message"), error.getString("message").contains("in the activity window, which does not have the focus"))
        assertEquals(0, taps)

        assertEquals(200, postOffMain(dialogFocused, "click", """{"tag":"counter","force":true}""").status)
        assertEquals(1, taps)
        assertEquals(200, postOffMain(dialogFocused, "click", """{"tag":"in dialog"}""").status)

        val noFocus = postOffMain(tab(focus = { false }), "click", """{"tag":"counter"}""")
        assertEquals(409, noFocus.status)
        assertEquals("no_window_focus", json(noFocus).getString("code"))
    }

    @Test
    fun `a target with no part on the screen is refused unless the request forces it`() {
        actions()
        val hidden = postOffMain(everyWindow, "click", """{"tag":"hidden"}""")
        assertEquals(409, hidden.status)
        assertEquals("not_on_screen", json(hidden).getString("code"))
        assertEquals(0, taps)
        assertEquals(200, postOffMain(everyWindow, "click", """{"tag":"hidden","force":true}""").status)
        assertEquals(1, taps)
    }

    @Test
    fun `set-text replaces the text of a Compose field and of an EditText`() {
        actions()
        val result = json(postOffMain(everyWindow, "set-text", """{"tag":"field","value":"Grace"}"""))
        assertEquals("SetText", result.getString("action"))
        assertEquals("Grace", node(result.getJSONObject("tree"), "field").getString("editableText"))

        val activity = ScreenFixtures.viewActivity()
        val field = activity.findViewById<View>(android.R.id.content).let { find<EditText>(it) }
        field.tag = "pin"
        assertEquals(200, postOffMain(everyWindow, "set-text", """{"tag":"pin","value":"0000"}""").status)
        assertEquals("0000", field.text.toString())
        assertEquals(4, field.selectionStart)
    }

    @Test
    fun `scroll takes a Compose list to an index, or by pixels, and a ScrollView by pixels`() {
        ScreenFixtures.composeActivity()
        val toIndex = json(postOffMain(everyWindow, "scroll", """{"tag":"list","index":30}"""))
        assertEquals("ScrollToIndex", toIndex.getString("action"))
        assertTrue(ScreenFixtures.nodes(toIndex.getJSONObject("tree")).any { it.optString("testTag") == "row 30" })

        val byPixels = json(postOffMain(everyWindow, "scroll", """{"tag":"list","dy":-40}"""))
        assertEquals("ScrollBy", byPixels.getString("action"))

        val activity = ScreenFixtures.viewActivity()
        val scroll = activity.findViewById<View>(android.R.id.content).let { find<ScrollView>(it) }
        scroll.tag = "scroller"
        assertEquals(200, postOffMain(everyWindow, "scroll", """{"tag":"scroller","dy":120}""").status)
        assertEquals(120, scroll.scrollY)
    }

    @Test
    fun `a scroll by pixels moves a ListView's rows, and back`() {
        val controller = Robolectric.buildActivity(android.app.Activity::class.java)
        controller.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        val activity = controller.setup().get()
        val list = android.widget.ListView(activity).apply { tag = "rows" }
        list.adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_list_item_1, (1..50).map { "Row $it" })
        activity.setContentView(list, android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 400))
        ScreenFixtures.idle()
        assertEquals(0, list.firstVisiblePosition)
        assertEquals(200, postOffMain(everyWindow, "scroll", """{"tag":"rows","dy":600}""").status)
        assertTrue("first row ${list.firstVisiblePosition}", list.firstVisiblePosition > 0)
        // The rows moved, not the View's own content.
        assertEquals(0, list.scrollY)
        assertEquals(200, postOffMain(everyWindow, "scroll", """{"tag":"rows","dy":-600}""").status)
        assertEquals(0, list.firstVisiblePosition)
    }

    @Test
    fun `an action that throws gives a 500 with action_failed, and the main thread goes on`() {
        actions(throwOnTap = true)
        val response = postOffMain(everyWindow, "click", """{"tag":"counter"}""")
        assertEquals(500, response.status)
        val error = json(response)
        assertEquals("action_failed", error.getString("code"))
        assertTrue(error.getString("message"), error.getString("message").contains("IllegalStateException: the app's click failed"))
        ScreenFixtures.idle()
    }

    @Test
    fun `a body that names no selector, two, an unknown key, or a bad value is refused with its field`() {
        val cases =
            listOf(
                Triple("click", "", null),
                Triple("click", "[1]", null),
                Triple("click", """{"force":true}""", "tag"),
                Triple("click", """{"tag":"a","text":"b"}""", "text"),
                Triple("click", """{"tag":"a","tags":"b"}""", "tags"),
                Triple("click", """{"tag":""}""", "tag"),
                Triple("click", """{"tag":1}""", "tag"),
                Triple("click", """{"tag":"a","settle":0}""", "settle"),
                Triple("click", """{"tag":"a","force":"yes"}""", "force"),
                Triple("set-text", """{"tag":"a"}""", "value"),
                Triple("set-text", """{"tag":"a","value":1}""", "value"),
                Triple("scroll", """{"tag":"a"}""", "index"),
                Triple("scroll", """{"tag":"a","index":1,"dy":2}""", "dx"),
                Triple("scroll", """{"tag":"a","index":-1}""", "index"),
                Triple("scroll", """{"tag":"a","dy":"far"}""", "dy"),
                Triple("click", """{"tag":"a","value":"x"}""", "value"),
            )
        for ((route, body, field) in cases) {
            val response = everyWindow.handle(io.github.twinsen81.lustro.DebugRequest(route, "POST", body = body.toByteArray()))!!
            assertEquals("$route $body", 400, response.status)
            val error = json(response)
            if (field == null) assertFalse(error.has("field")) else assertEquals("$route $body", field, error.getString("field"))
        }
        assertEquals(405, ScreenFixtures.request(everyWindow, "click", "GET").status)
    }

    private fun actions(dialog: Boolean = false, throwOnTap: Boolean = false) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        controller.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        val activity = controller.setup().get()
        activity.setContent { Actions(dialog, throwOnTap) }
        ScreenFixtures.idle()
    }

    @Composable
    private fun Actions(dialog: Boolean, throwOnTap: Boolean) {
        var count by remember { mutableIntStateOf(0) }
        var text by remember { mutableStateOf("Ada") }
        Column {
            Box(
                Modifier.testTag("counter").clickable(role = Role.Button) {
                    check(!throwOnTap) { "the app's click failed" }
                    taps++
                    count++
                },
            ) { BasicText("Tapped $count") }
            Box(Modifier.testTag("open 1").clickable {}) { BasicText("Open") }
            Box(Modifier.testTag("open 2").clickable {}) { BasicText("Open") }
            Box(Modifier.testTag("disabled").clickable(enabled = false) { taps++ }) { BasicText("Disabled") }
            BasicText("Just text", Modifier.testTag("plain"))
            Box(Modifier.testTag("hidden").size(0.dp).clickable { taps++ })
            BasicTextField(text, { text = it }, Modifier.testTag("field"))
        }
        if (dialog) {
            Dialog(onDismissRequest = {}) { Box(Modifier.testTag("in dialog").clickable {}) { BasicText("In the dialog") } }
        }
    }

    private fun node(tree: JSONObject, tag: String): JSONObject = ScreenFixtures.nodes(tree).single { it.optString("testTag") == tag }

    private inline fun <reified T : View> find(view: View): T = findOf(T::class.java, view) as T

    private fun findOf(type: Class<*>, view: View): View? {
        if (type.isInstance(view)) return view
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) findOf(type, view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
