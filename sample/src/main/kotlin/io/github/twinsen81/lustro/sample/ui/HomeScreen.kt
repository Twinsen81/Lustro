package io.github.twinsen81.lustro.sample.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.twinsen81.lustro.sample.ActionEvent
import io.github.twinsen81.lustro.sample.CONSOLE_PORT
import io.github.twinsen81.lustro.sample.SampleFlag
import io.github.twinsen81.lustro.sample.SampleFlags
import io.github.twinsen81.lustro.sample.SampleViewModel
import io.github.twinsen81.lustro.sample.Tone

@Composable
internal fun HomeScreen(viewModel: SampleViewModel, flags: List<SampleFlag>?, latest: Map<String, ActionEvent>, onOpen: (Screen) -> Unit) {
    val ticker by viewModel.ticker.collectAsState()
    val serverRunning by viewModel.serverRunning.collectAsState()
    val networkCount = viewModel.network.sumOf { it.actions.size }
    val modules = if (flags != null) MODULES_WITH_FLAGS else MODULES

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        HomeBar(variant = if (viewModel.consoleAvailable) "DEBUG" else "RELEASE")
        AnimatedVisibility(
            visible = SampleFlags.isOn(flags, SampleFlags.HOME_INTRO),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(Modifier.padding(start = ScreenPadding, end = ScreenPadding, top = 14.dp, bottom = 4.dp)) {
                Text("LUSTRO / DEBUG CONSOLE", style = SampleText.eyebrow, color = palette.accent)
                Spacer(Modifier.height(10.dp))
                Text("Test the invisible.", style = SampleText.hero)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Pick a module, then tap an action. Watch the result in the browser console.",
                    style = SampleText.body,
                    color = palette.textMuted,
                )
            }
        }
        ConsoleStrip(viewModel, serverRunning, latest, onOpen = { onOpen(Screen.CONSOLE) })
        SectionLabel("YOUR MODULES", "%02d".format(modules))

        ModuleCard(SampleIcons.Globe, Tone.ACCENT, "Network", "HTTP, bodies, streaming, sockets", onClick = { onOpen(Screen.NETWORK) }) {
            MetaText("$networkCount actions")
            MetaText("·", palette.textFaint)
            MetaText("${viewModel.network.size} groups")
        }
        ModuleCard(SampleIcons.Lines, Tone.VIOLET, "Logs", "Events, repeats and exceptions", onClick = { onOpen(Screen.LOGS) }) {
            PulseDot(if (ticker) palette.accent else palette.textFaint, pulsing = ticker)
            MetaText(if (ticker) "Ticker on" else "Ticker paused", if (ticker) palette.accent else palette.textMuted)
            MetaText("·", palette.textFaint)
            MetaText("${viewModel.logActions.size} actions")
        }
        if (flags != null) {
            ModuleCard(SampleIcons.Flag, Tone.ORANGE, "Feature flags", "Change this app from the browser console", onClick = { onOpen(Screen.FLAGS) }) {
                MetaText("${flags.count { it.enabled }} of ${flags.size} on", palette.orange)
            }
        }
        ModuleCard(SampleIcons.Terminal, Tone.SLATE, "Console", "Server controls and connection", onClick = { onOpen(Screen.CONSOLE) }) {
            MetaText(":$CONSOLE_PORT", palette.textMuted)
            MetaText("·", palette.textFaint)
            when {
                !viewModel.consoleAvailable -> MetaText("No server in this build", palette.textMuted)
                serverRunning -> MetaText("Running")
                else -> MetaText("Stopped", palette.error)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** The server state at a glance, with Stop or Start. Tapping the rest opens the Console screen. */
@Composable
private fun ConsoleStrip(viewModel: SampleViewModel, running: Boolean, latest: Map<String, ActionEvent>, onOpen: () -> Unit) {
    SampleCard(Modifier.padding(start = ScreenPadding, end = ScreenPadding, top = 18.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onOpen)
                .testTag("Console strip")
                .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val available = viewModel.consoleAvailable
            PulseDot(if (available && running) palette.accent else palette.textFaint, pulsing = available && running)
            Spacer(Modifier.width(10.dp))
            Text("CONSOLE", style = SampleText.eyebrow, color = palette.textMuted)
            Spacer(Modifier.width(6.dp))
            Text(":$CONSOLE_PORT", style = SampleText.mono.copy(fontWeight = FontWeight.Bold))
            Spacer(Modifier.weight(1f))
            if (!available) {
                Text("NO-OP BUILD", style = SampleText.eyebrow, color = palette.textFaint, modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp))
            } else {
                Text(
                    if (running) "RUNNING" else "STOPPED",
                    style = SampleText.eyebrow,
                    color = if (running) palette.accent else palette.error,
                )
                Spacer(Modifier.width(8.dp))
                Spacer(Modifier.width(1.dp).height(18.dp).background(palette.border))
                Spacer(Modifier.width(4.dp))
                val action = if (running) viewModel.stopServer else viewModel.startServer
                ActionControl(
                    tag = action.label,
                    text = action.chip,
                    event = latest[action.label],
                    onClick = { viewModel.run(action) },
                    style = ControlStyle.GHOST,
                )
            }
        }
    }
}

private const val MODULES = 3
private const val MODULES_WITH_FLAGS = 4
