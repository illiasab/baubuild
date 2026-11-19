package com.vesper.baubuild.feature.auth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import baubuild.app.shared.generated.resources.Res
import com.vesper.baubuild.designsystem.BauColors
import io.github.alexzhirkevich.compottie.Compottie
import io.github.alexzhirkevich.compottie.LottieCompositionSpec
import io.github.alexzhirkevich.compottie.animateLottieCompositionAsState
import io.github.alexzhirkevich.compottie.rememberLottieComposition
import io.github.alexzhirkevich.compottie.rememberLottiePainter

/**
 * The isometric BAU BUILD tower from the welcome hero: navy blocks drop in one by one, bounce, the yellow
 * block lands last, then the tower fades and rebuilds (Lottie port of the design's `bbdrop` keyframes,
 * `composeResources/files/welcome_blocks.json`). With reduced motion the finished tower is shown still.
 */
@Composable
internal fun BrandBlocksAnimation(modifier: Modifier = Modifier) {
    val reducedMotion = rememberPrefersReducedMotion()
    val composition by rememberLottieComposition {
        LottieCompositionSpec.JsonString(Res.readBytes("files/welcome_blocks.json").decodeToString())
    }
    val progress by animateLottieCompositionAsState(
        composition = composition,
        isPlaying = !reducedMotion,
        iterations = Compottie.IterateForever,
    )
    Image(
        painter = rememberLottiePainter(
            composition = composition,
            progress = { if (reducedMotion) STATIC_FRAME else progress },
        ),
        contentDescription = null, // decorative
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}

/** ~5.5 s into the 7 s loop: every block has landed and none has started fading. */
private const val STATIC_FRAME = 0.78f

/** The BAU BUILD mark: two solid yellow blocks and a dashed "next block" outline (28×28 design grid). */
@Composable
internal fun BauBuildMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / 28f
        val r = CornerRadius(1f * u)
        drawRoundRect(BauColors.Yellow, Offset(2 * u, 16 * u), Size(24 * u, 9 * u), r)
        drawRoundRect(BauColors.Yellow, Offset(2 * u, 4 * u), Size(11 * u, 9 * u), r)
        drawRoundRect(
            BauColors.Yellow, Offset(15.75f * u, 4.75f * u), Size(9.5f * u, 7.5f * u), r,
            style = Stroke(width = 1.5f * u, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.5f * u, 2f * u))),
        )
    }
}
