package io.github.twinsen81.lustro.sample.ui

import android.app.AlertDialog as ViewAlertDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.twinsen81.lustro.sample.Tone

/**
 * What the console's Screen tab reads and acts on: a form with a field, a password
 * field, a switch, a button that counts its taps, and a disabled button; a Compose
 * dialog and a View dialog, each a window of its own; and a list taller than its box.
 */
@Composable
internal fun ScreenTreeScreen(onBack: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("Ada Lovelace") }
    var pin by rememberSaveable { mutableStateOf("2468") }
    var notifications by rememberSaveable { mutableStateOf(true) }
    var taps by rememberSaveable { mutableIntStateOf(0) }
    var dialogOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        SubScreenBar("Screen", "The UI tree", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            ScreenHeader(
                "Screen",
                "The console's Screen tab gives agents this app's UI tree: each window, and each Compose node and View in it, " +
                    "with its test tag, text, bounds, and actions. Open a dialog and read the tree again: the dialog is a window of its own.",
            )
            SampleCard(Modifier.padding(horizontal = ScreenPadding)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(SampleIcons.Terminal, Tone.SLATE, size = 40.dp, iconSize = 20.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("FROM THE API", style = SampleText.eyebrow, color = palette.textMuted)
                        Spacer(Modifier.height(2.dp))
                        Text("lustro screen tree", style = SampleText.mono.copy(fontWeight = FontWeight.SemiBold))
                        Text(
                            "Or GET screen/tree?settle=500, for a tree that has stopped changing.",
                            style = SampleText.small,
                            color = palette.textMuted,
                        )
                    }
                }
            }
            SectionLabel("A FORM")
            SampleCard(Modifier.padding(horizontal = ScreenPadding)) {
                Field("Name", name, onChange = { name = it }, tag = "screen name")
                Field("PIN", pin, onChange = { pin = it }, tag = "screen pin", password = true)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Notifications", style = SampleText.body, modifier = Modifier.weight(1f))
                    Switch(notifications, { notifications = it }, colors = sampleSwitchColors(), modifier = Modifier.testTag("screen notifications"))
                }
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (taps == 1) "Tapped once" else "Tapped $taps times",
                        style = SampleText.body,
                        color = palette.textMuted,
                        modifier = Modifier.weight(1f).testTag("screen taps"),
                    )
                    ActionControl(tag = "screen tap", text = "Tap", event = null, onClick = { taps++ }, style = ControlStyle.ACCENT)
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("A disabled button", style = SampleText.body, modifier = Modifier.weight(1f))
                    Text(
                        "Unavailable",
                        style = SampleText.chip,
                        color = palette.textFaint,
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.dp, palette.border, RoundedCornerShape(10.dp))
                                .clickable(enabled = false, role = Role.Button) { taps++ }
                                .testTag("screen disabled")
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            SectionLabel("WINDOWS")
            SampleCard(Modifier.padding(horizontal = ScreenPadding)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("A Compose dialog", style = SampleText.body, modifier = Modifier.weight(1f))
                    ActionControl(tag = "screen compose dialog", text = "Open", event = null, onClick = { dialogOpen = true })
                }
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("A View dialog", style = SampleText.body, modifier = Modifier.weight(1f))
                    ActionControl(tag = "screen view dialog", text = "Open", event = null, onClick = { showViewDialog(context) })
                }
            }
            SectionLabel("A LIST", "40 ROWS")
            SampleCard(Modifier.padding(horizontal = ScreenPadding)) {
                LazyColumn(Modifier.fillMaxWidth().height(180.dp).testTag("screen list")) {
                    items(LIST_ROWS) { index ->
                        Text(
                            "Row ${index + 1}",
                            style = SampleText.body,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp).testTag("screen row ${index + 1}"),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text("A Compose dialog") },
            text = { Text("Its window comes after the activity's in the tree.") },
            confirmButton = { TextButton(onClick = { dialogOpen = false }, modifier = Modifier.testTag("screen dialog ok")) { Text("OK") } },
        )
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, tag: String, password: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label.uppercase(), style = SampleText.eyebrow, color = palette.textFaint)
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value,
            onChange,
            singleLine = true,
            textStyle = SampleText.body.copy(color = palette.text),
            cursorBrush = SolidColor(palette.accent),
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(palette.surfaceMuted, RoundedCornerShape(10.dp))
                    .border(1.dp, palette.border, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .testTag(tag),
        )
    }
}

private fun showViewDialog(context: Context) {
    ViewAlertDialog.Builder(context)
        .setTitle("A View dialog")
        .setMessage("A classic dialog: the tree has its Views, and no Compose node.")
        .setPositiveButton("OK", null)
        .show()
}

private const val LIST_ROWS = 40
