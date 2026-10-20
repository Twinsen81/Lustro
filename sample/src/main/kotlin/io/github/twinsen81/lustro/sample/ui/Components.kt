package io.github.twinsen81.lustro.sample.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.twinsen81.lustro.sample.R
import io.github.twinsen81.lustro.sample.Tone

internal val CardShape = RoundedCornerShape(16.dp)
internal val ScreenPadding = 20.dp

/** A white card with a hairline border. */
@Composable
internal fun SampleCard(
    modifier: Modifier = Modifier,
    background: Color = palette.surface,
    border: Color = palette.border,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().clip(CardShape).background(background).border(1.dp, border, CardShape),
        content = content,
    )
}

/** A rounded square with an icon in a tone's colors. [background] replaces the tone's container color. */
@Composable
internal fun IconTile(
    icon: ImageVector,
    tone: Tone,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    background: Color? = null,
) {
    val (content, container) = palette.tone(tone)
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(background ?: container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(iconSize))
    }
}

@Composable
internal fun HomeBar() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(palette.logoBackground)) {
            // The launcher foreground has the mark in its middle two thirds; scale it to fill.
            Image(
                painter = painterResource(R.mipmap.ic_launcher_foreground),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(40.dp).graphicsLayer {
                    scaleX = LOGO_SCALE
                    scaleY = LOGO_SCALE
                },
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Lustro", style = SampleText.barTitle)
            Text("SAMPLE APP", style = SampleText.eyebrow, color = palette.textFaint)
        }
    }
}

@Composable
internal fun SubScreenBar(title: String, subtitle: String, onBack: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PressableBox(onClick = onBack, modifier = Modifier.testTag("Back")) {
            Icon(SampleIcons.ChevronLeft, contentDescription = "Back", tint = palette.text, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = SampleText.barTitle)
            Text(subtitle.uppercase(), style = SampleText.eyebrow, color = palette.textFaint)
        }
        trailing()
    }
}

/** A square bordered button for an icon, such as Back. */
@Composable
internal fun PressableBox(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(stiffness = 800f), label = "box-press")
    Box(
        modifier
            .size(36.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(10.dp))
            .background(palette.surface)
            .border(1.dp, palette.border, RoundedCornerShape(10.dp))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
internal fun ScreenHeader(title: String, subtitle: String) {
    Column(Modifier.padding(start = ScreenPadding, end = ScreenPadding, top = 6.dp, bottom = 18.dp)) {
        Text(title, style = SampleText.screenTitle)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, style = SampleText.body, color = palette.textMuted)
    }
}

@Composable
internal fun SectionLabel(text: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(start = ScreenPadding + 2.dp, end = ScreenPadding + 2.dp, top = 18.dp, bottom = 10.dp)) {
        Text(text, style = SampleText.eyebrow, color = palette.textMuted, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = SampleText.eyebrow, color = palette.textFaint)
    }
}

/** A card on the home screen that opens a module. */
@Composable
internal fun ModuleCard(
    icon: ImageVector,
    tone: Tone,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    meta: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, spring(stiffness = 700f), label = "card-press")
    val border by animateColorAsState(if (pressed) palette.accent else palette.border, tween(120), label = "card-border")
    SampleCard(
        modifier =
            Modifier
                .padding(horizontal = ScreenPadding, vertical = 6.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
                .testTag(title),
        border = border,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, tone)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = SampleText.cardTitle)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = SampleText.small, color = palette.textMuted)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), content = meta)
            }
        }
    }
}

/** The sample's switch colors; the Material defaults do not match the palette. */
@Composable
internal fun sampleSwitchColors(): SwitchColors {
    val p = palette
    return SwitchDefaults.colors(
        checkedTrackColor = p.accent,
        checkedThumbColor = p.onAccent,
        checkedBorderColor = p.accent,
        uncheckedTrackColor = p.surfaceMuted,
        uncheckedThumbColor = p.textFaint,
        uncheckedBorderColor = p.border,
    )
}

/** A short line of accent text under a module card's subtitle. */
@Composable
internal fun MetaText(text: String, color: Color = palette.accent) {
    Text(text, style = SampleText.small.copy(fontWeight = FontWeight.SemiBold), color = color)
}

private const val LOGO_SCALE = 1.55f
