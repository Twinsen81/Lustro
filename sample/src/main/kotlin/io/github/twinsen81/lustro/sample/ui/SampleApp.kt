package io.github.twinsen81.lustro.sample.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import io.github.twinsen81.lustro.sample.SampleFlags
import io.github.twinsen81.lustro.sample.SampleViewModel

/**
 * Two levels: the home screen, and one screen per module. The result bar stays
 * under both. [systemBars] lets the activity match the bar icons to the theme.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SampleApp(viewModel: SampleViewModel, systemBars: @Composable (dark: Boolean) -> Unit) {
    val flags = viewModel.flags?.collectAsState()?.value
    val dark = SampleFlags.isOn(flags, SampleFlags.DARK_THEME)
    systemBars(dark)
    SampleTheme(dark) {
        var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
        var historyOpen by rememberSaveable { mutableStateOf(false) }
        val recent by viewModel.events.recent.collectAsState()
        val latest by viewModel.events.latestByLabel.collectAsState()
        // Keeps each screen's scroll position and open groups while another one shows.
        val screens = rememberSaveableStateHolder()
        val open: (Screen) -> Unit = { screen = it }
        val back = { screen = Screen.HOME }

        BackHandler(enabled = historyOpen || screen != Screen.HOME) {
            if (historyOpen) historyOpen = false else back()
        }
        Column(
            Modifier
                .fillMaxSize()
                .background(palette.background)
                // Scripts find a control by its label: the test tags become resource ids.
                .semantics { testTagsAsResourceId = true }
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .imePadding(),
        ) {
            Box(Modifier.weight(1f).statusBarsPadding()) {
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        val direction = if (targetState == Screen.HOME) -1 else 1
                        (slideInHorizontally(tween(SLIDE_MS)) { direction * it / SLIDE_FRACTION } + fadeIn(tween(SLIDE_MS))) togetherWith
                            (slideOutHorizontally(tween(SLIDE_MS)) { -direction * it / SLIDE_FRACTION } + fadeOut(tween(FADE_OUT_MS)))
                    },
                    label = "screen",
                ) { target ->
                    screens.SaveableStateProvider(target.name) {
                        when (target) {
                            Screen.HOME -> HomeScreen(viewModel, flags, latest, open)
                            Screen.NETWORK -> NetworkScreen(viewModel, latest, back)
                            Screen.LOGS -> LogsScreen(viewModel, latest, back)
                            Screen.PREFS -> PrefsScreen(viewModel, latest, back)
                            Screen.SQLITE -> SqliteScreen(viewModel, latest, back)
                            Screen.WORK -> WorkScreen(viewModel, latest, back)
                            Screen.FLAGS -> FlagsScreen(viewModel, flags.orEmpty(), back)
                            Screen.CONSOLE -> ConsoleScreen(viewModel, latest, back)
                        }
                    }
                }
            }
            ResultBar(recent, historyOpen) { historyOpen = !historyOpen }
        }
    }
}

private const val SLIDE_MS = 280
private const val FADE_OUT_MS = 160
private const val SLIDE_FRACTION = 5
