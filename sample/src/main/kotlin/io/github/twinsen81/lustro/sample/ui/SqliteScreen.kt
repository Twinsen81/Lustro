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
import io.github.twinsen81.lustro.sample.ActionEvent
import io.github.twinsen81.lustro.sample.DemoSqlite
import io.github.twinsen81.lustro.sample.SampleViewModel
import io.github.twinsen81.lustro.sample.Tone

@Composable
internal fun SqliteScreen(viewModel: SampleViewModel, latest: Map<String, ActionEvent>, onBack: () -> Unit) {
    val counts by viewModel.sqliteCounts.collectAsState()
    Column(Modifier.fillMaxSize()) {
        SubScreenBar("SQLite", "${viewModel.sqliteActions.size} actions", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            ScreenHeader(
                "SQLite",
                "The console's SQLite tab browses these databases: the tables, their schemas, and pages of rows, sorted and filtered. " +
                    "It edits the rows of the notes database and runs your own SQL. The app keeps writing while the console reads.",
            )
            SampleCard(Modifier.padding(horizontal = ScreenPadding)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(SampleIcons.Terminal, Tone.VIOLET, size = 40.dp, iconSize = 20.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("FROM THE API", style = SampleText.eyebrow, color = palette.violet)
                        Spacer(Modifier.height(2.dp))
                        Text("lustro api GET sqlite/databases", style = SampleText.mono.copy(fontWeight = FontWeight.SemiBold))
                        Text(
                            "Then GET sqlite/tables --param db=${DemoSqlite.NOTES}, and GET sqlite/rows --param db=${DemoSqlite.NOTES} --param table=notes.",
                            style = SampleText.small,
                            color = palette.textMuted,
                        )
                    }
                }
            }
            SectionLabel("DATABASES")
            SampleCard(Modifier.padding(horizontal = ScreenPadding)) {
                DatabaseRow(DemoSqlite.NOTES, "The app's open handle · writable", counts?.let { "${it.first} notes" })
                DatabaseRow(DemoSqlite.CATALOG, "A file, opened read-only", counts?.let { "${it.second} products" })
                DatabaseRow(DemoSqlite.SDK_CACHE, "Not registered: the tab finds it on disk", null)
            }
            SectionLabel("APP WRITES")
            viewModel.sqliteActions.forEach { action ->
                BadgeActionRow(action, latest[action.label], onRun = { viewModel.run(action) })
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DatabaseRow(name: String, note: String, count: String?) {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag("database $name")
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = SampleText.mono.copy(fontWeight = FontWeight.SemiBold))
            Text(note, style = SampleText.small, color = palette.textMuted)
        }
        if (count != null) Text(count, style = SampleText.mono, color = palette.textMuted)
    }
}
