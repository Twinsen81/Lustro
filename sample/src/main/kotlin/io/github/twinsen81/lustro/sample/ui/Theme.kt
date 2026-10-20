package io.github.twinsen81.lustro.sample.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.twinsen81.lustro.sample.Tone

@Immutable
internal data class Palette(
    val background: Color,
    val surface: Color,
    val surfaceMuted: Color,
    val border: Color,
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    val accent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    val warn: Color,
    val warnContainer: Color,
    val error: Color,
    val errorContainer: Color,
    val violet: Color,
    val violetContainer: Color,
    val orange: Color,
    val orangeContainer: Color,
    val slate: Color,
    val slateContainer: Color,
    val logoBackground: Color,
)

internal val LightPalette =
    Palette(
        background = Color(0xFFF5F7F8),
        surface = Color(0xFFFFFFFF),
        surfaceMuted = Color(0xFFF0F3F5),
        border = Color(0xFFE3E8EC),
        text = Color(0xFF0F1B2A),
        textMuted = Color(0xFF5B6B7B),
        textFaint = Color(0xFF8A98A6),
        accent = Color(0xFF0E9F7E),
        onAccent = Color(0xFFFFFFFF),
        accentContainer = Color(0xFFE2F5EE),
        onAccentContainer = Color(0xFF0B6E57),
        warn = Color(0xFFB7791F),
        warnContainer = Color(0xFFFDF3E1),
        error = Color(0xFFD64545),
        errorContainer = Color(0xFFFCEBEB),
        violet = Color(0xFF6B5BD6),
        violetContainer = Color(0xFFEFEDFC),
        orange = Color(0xFFDD8A2E),
        orangeContainer = Color(0xFFFFF2E3),
        slate = Color(0xFF4B5C70),
        slateContainer = Color(0xFFEDF1F5),
        logoBackground = Color(0xFF101A20),
    )

internal val DarkPalette =
    Palette(
        background = Color(0xFF0B141C),
        surface = Color(0xFF121E29),
        surfaceMuted = Color(0xFF1A2834),
        border = Color(0xFF223343),
        text = Color(0xFFE7EEF4),
        textMuted = Color(0xFF9AABBA),
        textFaint = Color(0xFF6C7E8F),
        accent = Color(0xFF34D1A6),
        onAccent = Color(0xFF04261D),
        accentContainer = Color(0xFF12352D),
        onAccentContainer = Color(0xFF7BE8C8),
        warn = Color(0xFFF0B355),
        warnContainer = Color(0xFF3A2D15),
        error = Color(0xFFFF7A7A),
        errorContainer = Color(0xFF3D1E22),
        violet = Color(0xFFA79BFF),
        violetContainer = Color(0xFF2A2547),
        orange = Color(0xFFF5A85A),
        orangeContainer = Color(0xFF3A2A17),
        slate = Color(0xFFA9B8C8),
        slateContainer = Color(0xFF1F2D3A),
        logoBackground = Color(0xFF060C11),
    )

internal val LocalPalette = compositionLocalOf { LightPalette }

internal val palette: Palette
    @Composable get() = LocalPalette.current

internal fun Palette.tone(tone: Tone): Pair<Color, Color> =
    when (tone) {
        Tone.ACCENT -> accent to accentContainer
        Tone.ERROR -> error to errorContainer
        Tone.VIOLET -> violet to violetContainer
        Tone.ORANGE -> orange to orangeContainer
        Tone.SLATE -> slate to slateContainer
    }

internal object SampleText {
    val hero = TextStyle(fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp)
    val screenTitle = TextStyle(fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
    val barTitle = TextStyle(fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
    val cardTitle = TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    val small = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val chip = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    val eyebrow = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp)
    val mono = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace)
}

@Composable
internal fun SampleTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = animatedPalette(if (dark) DarkPalette else LightPalette)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme =
        base.copy(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            primaryContainer = colors.accentContainer,
            onPrimaryContainer = colors.onAccentContainer,
            background = colors.background,
            onBackground = colors.text,
            surface = colors.surface,
            onSurface = colors.text,
            surfaceVariant = colors.surfaceMuted,
            onSurfaceVariant = colors.textMuted,
            outline = colors.border,
            error = colors.error,
        )
    CompositionLocalProvider(LocalPalette provides colors) {
        MaterialTheme(colorScheme = scheme) {
            CompositionLocalProvider(LocalContentColor provides colors.text, content = content)
        }
    }
}

// A flag flips the theme while the app is open, so the colors fade instead of jumping.
@Composable
private fun animatedPalette(target: Palette): Palette =
    Palette(
        background = target.background.animated(),
        surface = target.surface.animated(),
        surfaceMuted = target.surfaceMuted.animated(),
        border = target.border.animated(),
        text = target.text.animated(),
        textMuted = target.textMuted.animated(),
        textFaint = target.textFaint.animated(),
        accent = target.accent.animated(),
        onAccent = target.onAccent.animated(),
        accentContainer = target.accentContainer.animated(),
        onAccentContainer = target.onAccentContainer.animated(),
        warn = target.warn.animated(),
        warnContainer = target.warnContainer.animated(),
        error = target.error.animated(),
        errorContainer = target.errorContainer.animated(),
        violet = target.violet.animated(),
        violetContainer = target.violetContainer.animated(),
        orange = target.orange.animated(),
        orangeContainer = target.orangeContainer.animated(),
        slate = target.slate.animated(),
        slateContainer = target.slateContainer.animated(),
        logoBackground = target.logoBackground.animated(),
    )

@Composable
private fun Color.animated(): Color = animateColorAsState(this, tween(THEME_FADE_MS), label = "palette").value

private const val THEME_FADE_MS = 450
