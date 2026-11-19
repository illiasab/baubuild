package com.vesper.baubuild.feature.staff.inquiries.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.vesper.baubuild.core.model.InquiryStatus
import com.vesper.baubuild.core.model.StaffInquiry
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.BauSpacing
import com.vesper.baubuild.feature.staff.inquiries.listDetail
import com.vesper.baubuild.feature.staff.inquiries.listSubtitle

// Staff-feature components from the iOS Staff design (Overview / Inquiries / Detail). Feature-local on purpose;
// candidates for designsystem/ once another feature needs them.

/** Extra palette entries used only by the staff screens. */
object StaffColors {
    val Muted = Color(0xFFEEF0EA)          // "No blockers" chip, priority track
    val OnDarkSecondary = Color(0xFFC9D1E2) // labels on the dark "This Semester" card
    val WastedOnDark = Color(0xFFFFB38A)
    val ChoiceBorder = Color(0xFFC5CAD3)
    val BlockerInk = Color(0xFF2A1A0A)
    val BriefText = Color(0xFF333B4D)
    val AiNoteText = Color(0xFF1E2848)
}

object StaffIcons {
    val Back = strokeIcon("Back", 20f, "M12 4l-6 6 6 6", 2.2f)
    val Chevron = strokeIcon("Chevron", 8f, "M1 1l6 6-6 6", 2f, height = 14f)
    val Check = strokeIcon("Check", 18f, "M3.5 9.5l3.5 3.5 7.5-8", 2.2f)
    val Tick = strokeIcon("Tick", 16f, "M3 8.5l3 3 7-7.5", 2.4f)
    val Circle = strokeIcon("Circle", 16f, "M1.8 8a6.2 6.2 0 1 0 12.4 0a6.2 6.2 0 1 0 -12.4 0", 1.6f)
    val Warning = strokeIcon("Warning", 20f, "M10 3L18 17H2zM10 8.5v3.5M10 14.5v.5", 2f)
    val Sparkle = strokeIcon("Sparkle", 20f, "M10 2.5l1.8 5.7 5.7 1.8-5.7 1.8L10 17.5l-1.8-5.7L2.5 10l5.7-1.8z", 1.8f)
    val Bell = strokeIcon("Bell", 20f, "M5 8a5 5 0 0 1 10 0c0 4 1.5 5 1.5 5h-13S5 12 5 8zM8.5 16a1.6 1.6 0 0 0 3 0")

    private fun strokeIcon(name: String, width: Float, path: String, stroke: Float = 1.8f, height: Float = width) =
        ImageVector.Builder(name, width.dp, height.dp, width, height).apply {
            addPath(
                pathData = addPathNodes(path), stroke = SolidColor(Color.Black), strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
}

private val CardShape = RoundedCornerShape(24.dp)

/** White grouped card (24dp radius, hairline border, soft shadow). */
fun Modifier.staffCard(shape: RoundedCornerShape = CardShape): Modifier = this
    .shadow(6.dp, shape, ambientColor = BauColors.Ink.copy(alpha = 0.06f), spotColor = BauColors.Ink.copy(alpha = 0.06f))
    .clip(shape)
    .background(BauColors.Surface)
    .border(1.dp, BauColors.Line, shape)

/** Frosted glass surface used by the segmented control and the circular buttons. */
fun Modifier.glass(shape: RoundedCornerShape): Modifier = this
    .shadow(8.dp, shape, ambientColor = BauColors.Ink.copy(alpha = 0.14f), spotColor = BauColors.Ink.copy(alpha = 0.14f))
    .clip(shape)
    .background(Color.White.copy(alpha = 0.85f))
    .border(1.dp, Color.White, shape)

/** 44dp glass circle with the back chevron. */
@Composable
fun StaffBackButton(contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(44.dp).glass(RoundedCornerShape(22.dp))
            .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) { Icon(StaffIcons.Back, null, tint = BauColors.Ink, modifier = Modifier.size(20.dp)) }
}

/** 34sp large title with optional eyebrow above ("Hello, Dr. Hale") or subtitle below. */
@Composable
fun StaffLargeTitle(title: String, modifier: Modifier = Modifier, eyebrow: String? = null, subtitle: String? = null) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (eyebrow != null) Text(eyebrow, style = TextStyle(fontSize = 15.sp, lineHeight = 20.sp), color = BauColors.TextSecondary)
        Text(
            title,
            modifier = Modifier.semantics { heading() },
            style = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 41.sp, letterSpacing = (-0.02).em),
            color = BauColors.Ink,
        )
        if (subtitle != null) Text(subtitle, style = TextStyle(fontSize = 15.sp, lineHeight = 20.sp), color = BauColors.TextSecondary)
    }
}

/** 20sp section title with an optional trailing text link (rendered by the caller). */
@Composable
fun StaffSectionTitle(title: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            modifier = Modifier.weight(1f).semantics { heading() },
            style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 25.sp, letterSpacing = (-0.02).em),
            color = BauColors.Ink,
        )
        trailing()
    }
}

/**
 * Glass segmented control (All / Open / Closed, Status / Details). 44dp tall, selected segment is Ink.
 * No ripple: the selection itself is the feedback.
 */
@Composable
fun <T> StaffSegmentedControl(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().height(44.dp).glass(RoundedCornerShape(22.dp)).padding(3.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier.weight(1f).fillMaxWidth().height(38.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(if (isSelected) BauColors.Ink else Color.Transparent)
                    .selectable(isSelected, interactionSource = interaction, indication = null, role = Role.Tab) { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = TextStyle(fontSize = 14.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium),
                    color = if (isSelected) Color.White else BauColors.Ink,
                )
            }
        }
    }
}

/** Small compact segmented control on Muted track (Priority Normal / Urgent). */
@Composable
fun <T> StaffMiniSegmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(modifier.clip(RoundedCornerShape(22.dp)).background(StaffColors.Muted).padding(3.dp)) {
        options.forEach { option ->
            val isSelected = option == selected
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier.heightIn(min = 38.dp).clip(RoundedCornerShape(19.dp))
                    .background(if (isSelected) BauColors.Ink else Color.Transparent)
                    .selectable(isSelected, enabled = enabled, interactionSource = interaction, indication = null, role = Role.RadioButton) { onSelect(option) }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = TextStyle(fontSize = 14.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium),
                    color = if (isSelected) Color.White else BauColors.Ink,
                )
            }
        }
    }
}

/** Status chip colors per the design. */
fun InquiryStatus.chipColors(): Pair<Color, Color> = when (this) {
    InquiryStatus.Draft -> StaffColors.Muted to BauColors.TextSecondary
    InquiryStatus.AiRefining, InquiryStatus.ReadyToPost -> BauColors.BlueSoft to BauColors.Ink
    InquiryStatus.Posted -> BauColors.Yellow to BauColors.Ink
    InquiryStatus.InProgress -> BauColors.BlueDeep to Color.White
    InquiryStatus.Done -> BauColors.Ink to Color.White
    InquiryStatus.Cancelled -> BauColors.WarningSoft to BauColors.WarningInk
}

/** Pill chip with the design's exact metrics (12sp bold, 5/11 padding). */
@Composable
fun StaffChip(text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.clip(RoundedCornerShape(999.dp)).background(container).padding(horizontal = 11.dp, vertical = 5.dp),
        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, lineHeight = 16.sp),
        color = content,
        maxLines = 1,
    )
}

@Composable
fun InquiryStatusChip(status: InquiryStatus, modifier: Modifier = Modifier) {
    val (bg, fg) = status.chipColors()
    StaffChip(status.label, bg, fg, modifier)
}

/** Light tag chip (project types, difficulty) — #F3F4EF, 12sp medium. */
@Composable
fun StaffTag(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.clip(RoundedCornerShape(999.dp)).background(BauColors.Paper).padding(horizontal = 10.dp, vertical = 5.dp),
        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
        color = BauColors.TextSecondary,
    )
}

/** Small grey caption above a grouped card ("AI Refining", "Earlier"). */
@Composable
fun StaffGroupedSection(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BauSpacing.sm)) {
        if (title != null) {
            Text(
                title,
                modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp),
                color = BauColors.TextSecondary,
            )
        }
        Column(Modifier.fillMaxWidth().staffCard(), content = content)
    }
}

/** One row of a grouped card: title, subtitle, optional detail line, trailing chip, chevron when tappable. */
@Composable
fun StaffListRow(
    title: String,
    subtitle: String?,
    showDivider: Boolean,
    modifier: Modifier = Modifier,
    detail: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .heightIn(min = 72.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    title,
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp, letterSpacing = (-0.02).em),
                    color = BauColors.Ink,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle, style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp), color = BauColors.TextSecondary,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!detail.isNullOrBlank()) {
                    Text(
                        detail, style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp), color = BauColors.TextTertiary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing()
            if (onClick != null) Icon(StaffIcons.Chevron, null, tint = BauColors.TextTertiary, modifier = Modifier.size(width = 8.dp, height = 14.dp))
        }
        if (showDivider) HorizontalDivider(thickness = 1.dp, color = BauColors.Line)
    }
}

/** Inquiry row as used on the Inquiries tab (subtitle + latest line). [subtitle] overrides the default text. */
@Composable
fun InquiryRow(
    inquiry: StaffInquiry,
    showDivider: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String = inquiry.listSubtitle,
    detail: String? = inquiry.listDetail,
) {
    StaffListRow(
        title = inquiry.title, subtitle = subtitle, detail = detail, showDivider = showDivider,
        onClick = onClick, modifier = modifier,
    ) { InquiryStatusChip(inquiry.status) }
}

/** Check mark + text line ("Done Today", "Done Looks Like"). */
@Composable
fun CheckLine(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Icon(StaffIcons.Check, null, tint = BauColors.BlueDeep, modifier = Modifier.padding(top = 2.dp).size(16.dp))
        Text(text, style = TextStyle(fontSize = 15.sp, lineHeight = 20.sp), color = BauColors.Ink)
    }
}

/** Centered quiet message for empty lists. */
@Composable
fun StaffEmptyMessage(title: String, body: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = BauSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(BauSpacing.sm),
    ) {
        Text(title, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp), color = BauColors.Ink)
        Text(
            body, style = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            color = BauColors.TextSecondary,
        )
        action?.invoke()
    }
}

/** Inline write error (warning colors), never a full-screen error. */
@Composable
fun StaffInlineError(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(BauColors.WarningSoft)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
        color = BauColors.WarningInk,
    )
}

/** Rounded action button used on tinted cards ("Contact IT" dark / "Mark Resolved" light). 44dp. */
@Composable
fun StaffCardButton(
    text: String,
    onClick: () -> Unit,
    dark: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    height: Dp = 44.dp,
) {
    val shape = RoundedCornerShape(height / 2)
    Box(
        modifier.heightIn(min = height).clip(shape)
            .background(if (dark) BauColors.Ink else Color.White)
            .clickable(enabled = enabled && !loading, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            androidx.compose.material3.CircularProgressIndicator(
                Modifier.size(18.dp), color = if (dark) Color.White else BauColors.Ink, strokeWidth = 2.dp,
            )
        } else {
            Text(
                text, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                color = (if (dark) Color.White else BauColors.Ink).copy(alpha = if (enabled) 1f else 0.5f),
            )
        }
    }
}

/** Multi-select choice chip with tick / empty circle (Project Type "Tick all that apply"). */
@Composable
fun StaffChoiceChip(text: String, selected: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier.heightIn(min = 44.dp).clip(shape)
            .background(if (selected) BauColors.Ink else Color.White)
            .then(if (selected) Modifier else Modifier.border(1.dp, StaffColors.ChoiceBorder, shape))
            .toggleable(selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(start = 12.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (selected) Icon(StaffIcons.Tick, null, tint = Color.White, modifier = Modifier.size(16.dp))
        else Icon(StaffIcons.Circle, null, tint = BauColors.TextTertiary, modifier = Modifier.size(16.dp))
        Text(
            text, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            color = if (selected) Color.White else BauColors.Ink,
        )
    }
}
