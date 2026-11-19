package com.vesper.baubuild.feature.mywork.portal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.vesper.baubuild.core.model.ChangeStatus
import com.vesper.baubuild.core.model.TaskStatus
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.DisplayFamily
import com.vesper.baubuild.feature.mywork.workspace.label

/** Web portal colors that are not in [BauColors] (from the portal design's inline styles). */
internal object PortalColors {
    val CardBorder = Color(0xFFD9DCD3)
    val RowLine = Color(0xFFE3E6DE)
    val ControlBorder = Color(0xFFC5CAD3)
    val RowHover = Color(0xFFF7F8F3)
    val HoverBorder = Color(0xFF9AA2B1)
    val MentorBubble = Color(0xFF151B2E)
    val MentorBorder = Color(0xFF2C3757)
    val MentorText = Color(0xFFE6EAF3)
    val MentorMuted = Color(0xFFC9D1E2)
    val MentorInput = Color(0xFF0A0E1A)
    val MentorInputBorder = Color(0xFF3D4A6B)
}

internal object PortalText {
    val PageTitle = TextStyle(fontFamily = DisplayFamily, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.015).em)
    val SectionLabel = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.04.em)
    val RowTitle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
    val RowSubtitle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val Link = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
}

internal val PortalCardShape = RoundedCornerShape(12.dp)

/** Page heading row ("My Work", "Project"): 24 / 32 padding like the design's top bar. */
@Composable
internal fun PortalPageTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp).semantics { heading() },
        style = PortalText.PageTitle,
        color = BauColors.Ink,
    )
}

/** White card, 12dp radius, #D9DCD3 hairline. */
internal fun Modifier.portalCard(): Modifier =
    clip(PortalCardShape).background(BauColors.Surface).border(1.dp, PortalColors.CardBorder, PortalCardShape)

/** Pill chip from the portal design: 5 / 11 padding, 12sp bold, 16sp line height. */
@Composable
internal fun PortalChip(text: String, container: Color, content: Color, border: Color? = null) {
    val shape = RoundedCornerShape(999.dp)
    Text(
        text,
        modifier = Modifier
            .clip(shape)
            .background(container)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .padding(horizontal = 11.dp, vertical = 5.dp),
        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, lineHeight = 16.sp),
        color = content,
        maxLines = 1,
        softWrap = false,
    )
}

@Composable
internal fun PortalImInChip() = PortalChip("I’m In", BauColors.Yellow, BauColors.Ink)

@Composable
internal fun PortalTaskChip(status: TaskStatus) = when (status) {
    TaskStatus.InProgress -> PortalChip(status.label, BauColors.BlueDeep, BauColors.Surface)
    TaskStatus.Done -> PortalChip(status.label, BauColors.Ink, BauColors.Surface)
    TaskStatus.Backlog, TaskStatus.ToDo -> PortalChip(status.label, BauColors.Surface, BauColors.TextSecondary, PortalColors.ControlBorder)
}

@Composable
internal fun PortalChangeChip(status: ChangeStatus) = when (status) {
    ChangeStatus.Approved -> PortalChip(status.label, BauColors.Ink, BauColors.Surface)
    ChangeStatus.WithDevLead -> PortalChip(status.label, BauColors.Yellow, BauColors.Ink)
    ChangeStatus.ChangesRequested -> PortalChip(status.label, BauColors.WarningSoft, BauColors.WarningInk)
    ChangeStatus.Pushed -> PortalChip(status.label, BauColors.BlueSoft, BauColors.Ink)
}

/** Text link (blue, semibold) with a hand cursor. */
@Composable
internal fun PortalLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, style: TextStyle = PortalText.Link) {
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(vertical = 2.dp),
        style = style,
        color = BauColors.BlueDeep,
    )
}

/**
 * Row container with the portal hover state: light tint on hover / keyboard focus and a hand cursor.
 * Not interactive when [onClick] is null.
 */
@Composable
internal fun HoverRow(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onClickLabel: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val interactive = onClick != null
    Row(
        modifier
            .fillMaxWidth()
            .background(if (interactive && (hovered || focused)) PortalColors.RowHover else Color.Transparent)
            .then(
                if (onClick != null) Modifier
                    .hoverable(interaction)
                    .clickable(interaction, indication = null, role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
                    .pointerHoverIcon(PointerIcon.Hand)
                else Modifier,
            ),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
