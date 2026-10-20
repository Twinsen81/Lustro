package io.github.twinsen81.lustro.sample.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.twinsen81.lustro.sample.ActionEvent
import io.github.twinsen81.lustro.sample.Outcome
import kotlinx.coroutines.delay

/**
 * The bar at the bottom of every screen: the newest result, and a button that
 * opens the last few. A new result slides in, and its badge pops.
 */
@Composable
internal fun ResultBar(events: List<ActionEvent>, historyOpen: Boolean, onToggleHistory: () -> Unit) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(CLOCK_TICK_MS)
            value = System.currentTimeMillis()
        }
    }
    val latest = events.firstOrNull()
    val border = palette.border
    Column(
        Modifier
            .fillMaxWidth()
            .background(palette.surface)
            .drawBehind { drawLine(border, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx()) }
            .navigationBarsPadding(),
    ) {
        AnimatedVisibility(
            visible = historyOpen && events.size > 1,
            enter = expandVertically(tween(HISTORY_MS)) + fadeIn(tween(HISTORY_MS)),
            exit = shrinkVertically(tween(HISTORY_MS)) + fadeOut(tween(HISTORY_MS)),
        ) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                Text("EARLIER", style = SampleText.eyebrow, color = palette.textFaint)
                Spacer(Modifier.height(6.dp))
                events.drop(1).forEach { event ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutcomeBadge(event.outcome, size = 22.dp, iconSize = 13.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            event.text,
                            style = SampleText.small,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(ago(now - event.atMillis), style = SampleText.small, color = palette.textFaint)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutcomeBadge(latest?.outcome, modifier = Modifier.testTag("result-outcome"), pulseKey = latest?.let { it.run to it.outcome })
            Spacer(Modifier.width(12.dp))
            AnimatedContent(
                targetState = latest,
                contentKey = { it?.run to it?.text },
                transitionSpec = {
                    (slideInVertically(tween(SLIDE_MS)) { it / 2 } + fadeIn(tween(SLIDE_MS))) togetherWith
                        (slideOutVertically(tween(SLIDE_MS)) { -it / 2 } + fadeOut(tween(FADE_OUT_MS)))
                },
                label = "result",
                modifier = Modifier.weight(1f),
            ) { event ->
                Column {
                    Text(
                        event?.text ?: "Ready.",
                        style = SampleText.body.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("result"),
                    )
                    Text(
                        subtitle(event, now),
                        style = SampleText.small,
                        color = palette.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (events.size > 1) {
                Spacer(Modifier.width(8.dp))
                HistoryButton(count = events.size, open = historyOpen, onClick = onToggleHistory)
            }
        }
    }
}

@Composable
private fun HistoryButton(count: Int, open: Boolean, onClick: () -> Unit) {
    val background by animateColorAsState(if (open) palette.accentContainer else palette.surfaceMuted, label = "history-background")
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("History")
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(SampleIcons.Clock, contentDescription = "History", tint = palette.textMuted, modifier = Modifier.size(15.dp))
        AnimatedContent(
            targetState = count,
            transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) },
            label = "history-count",
        ) { value ->
            Text("$value", style = SampleText.chip)
        }
    }
}

/** A round badge for an outcome. It pops when [pulseKey] changes to a finished outcome. */
@Composable
internal fun OutcomeBadge(
    outcome: Outcome?,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
    iconSize: Dp = 18.dp,
    pulseKey: Any? = null,
) {
    val p = palette
    val (content, container) =
        when (outcome) {
            Outcome.RUNNING, Outcome.OK -> p.accent to p.accentContainer
            Outcome.HTTP_ERROR -> p.warn to p.warnContainer
            Outcome.FAILED -> p.error to p.errorContainer
            Outcome.INFO, null -> p.slate to p.slateContainer
        }
    val background by animateColorAsState(container, tween(BADGE_COLOR_MS), label = "badge-background")
    val scale = remember { Animatable(1f) }
    LaunchedEffect(pulseKey) {
        if (pulseKey != null && outcome != Outcome.RUNNING) {
            scale.animateTo(BADGE_POP_SCALE, tween(BADGE_POP_IN_MS))
            scale.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 400f))
        }
    }
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .clip(CircleShape)
            .background(background)
            .semantics { contentDescription = describe(outcome) },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = outcome,
            transitionSpec = { (scaleIn(initialScale = 0.4f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.4f) + fadeOut()) },
            label = "badge-glyph",
        ) { target ->
            when (target) {
                Outcome.RUNNING -> CircularProgressIndicator(modifier = Modifier.size(iconSize - 2.dp), color = content, strokeWidth = 2.dp)
                else -> Icon(glyph(target), contentDescription = null, tint = content, modifier = Modifier.size(iconSize))
            }
        }
    }
}

private fun glyph(outcome: Outcome?) =
    when (outcome) {
        Outcome.OK -> SampleIcons.Check
        Outcome.HTTP_ERROR -> SampleIcons.Alert
        Outcome.FAILED -> SampleIcons.Close
        Outcome.RUNNING, Outcome.INFO, null -> SampleIcons.Info
    }

// Scripts read the outcome from this description; the text says what ran.
private fun describe(outcome: Outcome?): String =
    when (outcome) {
        Outcome.RUNNING -> "In progress"
        Outcome.OK -> "Succeeded"
        Outcome.HTTP_ERROR -> "HTTP error"
        Outcome.FAILED -> "Failed"
        Outcome.INFO -> "Info"
        null -> "Ready"
    }

private fun subtitle(event: ActionEvent?, now: Long): String =
    when {
        event == null -> "Tap an action. Its result shows here."
        event.outcome == Outcome.RUNNING -> listOfNotNull("In progress", event.detail).joinToString(" · ")
        else -> listOfNotNull(ago(now - event.atMillis), event.detail).joinToString(" · ")
    }

private fun ago(millis: Long): String {
    val seconds = millis / MILLIS_PER_SECOND
    return when {
        seconds < JUST_NOW_SECONDS -> "Just now"
        seconds < SECONDS_PER_MINUTE -> "$seconds s ago"
        seconds < SECONDS_PER_HOUR -> "${seconds / SECONDS_PER_MINUTE} min ago"
        else -> "${seconds / SECONDS_PER_HOUR} h ago"
    }
}

private const val CLOCK_TICK_MS = 1_000L
private const val HISTORY_MS = 220
private const val SLIDE_MS = 260
private const val FADE_OUT_MS = 150
private const val BADGE_COLOR_MS = 220
private const val BADGE_POP_SCALE = 1.2f
private const val BADGE_POP_IN_MS = 110
private const val MILLIS_PER_SECOND = 1_000L
private const val JUST_NOW_SECONDS = 5
private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3_600
