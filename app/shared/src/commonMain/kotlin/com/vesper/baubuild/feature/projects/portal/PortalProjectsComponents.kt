package com.vesper.baubuild.feature.projects.portal

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vesper.baubuild.core.model.Difficulty
import com.vesper.baubuild.core.model.JoinPolicy
import com.vesper.baubuild.core.model.Project
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.DisplayFamily
import com.vesper.baubuild.designsystem.MonoFamily
import com.vesper.baubuild.feature.projects.CubeStack
import com.vesper.baubuild.feature.projects.JoinEligibility
import com.vesper.baubuild.feature.projects.ProjectsIcons
import com.vesper.baubuild.feature.projects.eligibilityFor
import com.vesper.baubuild.feature.projects.joinCtaLabel
import com.vesper.baubuild.feature.projects.label
import com.vesper.baubuild.feature.projects.scoreChipLabel
import com.vesper.baubuild.feature.projects.urgentEyebrow

/** Web portal palette (values from the portal design that are not in [BauColors]). */
internal object PortalColors {
    val Field = Color(0xFFC5CAD3)        // input / control borders
    val CardBorder = Color(0xFFD9DCD3)
    val ChipBorder = Color(0xFFE3E6DE)
    val OnDarkMuted = Color(0xFFC9D1E2)
    val HoverBorder = Color(0xFF9AA2B1)
}

internal object PortalIcons {
    val Close = icon("Close", "M5 5l10 10M15 5L5 15", 2f)
    val Search = icon("Search", "M13.5 13.5L17 17", 1.8f, circle = Triple(8.5f, 8.5f, 5.5f))

    private fun icon(name: String, path: String, stroke: Float, circle: Triple<Float, Float, Float>? = null): ImageVector =
        ImageVector.Builder(name, 20.dp, 20.dp, 20f, 20f).apply {
            val brush = SolidColor(Color.Black)
            circle?.let { (cx, cy, r) ->
                addPath(
                    addPathNodes("M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0"),
                    stroke = brush, strokeLineWidth = stroke,
                )
            }
            addPath(addPathNodes(path), stroke = brush, strokeLineWidth = stroke, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
}

internal val ControlShape = RoundedCornerShape(6.dp)
internal val FieldShape = RoundedCornerShape(8.dp)
private val CardShape = RoundedCornerShape(12.dp)
private val HeroShape = RoundedCornerShape(14.dp)

private val MonoCaps = TextStyle(fontFamily = MonoFamily, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.72.sp)

/** Pointer cursor for clickable things on desktop (no-op on touch). */
internal fun Modifier.handCursor(): Modifier = pointerHoverIcon(PointerIcon.Hand)

// ---------------------------------------------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------------------------------------------

@Composable
internal fun PortalHeaderTitle(modifier: Modifier = Modifier) {
    Text(
        "Projects", modifier.semantics { heading() },
        fontFamily = DisplayFamily, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.45).sp, color = BauColors.Ink,
    )
}

@Composable
internal fun PortalSearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(fontSize = 15.sp, color = BauColors.Ink),
        cursorBrush = SolidColor(BauColors.Blue),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = modifier.widthIn(max = 260.dp).fillMaxWidth().height(44.dp)
            .semantics { contentDescription = "Search projects" },
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().height(44.dp).clip(FieldShape).background(BauColors.Surface)
                    .border(1.dp, PortalColors.Field, FieldShape).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(PortalIcons.Search, null, tint = BauColors.TextTertiary, modifier = Modifier.size(18.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text("Search projects", fontSize = 15.sp, color = BauColors.TextTertiary, maxLines = 1)
                    inner()
                }
                if (value.isNotEmpty()) {
                    Icon(
                        PortalIcons.Close, "Clear search", tint = BauColors.TextSecondary,
                        modifier = Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).handCursor()
                            .clickable(role = Role.Button) { onValueChange("") }.padding(6.dp),
                    )
                }
            }
        },
    )
}

/** 44dp square outlined icon button from the portal header. */
@Composable
internal fun PortalIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, badge: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier.size(44.dp).clip(FieldShape)
            .background(if (hovered) BauColors.Paper else BauColors.Surface)
            .border(1.dp, if (hovered) PortalColors.HoverBorder else PortalColors.Field, FieldShape)
            .handCursor()
            .clickable(interactionSource = interaction, indication = null, onClickLabel = contentDescription, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = if (badge) "$contentDescription, unread" else contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = BauColors.Ink, modifier = Modifier.size(20.dp))
        if (badge) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 9.dp, end = 10.dp).size(9.dp)
                    .clip(RoundedCornerShape(5.dp)).background(BauColors.Danger)
                    .border(1.5.dp, Color.White, RoundedCornerShape(5.dp)),
            )
        }
    }
}

internal val BellIcon: ImageVector get() = ProjectsIcons.Bell

// ---------------------------------------------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------------------------------------------

/** Portal CTA: yellow filled (primary) or ink-outlined (secondary), radius 6, min 44dp. */
@Composable
internal fun PortalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val active = enabled && !loading
    val bg = when {
        !enabled -> BauColors.Line
        primary -> if (hovered) BauColors.YellowLight else BauColors.Yellow
        else -> if (hovered) BauColors.Paper else Color.Transparent
    }
    Box(
        modifier.heightIn(min = 44.dp).clip(ControlShape).background(bg)
            .then(if (!primary && enabled) Modifier.border(1.5.dp, BauColors.Ink, ControlShape) else Modifier)
            .then(if (active) Modifier.handCursor() else Modifier)
            .clickable(enabled = active, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), color = BauColors.Ink, strokeWidth = 2.dp)
        } else {
            Text(
                text, fontSize = 15.sp, maxLines = 1,
                fontWeight = if (primary) FontWeight.Bold else FontWeight.SemiBold,
                color = if (enabled) BauColors.Ink else BauColors.TextTertiary,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Filters
// ---------------------------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PortalFilterBar(filters: PortalProjectFilters, onChange: (PortalProjectFilters) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(9.dp)).background(BauColors.Surface)
                .border(1.dp, PortalColors.Field, RoundedCornerShape(9.dp)).padding(3.dp)
                .semantics { contentDescription = "Difficulty" },
        ) {
            (listOf<Difficulty?>(null) + Difficulty.entries).forEach { d ->
                FilterButton(d?.label ?: "All", selected = filters.difficulty == d, outlined = false) { onChange(filters.copy(difficulty = d)) }
            }
        }
        FilterButton("My Classes", filters.myClasses, outlined = true) { onChange(filters.copy(myClasses = !filters.myClasses)) }
        FilterButton("Urgent", filters.urgent, outlined = true) { onChange(filters.copy(urgent = !filters.urgent)) }
        FilterButton("Open to my score", filters.openToMyScore, outlined = true) { onChange(filters.copy(openToMyScore = !filters.openToMyScore)) }
    }
}

@Composable
private fun FilterButton(text: String, selected: Boolean, outlined: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg = when {
        selected -> BauColors.Ink
        hovered -> BauColors.Paper
        outlined -> BauColors.Surface
        else -> Color.Transparent
    }
    Box(
        Modifier.heightIn(min = 44.dp).clip(ControlShape).background(bg)
            .then(if (outlined) Modifier.border(1.dp, if (selected) BauColors.Ink else PortalColors.Field, ControlShape) else Modifier)
            .handCursor()
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, fontSize = 14.sp, maxLines = 1,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) Color.White else BauColors.Ink,
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Urgent hero
// ---------------------------------------------------------------------------------------------------------------

@Composable
internal fun UrgentHeroCard(hero: UrgentHero, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val project = hero.project
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val showCubes = maxWidth >= 560.dp
        Row(
            Modifier.fillMaxWidth().clip(HeroShape).background(BauColors.Ink).padding(horizontal = 32.dp, vertical = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(hero.inquiry.urgentEyebrow.uppercase(), style = MonoCaps, color = BauColors.Yellow)
                Text(
                    project.title, modifier = Modifier.semantics { heading() },
                    fontFamily = DisplayFamily, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp,
                    letterSpacing = (-0.42).sp, color = Color.White,
                )
                Text(
                    project.description.ifBlank { project.summary }, modifier = Modifier.widthIn(max = 560.dp),
                    fontSize = 15.sp, lineHeight = 23.sp, color = PortalColors.OnDarkMuted,
                )
                PortalButton(
                    if (project.bountyUsd > 0) "View project · $${project.bountyUsd}" else "View project",
                    onClick = onOpen,
                )
            }
            if (showCubes) CubeStack(Modifier.size(width = 130.dp, height = 160.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Project card
// ---------------------------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PortalProjectCard(project: Project, viewerScore: Int, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val elevation by animateDpAsState(if (hovered) 10.dp else 0.dp, label = "card-elevation")
    val border by animateColorAsState(if (hovered) PortalColors.HoverBorder else PortalColors.CardBorder, label = "card-border")
    val eligible = project.eligibilityFor(viewerScore) == JoinEligibility.Eligible

    Column(
        modifier.fillMaxWidth()
            .shadow(elevation, CardShape, ambientColor = BauColors.Ink.copy(alpha = 0.12f), spotColor = BauColors.Ink.copy(alpha = 0.12f))
            .clip(CardShape).background(BauColors.Surface).border(BorderStroke(1.dp, border), CardShape)
            .handCursor()
            .clickable(interactionSource = interaction, indication = null, onClickLabel = "Open ${project.title}", role = Role.Button, onClick = onOpen)
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                project.owner.uppercase(), Modifier.weight(1f), style = MonoCaps, color = BauColors.TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text("$${project.bountyUsd}", fontFamily = DisplayFamily, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = BauColors.Ink)
        }
        // Fixed line counts keep cards in a grid row the same height (lazy grids don't stretch rows).
        Text(
            project.title, fontFamily = DisplayFamily, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp,
            color = BauColors.Ink, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Text(
            project.description.ifBlank { project.summary }, fontSize = 14.sp, lineHeight = 21.sp, color = BauColors.TextSecondary,
            minLines = 3, maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MetaChip(project.difficulty.label)
            MetaChip(project.scoreChipLabel)
            MetaChip(portalDueChip(project.dueInDays))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(PortalColors.ChipBorder))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (eligible) project.portalJoiningLabel else "Needs score ${project.minScore}+",
                Modifier.weight(1f), fontSize = 13.sp, maxLines = 2,
                color = if (eligible) BauColors.TextSecondary else BauColors.WarningInk,
            )
            // Opens the detail, where the join runs with progress / errors.
            PortalButton(project.joinCtaLabel, onClick = onOpen, primary = project.joinPolicy == JoinPolicy.Open, enabled = eligible)
        }
    }
}

@Composable
internal fun MetaChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.clip(RoundedCornerShape(999.dp)).background(BauColors.Paper)
            .border(1.dp, PortalColors.ChipBorder, RoundedCornerShape(999.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
        fontFamily = MonoFamily, fontSize = 12.sp, color = BauColors.Ink, maxLines = 1,
    )
}

@Composable
internal fun MonoCapsLabel(text: String, modifier: Modifier = Modifier, color: Color = BauColors.TextSecondary) =
    Text(text.uppercase(), modifier, style = MonoCaps, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)

@Composable
internal fun PortalEmptyState(message: String, onClear: (() -> Unit)?, modifier: Modifier = Modifier, actionLabel: String = "Clear filters") {
    Column(
        modifier.fillMaxWidth().clip(CardShape).background(BauColors.Surface).border(1.dp, PortalColors.CardBorder, CardShape)
            .padding(vertical = 48.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(message, fontSize = 15.sp, color = BauColors.TextSecondary)
        if (onClear != null) PortalButton(actionLabel, onClick = onClear, primary = false)
    }
}
