package com.vesper.baubuild.feature.projects

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vesper.baubuild.core.model.Project
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.BauSpacing

// ---------------------------------------------------------------------------------------------------------------
// Icons (stroked SVG paths from the design, so no icon-library dependency is needed)
// ---------------------------------------------------------------------------------------------------------------

internal object ProjectsIcons {
    val Back = strokeIcon("Back", 20f, "M12 4l-6 6 6 6", 2.2f)
    val ChevronRight = strokeIcon("Chevron", 8f, "M1 1l6 6-6 6", 2f, height = 14f)
    val Star = strokeIcon("Star", 20f, "M10 2.5l2.3 4.7 5.2.7-3.8 3.6.9 5.1L10 14.2l-4.6 2.4.9-5.1-3.8-3.6 5.2-.7z")
    val Book = strokeIcon("Book", 20f, "M10 4.5C8 3 5 3 3 4v11c2-1 5-1 7 .5 2-1.5 5-1.5 7-.5V4c-2-1-5-1-7 .5zM10 4.5v11")
    val Bolt = strokeIcon("Bolt", 20f, "M11 2L4 11.5h5L8 18l8-10h-5.5z")
    val Branch = strokeIcon("Repo", 22f, "M6 7.2v7.6M16 10.2c0 3-4 3-10 4", 2f, circles = listOf(Triple(6f, 5f, 2.2f), Triple(6f, 17f, 2.2f), Triple(16f, 8f, 2.2f)))
    val Plus = strokeIcon("Plus", 20f, "M10 4v12M4 10h12", 2f)
    val Check = strokeIcon("Check", 20f, "M4.5 10.5l3.5 3.5 7.5-8", 2.2f)
    val Clock = strokeIcon("Clock", 20f, "M10 6v4.5l3 2", 2f, circles = listOf(Triple(10f, 10f, 7.5f)))
    val Bell = strokeIcon("Bell", 20f, "M5 8a5 5 0 0 1 10 0c0 4 1.5 5 1.5 5h-13S5 12 5 8zM8.5 16a1.6 1.6 0 0 0 3 0")
    val Review = strokeIcon("Review", 20f, "M5 2.5h10v15H5zM7.5 10l2 2 3.5-4")

    private fun strokeIcon(
        name: String,
        width: Float,
        pathData: String,
        strokeWidth: Float = 1.8f,
        height: Float = width,
        circles: List<Triple<Float, Float, Float>> = emptyList(),
    ): ImageVector = ImageVector.Builder(
        name = name, defaultWidth = width.dp, defaultHeight = height.dp, viewportWidth = width, viewportHeight = height,
    ).apply {
        val stroke = SolidColor(Color.Black)
        circles.forEach { (cx, cy, r) ->
            addPath(
                pathData = addPathNodes("M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0"),
                stroke = stroke, strokeLineWidth = strokeWidth, strokeLineCap = StrokeCap.Round,
            )
        }
        addPath(
            pathData = addPathNodes(pathData),
            stroke = stroke, strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
    }.build()
}

// ---------------------------------------------------------------------------------------------------------------
// Chrome
// ---------------------------------------------------------------------------------------------------------------

/** 44pt frosted circle button from the design (back, notifications…). */
@Composable
internal fun GlassCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    val shape = RoundedCornerShape(size / 2)
    Box(
        modifier
            .size(size)
            .shadow(8.dp, shape, ambientColor = BauColors.Ink.copy(alpha = 0.14f), spotColor = BauColors.Ink.copy(alpha = 0.14f))
            .clip(shape)
            .background(Color.White.copy(alpha = 0.85f))
            .border(1.dp, Color.White, shape)
            .clickable(onClickLabel = contentDescription, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = BauColors.Ink, modifier = Modifier.size(20.dp)) }
}

/** Header bell: [GlassCircleButton] with a small dot badge when there is something unread. */
@Composable
internal fun NotificationsBellButton(hasUnread: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier) {
        GlassCircleButton(
            ProjectsIcons.Bell,
            contentDescription = "Notifications",
            onClick = onClick,
        )
        if (hasUnread) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 9.dp, end = 10.dp).size(10.dp)
                    .clip(RoundedCornerShape(5.dp)).background(BauColors.Danger)
                    .border(1.5.dp, Color.White, RoundedCornerShape(5.dp))
                    .semantics { contentDescription = "Unread notifications" },
            )
        }
    }
}

/** Back button row used at the top of every pushed screen. */
@Composable
internal fun ProjectsTopBar(onBack: () -> Unit, backLabel: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassCircleButton(ProjectsIcons.Back, backLabel, onBack)
    }
}

/** Big title block of a pushed list screen. */
@Composable
internal fun ProjectsScreenHeader(title: String, subtitle: String?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            title, fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 41.sp, letterSpacing = (-0.6).sp,
            color = BauColors.Ink, modifier = Modifier.semantics { heading() },
        )
        if (!subtitle.isNullOrBlank()) Text(subtitle, fontSize = 15.sp, lineHeight = 20.sp, color = BauColors.TextSecondary)
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Project row
// ---------------------------------------------------------------------------------------------------------------

private val CardShape = RoundedCornerShape(24.dp)

/** White card with soft shadow, radius 24 (design's list cards). */
internal fun Modifier.projectsCard(onClick: (() -> Unit)? = null, label: String? = null): Modifier = this
    .shadow(10.dp, CardShape, ambientColor = BauColors.Ink.copy(alpha = 0.06f), spotColor = BauColors.Ink.copy(alpha = 0.06f))
    .clip(CardShape)
    .background(BauColors.Surface)
    .border(1.dp, BauColors.Line, CardShape)
    .then(if (onClick != null) Modifier.clickable(onClickLabel = label, role = Role.Button, onClick = onClick) else Modifier)

@Composable
internal fun ProjectRow(project: Project, viewerScore: Int, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val eligibility = project.eligibilityFor(viewerScore)
    Column(
        modifier.fillMaxWidth().projectsCard(onOpen, "Open ${project.title}").padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    project.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp,
                    letterSpacing = (-0.3).sp, color = BauColors.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(project.summary.ifBlank { project.owner }, fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.TextSecondary)
            }
            Text("$${project.bountyUsd}", fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp, color = BauColors.Ink)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MutedChip(project.difficulty.label)
                MutedChip(project.scoreChipLabel)
            }
            // Visual affordance only — the whole card opens the "before joining" detail, matching the design.
            ActionPill(project.joinCtaLabel, enabled = eligibility == JoinEligibility.Eligible)
        }
        if (eligibility is JoinEligibility.BelowMinScore) {
            Text(
                "Needs score ${eligibility.required}+ · you have ${eligibility.current}",
                fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.WarningInk,
            )
        }
    }
}

@Composable
internal fun MutedChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.clip(RoundedCornerShape(999.dp)).background(BauColors.Paper).padding(horizontal = 10.dp, vertical = 5.dp),
        fontSize = 12.sp, fontWeight = FontWeight.Medium, color = BauColors.TextSecondary,
    )
}

@Composable
internal fun ActionPill(text: String, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.clip(RoundedCornerShape(999.dp))
            .background(if (enabled) BauColors.Yellow else BauColors.Line)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        fontSize = 13.sp, fontWeight = FontWeight.Bold,
        color = if (enabled) BauColors.Ink else BauColors.TextTertiary,
    )
}

@Composable
internal fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier.fillMaxWidth().padding(vertical = BauSpacing.xxl),
        style = MaterialTheme.typography.bodyMedium, color = BauColors.TextSecondary,
    )
}

/** Inline error under a write action (join / submit). Never replaces the screen. */
@Composable
internal fun InlineError(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(BauColors.WarningSoft)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        fontSize = 14.sp, lineHeight = 19.sp, color = BauColors.WarningInk,
    )
}

// ---------------------------------------------------------------------------------------------------------------
// Isometric cubes from the urgent card (viewBox 0 0 120 148)
// ---------------------------------------------------------------------------------------------------------------

/**
 * Urgent-card art: the brand blocks falling and stacking on top of each other (same Lottie as Welcome),
 * looping. Shows the finished stack when the OS asks for reduced motion.
 */
@Composable
internal fun CubeStack(modifier: Modifier = Modifier) {
    com.vesper.baubuild.feature.auth.BrandBlocksAnimation(modifier.width(104.dp).height(128.dp))
}

private fun cubeFace(vararg pts: Float): Path = Path().apply {
    moveTo(pts[0], pts[1])
    var i = 2
    while (i < pts.size) { lineTo(pts[i], pts[i + 1]); i += 2 }
    close()
}

@Composable
internal fun VerticalSpace(height: Dp) = Spacer(Modifier.height(height))
