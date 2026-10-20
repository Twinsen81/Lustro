package io.github.twinsen81.lustro.sample.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Line icons on a 24-unit grid, drawn with a round stroke. They are a few paths
 * each, so the sample needs no icon library.
 */
internal object SampleIcons {
    private const val CIRCLE = "M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0z"

    val Globe = lineIcon("globe", CIRCLE, "M3 12h18", "M12 3c2.5 2.6 3.8 5.6 3.8 9s-1.3 6.4-3.8 9", "M12 3c-2.5 2.6-3.8 5.6-3.8 9s1.3 6.4 3.8 9")
    val Lines = lineIcon("lines", "M5 7h14", "M5 12h10", "M5 17h12")
    val Flag = lineIcon("flag", "M6 21V4", "M6 4.5h11l-2.5 4.25L17 13H6")
    val Terminal = lineIcon("terminal", "M4 4.5h16a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-13a1 1 0 0 1 1-1z", "M7.5 9.5l3 2.5-3 2.5", "M13 15h4")
    val ArrowUpRight = lineIcon("arrow-up-right", "M8 16L16 8", "M9.5 8H16v6.5")
    val ChevronLeft = lineIcon("chevron-left", "M14.5 6.5L9 12l5.5 5.5")
    val ChevronRight = lineIcon("chevron-right", "M10 7l5 5-5 5")
    val Search = lineIcon("search", "M17 11a6 6 0 1 1-12 0a6 6 0 1 1 12 0z", "M15.5 15.5L20 20")
    val Clock = lineIcon("clock", CIRCLE, "M12 7.5V12l3 2")
    val Check = lineIcon("check", "M5.5 12.5l4 4L18.5 7.5")
    val Close = lineIcon("close", "M7 7l10 10", "M17 7L7 17")
    val Alert = lineIcon("alert", "M12 6.5v7", "M12 17.5v0.01")
    val Info = lineIcon("info", "M12 11v6", "M12 7.5v0.01")
    val InfoCircle = lineIcon("info-circle", CIRCLE, "M12 11v5", "M12 7.75v0.01")

    private fun lineIcon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply {
                paths.forEach { path ->
                    addPath(
                        pathData = addPathNodes(path),
                        stroke = SolidColor(Color.Black),
                        strokeLineWidth = 2f,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }.build()
}
