package com.vesper.baubuild.feature.staff.tour

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vesper.baubuild.designsystem.BauColors

/*
 * Compose rendering of the staff tour (state: StaffTour.kt).
 *
 * Design (iOS · Staff screens): a dark banner near the top — yellow "N of 6" pill, the step's message, a "Skip"
 * pill — plus a spotlight on the step's target: everything else dimmed with Ink @ 45%, a 3dp yellow ring 6dp
 * outside the target. Neither the dim nor the ring takes touches, so the target stays tappable.
 * (The design's animated tapping-hand illustration is not reproduced.)
 */

private val DimColor = BauColors.Ink.copy(alpha = 0.45f)
private val RingWidth = 3.dp
private val RingOutset = 6.dp

@Stable
class StaffTourTargets internal constructor() {
    internal class Target(val owner: Any, val bounds: Rect, val cornerRadius: Dp)

    internal val targets = mutableStateMapOf<StaffTourStep, Target>()
    internal var hostOrigin by mutableStateOf(Offset.Zero)

    internal fun put(step: StaffTourStep, target: Target) { targets[step] = target }
    internal fun remove(step: StaffTourStep, owner: Any) {
        if (targets[step]?.owner === owner) targets.remove(step)
    }
}

private val LocalStaffTourTargets = staticCompositionLocalOf<StaffTourTargets?> { null }

/**
 * Wraps staff content so `Modifier.staffTourTarget` works inside it and draws the dim + ring for the current step's
 * target on top. [showBanner]: also draw the "N of 6" banner (Android shell). The iOS shell draws its banner natively
 * above its own tab bar, so iOS-hosted Compose roots pass false.
 */
@Composable
fun StaffTourHost(showBanner: Boolean, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val targets = remember { StaffTourTargets() }
    val step by StaffTour.step.collectAsStateWithLifecycle()
    Box(modifier.fillMaxSize().onGloballyPositioned { targets.hostOrigin = it.positionInRoot() }) {
        CompositionLocalProvider(LocalStaffTourTargets provides targets) { content() }
        val target = step?.let { targets.targets[it] }
        if (target != null) StaffTourSpotlight(target.bounds.translate(-targets.hostOrigin), target.cornerRadius)
        val current = step
        if (showBanner && current != null) {
            StaffTourBanner(
                step = current,
                onSkip = StaffTour::skip,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * Marks this element as the spotlight target of [step] (only shown while [step] is current, inside a
 * [StaffTourHost]). [cornerRadius] is the target's own corner radius; the ring adds 6dp around it.
 */
@Composable
fun Modifier.staffTourTarget(step: StaffTourStep, cornerRadius: Dp = 16.dp): Modifier {
    val targets = LocalStaffTourTargets.current ?: return this
    val owner = remember { Any() }
    DisposableEffect(targets, step) { onDispose { targets.remove(step, owner) } }
    return onGloballyPositioned { coords ->
        targets.put(step, StaffTourTargets.Target(owner, coords.boundsInRoot(), cornerRadius))
    }
}

/** Full-size, touch-transparent dim with a hole + yellow ring around [bounds] (host coordinates). */
@Composable
fun StaffTourSpotlight(bounds: Rect, cornerRadius: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val outset = RingOutset.toPx()
        val hole = bounds.inflate(outset)
        val radius = CornerRadius(cornerRadius.toPx() + outset)
        val path = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            addRoundRect(RoundRect(hole, radius))
        }
        drawPath(path, DimColor)
        val ring = RingWidth.toPx()
        val ringRect = hole.inflate(ring / 2)
        drawRoundRect(
            color = BauColors.Yellow,
            topLeft = ringRect.topLeft,
            size = ringRect.size,
            cornerRadius = CornerRadius(radius.x + ring / 2),
            style = Stroke(ring),
        )
    }
}

/** The dark "N of 6 · message · Skip" banner. */
@Composable
fun StaffTourBanner(step: StaffTourStep, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier
            .fillMaxWidth()
            .shadow(18.dp, shape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
            .background(BauColors.Ink.copy(alpha = 0.94f), shape)
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
            .semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "${step.number} of ${StaffTourStep.count}",
            color = BauColors.Ink,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(BauColors.Yellow, RoundedCornerShape(999.dp))
                .padding(horizontal = 9.dp, vertical = 4.dp),
        )
        Text(
            step.message,
            color = Color.White,
            fontSize = 14.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color.White.copy(alpha = 0.16f))
                .clickable(role = Role.Button, onClickLabel = "Skip the tour", onClick = onSkip)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Skip", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
