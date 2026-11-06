package io.github.twinsen81.lustro.screen.internal

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.screen.ScreenContextProvider
import io.github.twinsen81.lustro.screen.ScreenDebugTab
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/** Screens for the tests: a Compose screen with a dialog, and a classic View screen. */
internal object ScreenFixtures {
    val app: Application get() = ApplicationProvider.getApplicationContext()

    fun tab(
        contextProvider: ScreenContextProvider? = null,
        timeoutMs: Long = ScreenSource.MAIN_THREAD_TIMEOUT_MS,
        compose: Boolean = true,
        focus: (android.view.View) -> Boolean = android.view.View::hasWindowFocus,
    ): ScreenDebugTab = ScreenDebugTab.create(ScreenSource(app, ActivityTracker(), contextProvider, timeoutMs, composeAvailable = compose, windowFocus = focus))

    /** A Compose screen: a title, a field, a password field, a switch, a button, a list; and, when [dialog], a dialog over it. */
    fun composeActivity(dialog: Boolean = false, onSave: () -> Unit = {}): ComponentActivity {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        // No action bar: its views would come before the content in each tree.
        controller.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        val activity = controller.setup().get()
        activity.setContent { Profile(dialog, onSave) }
        idle()
        return activity
    }

    @Composable
    fun Profile(dialog: Boolean, onSave: () -> Unit) {
        var name by remember { mutableStateOf("Ada") }
        var secret by remember { mutableStateOf("hunter2") }
        var dark by remember { mutableStateOf(false) }
        Column {
            BasicText("Profile", Modifier.testTag("title"))
            BasicTextField(name, { name = it }, Modifier.testTag("name"))
            BasicTextField(secret, { secret = it }, Modifier.testTag("secret"), visualTransformation = PasswordVisualTransformation())
            Box(Modifier.testTag("dark").toggleable(value = dark, role = Role.Switch) { dark = it }.padding(8.dp)) {
                BasicText("Dark theme")
            }
            Box(Modifier.testTag("save").clickable(role = Role.Button, onClick = onSave).padding(8.dp)) {
                BasicText("Save")
            }
            Box(Modifier.testTag("avatar").semantics { contentDescription = "Avatar" }.height(10.dp))
            LazyColumn(Modifier.testTag("list").height(60.dp)) {
                items(50) { BasicText("Row $it", Modifier.testTag("row $it").height(20.dp)) }
            }
        }
        if (dialog) {
            Dialog(onDismissRequest = {}) {
                Box(Modifier.testTag("confirm").clickable(role = Role.Button) {}) { BasicText("Delete the profile?") }
            }
        }
    }

    /** A classic View screen: a title, a button, a password field, a check box, and a scrolling column. */
    fun viewActivity(): Activity {
        val controller = Robolectric.buildActivity(Activity::class.java)
        controller.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        val activity = controller.setup().get()
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(activity).apply { text = "Settings"; tag = "title" })
        column.addView(Button(activity).apply { text = "Go"; contentDescription = "Go on"; setOnClickListener {} })
        column.addView(
            EditText(activity).apply {
                setText("s3cret")
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            },
        )
        column.addView(CheckBox(activity).apply { text = "Remember me"; isChecked = true })
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        repeat(12) { index -> list.addView(TextView(activity).apply { text = "Item $index"; height = 40 }) }
        column.addView(ScrollView(activity).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 200))
        activity.setContentView(column)
        idle()
        return activity
    }

    fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    fun get(tab: ScreenDebugTab, path: String, vararg params: Pair<String, String>): DebugResponse = request(tab, path, "GET", *params)

    fun request(tab: ScreenDebugTab, path: String, method: String, vararg params: Pair<String, String>): DebugResponse =
        tab.handle(DebugRequest(path, method, params.associate { it.first to listOf(it.second) }))!!

    /**
     * Calls the tab on another thread, as the server does, and runs the main looper
     * until the call returns: the read posts to the main thread, and a settle waits for
     * frames, which come as Robolectric's clock moves.
     */
    fun getOffMain(tab: ScreenDebugTab, path: String, vararg params: Pair<String, String>, frameMs: Long = 16): DebugResponse {
        var result: Result<DebugResponse>? = null
        val thread = Thread { result = runCatching { get(tab, path, *params) } }
        thread.start()
        while (thread.isAlive) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(frameMs))
            thread.join(1)
        }
        return result!!.getOrThrow()
    }

    fun json(response: DebugResponse): JSONObject = JSONObject(String(response.body, Charsets.UTF_8))

    fun nodes(tree: JSONObject, window: Int = 0): List<JSONObject> {
        val nodes = tree.getJSONArray("windows").getJSONObject(window).getJSONArray("nodes")
        return (0 until nodes.length()).map { nodes.getJSONObject(it) }
    }

    fun byTag(tree: JSONObject, tag: String, window: Int = 0): JSONObject = nodes(tree, window).single { it.optString("testTag") == tag }

    fun strings(array: JSONArray?): List<String> = if (array == null) emptyList() else (0 until array.length()).map { array.getString(it) }
}
