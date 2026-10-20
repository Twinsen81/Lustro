package io.github.twinsen81.lustro.sample.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.twinsen81.lustro.sample.ActionEvent
import io.github.twinsen81.lustro.sample.ActionGroup
import io.github.twinsen81.lustro.sample.DemoAction
import io.github.twinsen81.lustro.sample.Outcome
import io.github.twinsen81.lustro.sample.SampleViewModel

@Composable
internal fun NetworkScreen(viewModel: SampleViewModel, latest: Map<String, ActionEvent>, onBack: () -> Unit) {
    val groups = viewModel.network
    var expanded by rememberSaveable { mutableStateOf(listOf(groups.first().title)) }
    var about by rememberSaveable { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        SubScreenBar("Network", "${groups.sumOf { it.actions.size }} actions", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            ScreenHeader("Network requests", "Open a group, then tap an action. The Network tab shows each request.")
            groups.forEach { group ->
                GroupCard(
                    group = group,
                    expanded = group.title in expanded,
                    onToggle = { expanded = if (group.title in expanded) expanded - group.title else expanded + group.title },
                    onAbout = { about = group.title },
                    latest = latest,
                    onRun = viewModel::run,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    groups.firstOrNull { it.title == about }?.let { group ->
        GroupSheet(group, latest, onRun = viewModel::run, onDismiss = { about = null })
    }
}

/**
 * A group of actions that opens and closes. A dot on a closed group shows that
 * one of its actions runs; the "i" opens a sheet that explains each action, and
 * the line under the chips explains the one that ran last.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupCard(
    group: ActionGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAbout: () -> Unit,
    latest: Map<String, ActionEvent>,
    onRun: (DemoAction) -> Unit,
) {
    val (toneColor, toneContainer) = palette.tone(group.tone)
    val rotation by animateFloatAsState(if (expanded) 90f else 0f, spring(stiffness = 500f), label = "chevron")
    val actions = group.actions
    val busy = actions.any { latest[it.label]?.outcome == Outcome.RUNNING }
    SampleCard(Modifier.padding(horizontal = ScreenPadding, vertical = 5.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onToggle)
                .testTag(group.title)
                .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(toneContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    SampleIcons.ChevronRight,
                    contentDescription = null,
                    tint = toneColor,
                    modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = rotation },
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(group.title, style = SampleText.cardTitle.copy(fontSize = SampleText.body.fontSize))
            Spacer(Modifier.weight(1f))
            AnimatedVisibility(busy && !expanded, enter = fadeIn(), exit = fadeOut()) {
                PulseDot(palette.accent, Modifier.padding(end = 10.dp))
            }
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = "Explain the actions", onClick = onAbout)
                    .testTag("About ${group.title}"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(SampleIcons.InfoCircle, contentDescription = "About ${group.title}", tint = palette.textMuted, modifier = Modifier.size(18.dp))
            }
        }
        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                Text(group.note, style = SampleText.small, color = palette.textMuted)
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions.forEach { action -> ActionChip(action, latest[action.label], onRun) }
                }
                LastActionNote(actions.filter { latest[it.label] != null }.maxByOrNull { latest.getValue(it.label).atMillis })
            }
        }
    }
}

/** What the action that ran last in a group does: it appears with the first run, and changes with each new one. */
@Composable
private fun LastActionNote(action: DemoAction?) {
    AnimatedContent(
        targetState = action?.takeIf { it.detail != null },
        transitionSpec = { (fadeIn() + expandVertically()) togetherWith fadeOut() using SizeTransform(clip = false) },
        label = "last-action-note",
    ) { shown ->
        if (shown == null) return@AnimatedContent
        Row(
            Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(palette.surfaceMuted)
                .testTag("Note")
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Icon(SampleIcons.InfoCircle, contentDescription = null, tint = palette.textMuted, modifier = Modifier.padding(top = 1.dp).size(15.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = palette.text)) { append(shown.chip) }
                    append(": ")
                    append(shown.detail.orEmpty())
                },
                style = SampleText.small,
                color = palette.textMuted,
            )
        }
    }
}
