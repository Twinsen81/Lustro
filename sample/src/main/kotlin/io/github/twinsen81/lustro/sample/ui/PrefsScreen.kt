package io.github.twinsen81.lustro.sample.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.twinsen81.lustro.sample.ActionEvent
import io.github.twinsen81.lustro.sample.DemoPrefs
import io.github.twinsen81.lustro.sample.SampleViewModel
import io.github.twinsen81.lustro.sample.Tone

@Composable
internal fun PrefsScreen(viewModel: SampleViewModel, latest: Map<String, ActionEvent>, onBack: () -> Unit) {
    val values by viewModel.prefsValues.collectAsState()
    Column(Modifier.fillMaxSize()) {
        SubScreenBar("Preferences", "${viewModel.prefsActions.size} actions", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            ScreenHeader(
                "Preferences",
                "The Preferences tab reads and edits these SharedPreferences files over the API. " +
                    "Change a value here or through the API: the other side follows.",
            )
            SampleCard(Modifier.padding(horizontal = ScreenPadding)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(SampleIcons.Terminal, Tone.ACCENT, size = 40.dp, iconSize = 20.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("FROM THE API", style = SampleText.eyebrow, color = palette.accent)
                        Spacer(Modifier.height(2.dp))
                        Text("lustro api GET prefs/files", style = SampleText.mono.copy(fontWeight = FontWeight.SemiBold))
                        Text(
                            "Then GET prefs/entries --param file=${DemoPrefs.SETTINGS}, and POST prefs/edit to change a value.",
                            style = SampleText.small,
                            color = palette.textMuted,
                        )
                    }
                }
            }
            SectionLabel(DemoPrefs.SETTINGS.uppercase() + " · VISIBLE", "${values.size} KEYS")
            SampleCard(Modifier.padding(horizontal = ScreenPadding)) {
                if (values.isEmpty()) {
                    Text("The file is empty", style = SampleText.small, color = palette.textFaint, modifier = Modifier.padding(16.dp))
                }
                values.forEach { (key, value) -> ValueRow(key, value) }
            }
            SectionLabel("APP CHANGES")
            viewModel.prefsActions.forEach { action ->
                BadgeActionRow(action, latest[action.label], onRun = { viewModel.run(action) })
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** A key and its value. The row lights up when the value changes, from either side. */
@Composable
private fun ValueRow(key: String, value: String) {
    val glow = remember { Animatable(0f) }
    var shown by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (value != shown) {
            shown = value
            glow.snapTo(1f)
            glow.animateTo(0f, tween(GLOW_MS))
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .testTag("pref $key")
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, style = SampleText.mono.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = SampleText.mono,
            color = lerp(palette.textMuted, palette.accent, glow.value),
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private const val GLOW_MS = 1_200
