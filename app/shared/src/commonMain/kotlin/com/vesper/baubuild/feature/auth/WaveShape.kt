package com.vesper.baubuild.feature.auth

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times

/**
 * Rectangle whose bottom edge is a sea wave (smooth crests and troughs) instead of rounded corners.
 * [waves] full wavelengths span the width; [phase] (0..1 of a wavelength) shifts them sideways, so two
 * layers with different phases read as overlapping waves.
 */
internal class WaveBottomShape(
    private val amplitude: Dp = 14.dp,
    private val waves: Float = 1.5f,
    private val phase: Float = 0f,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val amp = with(density) { amplitude.toPx() }
        val baseline = size.height - amp
        val wavelength = size.width / waves
        val half = wavelength / 2f
        // Start one wavelength left of the edge (shifted by phase) so the curve covers the full width.
        var x = -wavelength + phase * wavelength
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, baseline)
            lineTo(x + wavelength * 3, baseline) // overshoot, then draw waves right → left
            var cx = x + wavelength * 3
            var up = true
            while (cx > x) {
                val next = cx - half
                // Each half-wave dips to a trough (baseline + amp) or rises to a crest (baseline - amp).
                val peak = if (up) baseline - amp else baseline + amp
                cubicTo(cx - half * 0.35f, peak, next + half * 0.35f, peak, next, baseline)
                cx = next
                up = !up
            }
            lineTo(0f, baseline)
            close()
        }
        // Clip the overshoot to the shape's bounds.
        val bounds = Path().apply { addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height)) }
        return Outline.Generic(Path.combine(androidx.compose.ui.graphics.PathOperation.Intersect, path, bounds))
    }
}

/**
 * Navy background whose sea-wave bottom edge drifts sideways and gently swells, looping seamlessly
 * (one wavelength per [periodMillis]). Only the draw phase reads the animation, so the screen doesn't recompose
 * each frame. Static when the OS asks for reduced motion.
 */
@androidx.compose.runtime.Composable
internal fun Modifier.animatedWaveBackground(
    color: androidx.compose.ui.graphics.Color,
    amplitude: Dp = 14.dp,
    periodMillis: Int = 7_000,
): Modifier {
    if (rememberPrefersReducedMotion()) return background(color, WaveBottomShape(amplitude))
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "waves")
    val phase = transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(periodMillis, easing = androidx.compose.animation.core.LinearEasing),
        ),
        label = "wave-phase",
    )
    val swell = transition.animateFloat(
        initialValue = 0.85f, targetValue = 1.15f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(periodMillis / 2, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "wave-swell",
    )
    return drawBehind {
        val outline = WaveBottomShape(amplitude * swell.value, phase = phase.value).createOutline(size, layoutDirection, this)
        drawOutline(outline, color)
    }
}
