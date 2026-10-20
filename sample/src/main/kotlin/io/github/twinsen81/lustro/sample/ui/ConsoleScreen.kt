package io.github.twinsen81.lustro.sample.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.twinsen81.lustro.sample.ActionEvent
import io.github.twinsen81.lustro.sample.CONSOLE_PORT
import io.github.twinsen81.lustro.sample.SampleViewModel

@Composable
internal fun ConsoleScreen(viewModel: SampleViewModel, latest: Map<String, ActionEvent>, onBack: () -> Unit) {
    val running by viewModel.serverRunning.collectAsState()
    Column(Modifier.fillMaxSize()) {
        SubScreenBar("Console", ":$CONSOLE_PORT", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            ScreenHeader("Console", "The debug server runs inside the app. A browser on your computer connects to it through adb.")
            if (viewModel.consoleAvailable) {
                ServerCard(viewModel, running, latest)
                SectionLabel("CONNECT FROM A COMPUTER")
                StepCard(1, "With the lustro CLI", listOf("lustro open"), "It forwards the port, reads the token, and opens the console.", viewModel, latest)
                StepCard(
                    2,
                    "Or by hand",
                    listOf("adb forward tcp:$CONSOLE_PORT tcp:$CONSOLE_PORT", "adb logcat -s LustroToken"),
                    "Then open http://localhost:$CONSOLE_PORT/#lustro_token=<token>.",
                    viewModel,
                    latest,
                )
            } else {
                SampleCard(Modifier.padding(horizontal = ScreenPadding)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Release build", style = SampleText.cardTitle)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "This build uses lustro-noop. There is no server, and nothing is captured. The actions still run.",
                            style = SampleText.small,
                            color = palette.textMuted,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ServerCard(viewModel: SampleViewModel, running: Boolean, latest: Map<String, ActionEvent>) {
    SampleCard(Modifier.padding(horizontal = ScreenPadding)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(if (running) palette.accentContainer else palette.errorContainer),
                contentAlignment = Alignment.Center,
            ) {
                PulseDot(if (running) palette.accent else palette.error, size = 12.dp, pulsing = running)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (running) "Running" else "Stopped", style = SampleText.cardTitle)
                Text("127.0.0.1:$CONSOLE_PORT on the device", style = SampleText.small, color = palette.textMuted)
            }
            val action = if (running) viewModel.stopServer else viewModel.startServer
            ActionControl(
                tag = action.label,
                text = if (running) "Stop server" else "Start server",
                event = latest[action.label],
                onClick = { viewModel.run(action) },
                style = ControlStyle.ACCENT,
            )
        }
    }
}

@Composable
private fun StepCard(
    number: Int,
    title: String,
    commands: List<String>,
    note: String,
    viewModel: SampleViewModel,
    latest: Map<String, ActionEvent>,
) {
    SampleCard(Modifier.padding(horizontal = ScreenPadding, vertical = 5.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(22.dp).clip(CircleShape).background(palette.slateContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$number", style = SampleText.small.copy(fontWeight = FontWeight.Bold), color = palette.slate)
                }
                Spacer(Modifier.width(10.dp))
                Text(title, style = SampleText.cardTitle.copy(fontSize = SampleText.body.fontSize))
            }
            commands.forEach { command ->
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.surfaceMuted)
                        .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        command,
                        style = SampleText.mono,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    )
                    val label = SampleViewModel.copyLabel(command)
                    ActionControl(tag = label, text = "Copy", event = latest[label], onClick = { viewModel.copy(command) }, style = ControlStyle.GHOST)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(note, style = SampleText.small, color = palette.textMuted)
        }
    }
}
