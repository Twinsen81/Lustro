package io.github.twinsen81.lustro.sample.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.twinsen81.lustro.sample.ActionEvent
import io.github.twinsen81.lustro.sample.SampleViewModel
import io.github.twinsen81.lustro.sample.Tone

@Composable
internal fun LogsScreen(viewModel: SampleViewModel, latest: Map<String, ActionEvent>, onBack: () -> Unit) {
    val ticker by viewModel.ticker.collectAsState()
    Column(Modifier.fillMaxSize()) {
        SubScreenBar("Logs", "${viewModel.logActions.size} actions", onBack) {
            Pill(
                if (ticker) "ACTIVE" else "PAUSED",
                content = if (ticker) palette.onAccentContainer else palette.textMuted,
                background = if (ticker) palette.accentContainer else palette.surfaceMuted,
                dot = true,
                pulsing = ticker,
            )
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            ScreenHeader("Logs", "Write log lines that the Logs tab reads from the app's logcat.")
            TickerCard(ticker, onToggle = viewModel::toggleTicker)
            SectionLabel("LOG ACTIONS")
            viewModel.logActions.forEach { action ->
                BadgeActionRow(action, latest[action.label], onRun = { viewModel.run(action) })
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TickerCard(on: Boolean, onToggle: () -> Unit) {
    val background by animateColorAsState(if (on) palette.accentContainer else palette.surface, tween(TOGGLE_MS), label = "ticker-background")
    val border by animateColorAsState(if (on) palette.accent.copy(alpha = 0.45f) else palette.border, tween(TOGGLE_MS), label = "ticker-border")
    SampleCard(Modifier.padding(horizontal = ScreenPadding), background = background, border = border) {
        Row(
            Modifier.clickable(role = Role.Switch, onClick = onToggle).testTag("Log ticker").padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(SampleIcons.Lines, if (on) Tone.ACCENT else Tone.SLATE, size = 40.dp, iconSize = 20.dp, background = palette.surface)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("AUTOMATIC TICKER", style = SampleText.eyebrow, color = if (on) palette.accent else palette.textFaint)
                Spacer(Modifier.height(2.dp))
                AnimatedContent(on, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "ticker-title") { running ->
                    Text(if (running) "Running in the foreground" else "Paused", style = SampleText.cardTitle)
                }
                Text("Writes sample lines every 2 s while the app is open", style = SampleText.small, color = palette.textMuted)
            }
            Switch(
                checked = on,
                onCheckedChange = { onToggle() },
                colors = sampleSwitchColors(),
            )
        }
    }
}

private const val TOGGLE_MS = 300
