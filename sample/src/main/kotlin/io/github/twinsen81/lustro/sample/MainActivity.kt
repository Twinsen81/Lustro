package io.github.twinsen81.lustro.sample

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import io.github.twinsen81.lustro.sample.ui.SampleApp

/**
 * Launcher activity: a home screen with one card per module, and one screen of
 * actions for each module.
 *
 * A launch with a `request` extra runs the action with that label, on any
 * screen, so a script can fire a request without the UI. `--activity-single-top`
 * delivers the extra when the sample is already open:
 * `adb shell am start --activity-single-top -n io.github.twinsen81.lustro.sample/.MainActivity --es request "'GET /get'"`.
 */
public class MainActivity : ComponentActivity() {
    private val viewModel: SampleViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SampleApp(viewModel) { dark ->
                // The dark-mode-v2 flag, not the system setting, picks the theme,
                // so the system bar icons follow it here.
                DisposableEffect(dark) {
                    val style =
                        if (dark) {
                            SystemBarStyle.dark(Color.TRANSPARENT)
                        } else {
                            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                        }
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                    onDispose {}
                }
            }
        }
        // A recreated activity, after a rotation for example, must not run it again.
        if (savedInstanceState == null) runActionNamedIn(intent)
    }

    override fun onStart() {
        super.onStart()
        viewModel.onForeground()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onBackground()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        runActionNamedIn(intent)
    }

    private fun runActionNamedIn(intent: Intent) {
        intent.getStringExtra(EXTRA_REQUEST)?.let(viewModel::runByLabel)
    }

    private companion object {
        private const val EXTRA_REQUEST = "request"
    }
}
