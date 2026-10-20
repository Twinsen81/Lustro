package io.github.twinsen81.lustro.sample.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.twinsen81.lustro.sample.ActionEvent
import io.github.twinsen81.lustro.sample.DemoAction
import io.github.twinsen81.lustro.sample.Outcome
import kotlinx.coroutines.delay

/**
 * What an action control shows. A finished run shows its outcome for
 * [FLASH_MS], then the control goes back to [IDLE].
 */
internal enum class ControlState { IDLE, RUNNING, LIVE, OK, HTTP_ERROR, FAILED, INFO }

private const val FLASH_MS = 1_400L

@Composable
internal fun rememberControlState(event: ActionEvent?, live: Boolean): ControlState {
    var flash by remember { mutableStateOf<Outcome?>(null) }
    LaunchedEffect(event?.run, event?.outcome, event?.atMillis) {
        flash = null
        val outcome = event?.outcome ?: return@LaunchedEffect
        if (outcome == Outcome.RUNNING) return@LaunchedEffect
        // Only a result that is new shows: a screen that opens later does not replay it.
        val age = System.currentTimeMillis() - event.atMillis
        if (age < FLASH_MS) {
            flash = outcome
            delay(FLASH_MS - age)
            flash = null
        }
    }
    return when {
        live -> ControlState.LIVE
        event?.outcome == Outcome.RUNNING -> ControlState.RUNNING
        else ->
            when (flash) {
                Outcome.OK -> ControlState.OK
                Outcome.HTTP_ERROR -> ControlState.HTTP_ERROR
                Outcome.FAILED -> ControlState.FAILED
                Outcome.INFO -> ControlState.INFO
                Outcome.RUNNING, null -> ControlState.IDLE
            }
    }
}

private val ControlState.isResult: Boolean
    get() = this == ControlState.OK || this == ControlState.HTTP_ERROR || this == ControlState.FAILED || this == ControlState.INFO

internal enum class ControlStyle { NEUTRAL, ACCENT, GHOST }

private data class ControlColors(val background: Color, val content: Color, val border: Color)

@Composable
private fun colorsFor(state: ControlState, style: ControlStyle): ControlColors {
    val p = palette
    return when (state) {
        ControlState.IDLE ->
            when (style) {
                ControlStyle.NEUTRAL -> ControlColors(p.surfaceMuted, p.text, p.surfaceMuted)
                ControlStyle.ACCENT -> ControlColors(p.accentContainer, p.onAccentContainer, p.accentContainer)
                ControlStyle.GHOST -> ControlColors(Color.Transparent, p.text, Color.Transparent)
            }
        ControlState.RUNNING -> ControlColors(p.accentContainer, p.onAccentContainer, p.accentContainer)
        ControlState.LIVE, ControlState.OK -> ControlColors(p.accentContainer, p.onAccentContainer, p.accent)
        ControlState.HTTP_ERROR -> ControlColors(p.warnContainer, p.warn, p.warn)
        ControlState.FAILED -> ControlColors(p.errorContainer, p.error, p.error)
        ControlState.INFO -> ControlColors(p.slateContainer, p.slate, p.slate)
    }
}

/**
 * A button that shows the life of its action: it shrinks while pressed, shows a
 * spinner while the action runs, then pops and shows a check, a warning, or a
 * cross in the outcome's color, and fades back.
 */
@Composable
internal fun ActionControl(
    tag: String,
    text: String,
    event: ActionEvent?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    live: String? = null,
    style: ControlStyle = ControlStyle.NEUTRAL,
    resultText: Boolean = false,
) {
    val state = rememberControlState(event, live = live != null)
    val colors = colorsFor(state, style)
    val background by animateColorAsState(colors.background, tween(COLOR_MS), label = "control-background")
    val content by animateColorAsState(colors.content, tween(COLOR_MS), label = "control-content")
    val border by animateColorAsState(colors.border, tween(COLOR_MS), label = "control-border")

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) PRESSED_SCALE else 1f, spring(stiffness = 800f), label = "control-press")
    val pop = remember { Animatable(1f) }
    LaunchedEffect(state) {
        if (state.isResult) {
            pop.animateTo(POP_SCALE, tween(POP_IN_MS))
            pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 500f))
        }
    }

    val shown =
        when {
            live != null -> live
            resultText && state == ControlState.OK -> "Done"
            resultText && state == ControlState.FAILED -> "Failed"
            resultText && state == ControlState.HTTP_ERROR -> "Error"
            else -> text
        }
    Row(
        modifier =
            modifier
                .graphicsLayer {
                    scaleX = pop.value * pressScale
                    scaleY = pop.value * pressScale
                }
                .clip(ControlShape)
                .background(background)
                .border(1.dp, border, ControlShape)
                .clickable(interactionSource = interaction, indication = ripple(color = content), role = Role.Button, onClick = onClick)
                .testTag(tag)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StateGlyph(state, content)
        AnimatedContent(
            targetState = shown,
            transitionSpec = { fadeIn(tween(COLOR_MS)) togetherWith fadeOut(tween(COLOR_MS)) using SizeTransform(clip = false) },
            label = "control-text",
        ) { value ->
            Text(value, style = SampleText.chip, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun ActionChip(action: DemoAction, event: ActionEvent?, onRun: (DemoAction) -> Unit) {
    val live = action.live?.collectAsState()?.value
    ActionControl(tag = action.label, text = action.chip, event = event, onClick = { onRun(action) }, live = live)
}

@Composable
private fun StateGlyph(state: ControlState, color: Color) {
    AnimatedContent(
        targetState = state,
        transitionSpec = {
            (scaleIn(spring(dampingRatio = 0.45f, stiffness = 600f), initialScale = 0.3f) + fadeIn()) togetherWith
                (scaleOut(targetScale = 0.3f) + fadeOut()) using SizeTransform(clip = false)
        },
        label = "control-glyph",
    ) { target ->
        when (target) {
            ControlState.IDLE -> Spacer(Modifier.width(0.dp))
            ControlState.RUNNING ->
                Box(Modifier.padding(end = 7.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), color = color, strokeWidth = 1.6.dp)
                }
            ControlState.LIVE -> PulseDot(color, Modifier.padding(end = 7.dp))
            else -> Icon(target.icon(), contentDescription = null, tint = color, modifier = Modifier.padding(end = 5.dp).size(15.dp))
        }
    }
}

private fun ControlState.icon(): ImageVector =
    when (this) {
        ControlState.HTTP_ERROR -> SampleIcons.Alert
        ControlState.FAILED -> SampleIcons.Close
        ControlState.INFO -> SampleIcons.Info
        else -> SampleIcons.Check
    }

/** A dot that breathes while something runs. */
@Composable
internal fun PulseDot(color: Color, modifier: Modifier = Modifier, size: Dp = 7.dp, pulsing: Boolean = true) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (pulsing) 0.3f else 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse),
        label = "pulse-alpha",
    )
    Box(
        modifier
            .size(size)
            .graphicsLayer { this.alpha = alpha }
            .background(color, CircleShape),
    )
}

/** A small rounded label, such as `RUNNING` or `DEBUG`. */
@Composable
internal fun Pill(text: String, content: Color, background: Color, modifier: Modifier = Modifier, dot: Boolean = false, pulsing: Boolean = false) {
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).background(background).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (dot) PulseDot(content, size = 6.dp, pulsing = pulsing)
        Text(text, style = SampleText.eyebrow, color = content)
    }
}

private val ControlShape = RoundedCornerShape(10.dp)
private const val COLOR_MS = 220
private const val PRESSED_SCALE = 0.94f
private const val POP_SCALE = 1.07f
private const val POP_IN_MS = 90
private const val PULSE_MS = 700
