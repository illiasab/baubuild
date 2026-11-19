package com.vesper.baubuild.shell.staff

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Stroke icons from the staff tab bar design (docs__iOS_Staff_Overview.html), drawn in code. */
internal object StaffIcons {
    val Overview by lazy { strokeIcon("Overview", 22f, 1.9f, "M4 18v-6M9 18V5M14 18v-9M19 18v-4") }
    val Inquiries by lazy {
        strokeIcon("Inquiries", 22f, 1.9f, "M3 12.5L5.5 5h11L19 12.5V18H3z", "M3 12.5h4.5l1 2.5h5l1-2.5H19")
    }
    val Profile by lazy {
        strokeIcon("Profile", 22f, 1.9f, "M7.5 8a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0-7 0z", "M4 19c1-3.5 4-5 7-5s6 1.5 7 5")
    }
    val Plus by lazy { strokeIcon("Plus", 24f, 2.4f, "M12 5v14M5 12h14") }

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
