package com.vesper.baubuild.feature.profile

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import com.vesper.baubuild.core.model.ScoreTier
import com.vesper.baubuild.designsystem.BauColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Band fills from the design, ROOKIE → HONORED. */
private val BandColors = listOf(Color(0xFFAEB7CC), Color(0xFF5C7CFA), BauColors.BlueDeep, BauColors.Orange, BauColors.Yellow)
private val BandLabelColors = listOf(BauColors.Ink, BauColors.Ink, Color.White, BauColors.Ink, BauColors.Ink)
internal val GaugeMuted = Color(0xFFC9D1E2)

/** Angle (canvas degrees, clockwise from +x) for a score on the 0..500 half dial. */
internal fun gaugeAngle(score: Int): Float =
    180f + 180f * score.coerceIn(0, ScoreTier.MAX_SCORE) / ScoreTier.MAX_SCORE.toFloat()

/**
 * The BAU BUILD Score half-dial: five tier bands (0–500), tier labels along the arc, tick values and a needle.
 * Drawn in the design's 300×166 viewBox and scaled to the composable's width.
 */
@Composable
fun ScoreGauge(score: Int, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val tier = ScoreTier.of(score.coerceIn(0, ScoreTier.MAX_SCORE))
    Canvas(
        modifier.semantics {
            contentDescription = "Score gauge from 0 to ${ScoreTier.MAX_SCORE} in five bands: " +
                ScoreTier.entries.joinToString { it.name } + ". The needle is at $score, in the ${tier.name} band."
        },
    ) {
        val k = size.width / 300f
        val c = Offset(150f * k, 150f * k)
        val bandRadius = 97f * k
        val bandWidth = 46f * k
        val step = 180f / ScoreTier.entries.size
        val gap = 0.8f

        ScoreTier.entries.forEachIndexed { i, _ ->
            drawArc(
                color = BandColors[i],
                startAngle = 180f + step * i + gap,
                sweepAngle = step - 2 * gap,
                useCenter = false,
                topLeft = Offset(c.x - bandRadius, c.y - bandRadius),
                size = Size(bandRadius * 2, bandRadius * 2),
                style = Stroke(width = bandWidth, cap = StrokeCap.Butt),
            )
        }

        val labelStyle = TextStyle(fontSize = (11.5f * k).toSp(), fontWeight = FontWeight.Bold, letterSpacing = (0.5f * k).toSp())
        ScoreTier.entries.forEachIndexed { i, t ->
            val angle = 180f + step * (i + 0.5f)
            val p = polar(c, bandRadius, angle)
            val layout = measurer.measure(t.label, labelStyle.copy(color = BandLabelColors[i]))
            rotate(angle + 90f, pivot = p) {
                drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
            }
        }

        val tickStyle = TextStyle(fontSize = (11.5f * k).toSp(), color = GaugeMuted)
        for (i in 0..ScoreTier.entries.size) {
            val value = ScoreTier.MAX_SCORE / ScoreTier.entries.size * i
            val p = polar(c, 132f * k, 180f + step * i)
            val layout = measurer.measure(value.toString(), tickStyle)
            drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
        }

        val a = gaugeAngle(score)
        val tip = polar(c, 104f * k, a)
        val left = polar(c, 7f * k, a - 90f)
        val right = polar(c, 7f * k, a + 90f)
        drawPath(Path().apply { moveTo(tip.x, tip.y); lineTo(left.x, left.y); lineTo(right.x, right.y); close() }, Color.White)
        drawCircle(Color.White, radius = 12f * k, center = c)
        drawCircle(BauColors.Ink, radius = 5f * k, center = c)
    }
}

private fun polar(c: Offset, r: Float, degrees: Float): Offset {
    val rad = degrees * PI.toFloat() / 180f
    return Offset(c.x + r * cos(rad), c.y + r * sin(rad))
}
