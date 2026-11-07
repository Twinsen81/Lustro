package io.github.twinsen81.lustro.screen.internal

import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
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
    fun `set-text gives a Compose field the focus first, as a user's tap does`() {
        actions()
        assertFalse(node(json(ScreenFixtures.getOffMain(everyWindow, "tree")), "field").optBoolean("focused"))
        val tree = json(postOffMain(everyWindow, "set-text", """{"tag":"field","value":"Grace"}""")).getJSONObject("tree")
        assertTrue(node(tree, "field").optBoolean("focused"))
        assertEquals("Grace", node(tree, "field").getString("editableText"))
    }

    @Test
    fun `set-text answers with the tree after the keyboard that the focus opens`() {
        val keyboardUp = mutableStateOf(false)
        val menu = mutableIntStateOf(0)
        form(keyboardUp, menu)
        var asked = 0
        var moving = false
        // The keyboard shows some frames after the focus asked for it, and the screen starts to make room for it
        // later, as on a physical device; a menu's own animation, such as a dropdown's, runs and ends before that.
        val keyboard: (View) -> Boolean = {
            val shown = ++asked > KEYBOARD_AFTER_CALLS
            if (shown && !moving) {
                moving = true
                val main = Handler(Looper.getMainLooper())
                main.postDelayed({ menu.intValue = 1 }, FRAME_MS)
                main.postDelayed({ menu.intValue = 2 }, 2 * FRAME_MS)
                main.postDelayed({ keyboardUp.value = true }, MOVE_AFTER_MS)
            }
            shown
        }
        val result = json(postOffMain(tab(focus = { true }, keyboard = keyboard), "set-text", """{"tag":"field","value":"Grace"}"""))
        val tree = result.getJSONObject("tree")
        assertEquals("Grace", node(tree, "field").getString("editableText"))
        // The keyboard covers the button: a user can't tap it now.
        assertTrue(node(tree, "save").toString(), node(tree, "save").isNull("visibleBounds"))
        assertTrue(tree.getJSONObject("settle").getBoolean("settled"))
    }

    @Test
    fun `after the keyboard shows, set-text waits for the keyboard's animation, also on a screen that does not move`() {
        Settings.Global.putFloat(ScreenFixtures.app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val settle = keyboardShownSettle()
        assertTrue(settle.getBoolean("settled"))
        val animation = ScreenSource.KEYBOARD_MOVE_START_MS + ScreenSource.KEYBOARD_MOVE_MS
        assertTrue(settle.toString(), settle.getLong("waitedMs") >= animation)
        assertTrue(settle.toString(), settle.getLong("waitedMs") < ActionBodies.DEFAULT_SETTLE_MS)
    }

    @Test
    fun `with animations off, the wait after the keyboard is only for the screen to start to move`() {
        Settings.Global.putFloat(ScreenFixtures.app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val settle = keyboardShownSettle()
        assertTrue(settle.getBoolean("settled"))
        assertTrue(settle.toString(), settle.getLong("waitedMs") >= ScreenSource.KEYBOARD_MOVE_START_MS)
        assertTrue(settle.toString(), settle.getLong("waitedMs") < ScreenSource.KEYBOARD_MOVE_START_MS + ScreenSource.KEYBOARD_MOVE_MS)
    }

    // The settle of a set-text whose keyboard shows on a screen that does not make room for it.
    private fun keyboardShownSettle(): JSONObject {
        form(mutableStateOf(false))
        var asked = 0
        val result = json(postOffMain(tab(focus = { true }, keyboard = { ++asked > KEYBOARD_AFTER_CALLS }), "set-text", """{"tag":"field","value":"Ada"}"""))
        return result.getJSONObject("tree").getJSONObject("settle")
    }

    @Test
    fun `set-text does not wait for a keyboard that already shows`() {
        form(mutableStateOf(false))
        var asked = 0
        val shown = json(postOffMain(tab(focus = { true }, keyboard = { asked++; true }), "set-text", """{"tag":"field","value":"Ada"}"""))
        assertTrue(shown.getJSONObject("tree").getJSONObject("settle").getBoolean("settled"))
        // Asked once, before the action: the keyboard was there, so nothing is on its way.
        assertEquals(1, asked)
    }

    @Test
    fun `set-text waits no longer than its limit for a keyboard that does not show`() {
        form(mutableStateOf(false))
        val started = android.os.SystemClock.uptimeMillis()
        // As with a hardware keyboard: the focus asks for the soft keyboard, and none shows.
        val none = json(postOffMain(tab(focus = { true }, keyboard = { false }), "set-text", """{"tag":"field","value":"Ada"}"""))
        val waited = android.os.SystemClock.uptimeMillis() - started
        assertTrue(none.getJSONObject("tree").getJSONObject("settle").getBoolean("settled"))
        assertTrue("waited $waited", waited >= ScreenSource.KEYBOARD_WAIT_MS)
        assertTrue("waited $waited", waited < ScreenSource.KEYBOARD_WAIT_MS + ActionBodies.DEFAULT_SETTLE_MS)
    }

    @Test
    fun `with a hardware keyboard out, set-text waits less for a soft keyboard that does not show`() {
        val config = ScreenFixtures.app.resources.configuration
        config.keyboard = Configuration.KEYBOARD_QWERTY
        config.hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_NO
        form(mutableStateOf(false))
        val started = android.os.SystemClock.uptimeMillis()
        val result = json(postOffMain(tab(focus = { true }, keyboard = { false }), "set-text", """{"tag":"field","value":"Ada"}"""))
        val waited = android.os.SystemClock.uptimeMillis() - started
        assertTrue(result.getJSONObject("tree").getJSONObject("settle").getBoolean("settled"))
        assertTrue("waited $waited", waited >= ScreenSource.KEYBOARD_WAIT_HARDWARE_MS)
        assertTrue("waited $waited", waited < ScreenSource.KEYBOARD_WAIT_MS)
    }

    @Test
    fun `only set-text on a Compose field waits for the keyboard`() {
        form(mutableStateOf(false))
        var asked = 0
        val counting = tab(focus = { true }, keyboard = { asked++; false })
        assertEquals(200, postOffMain(counting, "click", """{"tag":"save"}""").status)
        assertEquals(200, postOffMain(counting, "scroll", """{"tag":"page","dy":10}""").status)
        val activity = ScreenFixtures.viewActivity()
        activity.findViewById<View>(android.R.id.content).let { find<EditText>(it) }.tag = "pin"
        assertEquals(200, postOffMain(counting, "set-text", """{"tag":"pin","value":"0000"}""").status)
        assertEquals(0, asked)
    }

    @Test
    fun `a scroll by pixels on a Compose list answers with the tree after the scroll's animation`() {
        ScreenFixtures.composeActivity()
        // Compose animates the scroll, and its first frame moves nothing.
        val tree = json(postOffMain(everyWindow, "scroll", """{"tag":"list","dy":200}""")).getJSONObject("tree")
        assertTrue(tree.getJSONObject("settle").getBoolean("settled"))
        // 200 px at 40 px a row: row 5 is at the top, or row 4 for the spring's last pixel. Before the
        // scroll moved, row 0 was. Robolectric does not draw, so the rows' places can lag a few pixels.
        val first = ScreenFixtures.nodes(tree).first { it.optString("testTag").startsWith("row ") && !it.isNull("visibleBounds") }
        assertTrue(first.toString(), first.getString("testTag") in setOf("row 4", "row 5"))
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

    private fun form(keyboardUp: State<Boolean>, menu: State<Int> = mutableIntStateOf(0)) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        controller.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        val activity = controller.setup().get()
        activity.setContent { Form(keyboardUp.value, menu.value) }
        ScreenFixtures.idle()
    }

    // A field and a button under it on a page that scrolls; with [keyboardUp], the page makes room for the keyboard, as imePadding() does.
    @Composable
    private fun Form(keyboardUp: Boolean, menu: Int) {
        var text by remember { mutableStateOf("") }
        Column(Modifier.fillMaxSize().padding(bottom = if (keyboardUp) 300.dp else 0.dp)) {
            BasicText("Menu frame $menu", Modifier.testTag("menu"))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("page")) {
                BasicTextField(text, { text = it }, Modifier.testTag("field"))
                Spacer(Modifier.height(450.dp))
                Box(Modifier.testTag("save").clickable {}.size(80.dp, 40.dp)) { BasicText("Save") }
                Spacer(Modifier.height(300.dp))
            }
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

    private companion object {
        // The tab asks once before the action, and then on each frame: the keyboard shows on the fifth frame.
        const val KEYBOARD_AFTER_CALLS = 5

        const val FRAME_MS = 16L

        // The screen starts to make room for the keyboard after a menu's own animation has ended.
        const val MOVE_AFTER_MS = 200L
    }
}
