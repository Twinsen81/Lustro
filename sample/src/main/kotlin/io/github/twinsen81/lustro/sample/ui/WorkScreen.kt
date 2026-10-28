package io.github.twinsen81.lustro.sample.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import io.github.twinsen81.lustro.sample.ActionEvent
import io.github.twinsen81.lustro.sample.SampleViewModel
import io.github.twinsen81.lustro.sample.Tone

@Composable
internal fun WorkScreen(viewModel: SampleViewModel, latest: Map<String, ActionEvent>, onBack: () -> Unit) {
    val counts by viewModel.workCounts.collectAsState(initial = null)
    Column(Modifier.fillMaxSize()) {
        SubScreenBar("WorkManager", "${viewModel.workActions.size} actions", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            ScreenHeader(
                "WorkManager",
                "The console's WorkManager tab lists this app's work and records the events of each run: " +
                    "the result and output, why a run stopped, and what a worker threw. It cancels work and prunes finished work.",
            )
            SampleCard(Modifier.padding(horizontal = ScreenPadding)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(SampleIcons.Terminal, Tone.ORANGE, size = 40.dp, iconSize = 20.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("FROM THE API", style = SampleText.eyebrow, color = palette.orange)
                        Spacer(Modifier.height(2.dp))
                        Text("lustro api GET work/items", style = SampleText.mono.copy(fontWeight = FontWeight.SemiBold))
                        Text(
                            "Then GET work/events --param type=finished,exception, and GET work/metrics for the history on disk.",
                            style = SampleText.small,
                            color = palette.textMuted,
                        )
                    }
                }
            }
            SectionLabel("WORK BY STATE")
            SampleCard(Modifier.padding(horizontal = ScreenPadding)) {
                WorkInfo.State.entries.forEach { state -> StateRow(state, counts?.get(state) ?: 0, loaded = counts != null) }
            }
            SectionLabel("APP WORK")
            viewModel.workActions.forEach { action ->
                BadgeActionRow(action, latest[action.label], onRun = { viewModel.run(action) })
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StateRow(state: WorkInfo.State, count: Int, loaded: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag("work ${state.name}")
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(state.name, style = SampleText.mono.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
        Text(if (loaded) "$count" else "…", style = SampleText.mono, color = palette.textMuted)
    }
}
