package com.vesper.baubuild.shell

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Stroke icons from the design's tab bar (22/24 viewport SVGs), drawn in code to avoid an icon dependency. */
internal object TabIcons {
    val Projects by lazy {
        strokeIcon(
            "Projects", 22f, 1.9f,
            "M4.5 12h13a1.5 1.5 0 0 1 1.5 1.5v3a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 3 16.5v-3A1.5 1.5 0 0 1 4.5 12z",
            "M4.5 4h4a1.5 1.5 0 0 1 1.5 1.5v3a1.5 1.5 0 0 1-1.5 1.5h-4A1.5 1.5 0 0 1 3 8.5v-3A1.5 1.5 0 0 1 4.5 4z",
            "M13.5 4h4a1.5 1.5 0 0 1 1.5 1.5v3a1.5 1.5 0 0 1-1.5 1.5h-4A1.5 1.5 0 0 1 12 8.5v-3A1.5 1.5 0 0 1 13.5 4z",
        )
    }
    val MyWork by lazy { strokeIcon("MyWork", 22f, 1.9f, "M8 6l-5 5 5 5M14 6l5 5-5 5") }
    val Review by lazy {
        strokeIcon("Review", 22f, 1.9f, "M3 11a8 8 0 1 0 16 0a8 8 0 1 0-16 0z", "M7.5 11.5l2.5 2.5 4.5-5")
    }
    val Profile by lazy {
        strokeIcon("Profile", 22f, 1.9f, "M7.5 8a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0-7 0z", "M4 19c1-3.5 4-5 7-5s6 1.5 7 5")
    }
    val Search by lazy {
        strokeIcon("Search", 24f, 2f, "M4 10.5a6.5 6.5 0 1 0 13 0a6.5 6.5 0 1 0-13 0z", "M15.5 15.5L20 20")
    }

    private fun strokeIcon(name: String, viewport: Float, stroke: Float, vararg paths: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = viewport.dp,
            defaultHeight = viewport.dp,
            viewportWidth = viewport,
            viewportHeight = viewport,
        ).apply {
            paths.forEach { d ->
                addPath(
                    pathData = addPathNodes(d),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = stroke,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}
