package io.github.twinsen81.lustro.sample.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.twinsen81.lustro.sample.ActionEvent
import io.github.twinsen81.lustro.sample.ActionGroup
import io.github.twinsen81.lustro.sample.DemoAction

/**
 * What each action of [group] does, with a Run button each. The Run buttons show
 * their results as the chips do, so a run from here needs no trip back.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun GroupSheet(group: ActionGroup, latest: Map<String, ActionEvent>, onRun: (DemoAction) -> Unit, onDismiss: () -> Unit) {
    val p = palette
    val (toneColor, toneContainer) = p.tone(group.tone)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // The app draws edge to edge, and a long sheet must stop under the status bar.
        modifier = Modifier.statusBarsPadding(),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.surface,
        contentColor = p.text,
        scrimColor = Color.Black.copy(alpha = SCRIM_ALPHA),
        dragHandle = { BottomSheetDefaults.DragHandle(color = p.border) },
    ) {
        // The sheet is a window of its own, so its test tags need their own switch.
        LazyColumn(
            modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("Sheet ${group.title}"),
            contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, bottom = 24.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(toneContainer), contentAlignment = Alignment.Center) {
                        Icon(SampleIcons.InfoCircle, contentDescription = null, tint = toneColor, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(group.title, style = SampleText.barTitle)
                }
                Spacer(Modifier.height(10.dp))
                Text(group.note, style = SampleText.body, color = p.textMuted)
                Spacer(Modifier.height(8.dp))
            }
            items(group.actions, key = { it.label }) { action ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(p.surfaceMuted)
                        .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(action.chip, style = SampleText.body.copy(fontWeight = FontWeight.SemiBold))
                        action.detail?.let {
                            Spacer(Modifier.height(2.dp))
                            Text(it, style = SampleText.small, color = p.textMuted)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    ActionControl(
                        tag = "Run: ${action.label}",
                        text = "Run",
                        event = latest[action.label],
                        onClick = { onRun(action) },
                        live = action.live?.collectAsState()?.value,
                        style = ControlStyle.ACCENT,
                        resultText = true,
                    )
                }
            }
        }
    }
}

private const val SCRIM_ALPHA = 0.4f
