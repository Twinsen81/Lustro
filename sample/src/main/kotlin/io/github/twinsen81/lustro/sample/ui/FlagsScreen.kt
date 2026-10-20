package io.github.twinsen81.lustro.sample.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.twinsen81.lustro.sample.SampleFlag
import io.github.twinsen81.lustro.sample.SampleFlags
import io.github.twinsen81.lustro.sample.SampleViewModel
import io.github.twinsen81.lustro.sample.Tone

@Composable
internal fun FlagsScreen(viewModel: SampleViewModel, flags: List<SampleFlag>, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        SubScreenBar("Feature flags", "${flags.size} flags", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            ScreenHeader(
                "Feature flags",
                "This module is the app's own console tab, a custom DebugTab. Toggle a flag here or in the browser: the other side follows.",
            )
            SampleCard(Modifier.padding(horizontal = ScreenPadding)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(SampleIcons.Terminal, Tone.ORANGE, size = 40.dp, iconSize = 20.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("IN THE BROWSER", style = SampleText.eyebrow, color = palette.orange)
                        Spacer(Modifier.height(2.dp))
                        Text("Open the Sample Client Feature Flags tab", style = SampleText.cardTitle.copy(fontSize = SampleText.body.fontSize))
                        Text("Turn on ${SampleFlags.DARK_THEME} and watch this phone.", style = SampleText.small, color = palette.textMuted)
                    }
                }
            }
            SectionLabel("FLAGS", "${flags.count { it.enabled }} ON")
            flags.forEach { flag -> FlagRow(flag, onChange = { viewModel.setFlag(flag.id, it) }) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** A flag and its switch. The row lights up when the flag changes, from either side. */
@Composable
private fun FlagRow(flag: SampleFlag, onChange: (Boolean) -> Unit) {
    val glow = remember { Animatable(0f) }
    var shown by remember { mutableStateOf(flag.enabled) }
    LaunchedEffect(flag.enabled) {
        if (flag.enabled != shown) {
            shown = flag.enabled
            glow.snapTo(1f)
            glow.animateTo(0f, tween(GLOW_MS))
        }
    }
    val p = palette
    SampleCard(
        Modifier.padding(horizontal = ScreenPadding, vertical = 5.dp),
        background = lerp(p.surface, p.accentContainer, glow.value),
        border = lerp(p.border, p.accent, glow.value),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(flag.id, style = SampleText.mono.copy(fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.height(2.dp))
                Text(flag.description, style = SampleText.small, color = p.textMuted)
                Spacer(Modifier.height(6.dp))
                val effect = SampleFlags.effect(flag.id)
                Text(
                    effect ?: "The app does not read this flag",
                    style = SampleText.small.copy(fontWeight = FontWeight.SemiBold),
                    color = if (effect != null) p.orange else p.textFaint,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = flag.enabled,
                onCheckedChange = onChange,
                colors = sampleSwitchColors(),
                modifier = Modifier.testTag("flag ${flag.id}"),
            )
        }
    }
}

private const val GLOW_MS = 1_200
