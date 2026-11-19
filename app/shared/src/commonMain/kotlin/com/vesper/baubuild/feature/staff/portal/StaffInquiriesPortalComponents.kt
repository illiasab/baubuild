package com.vesper.baubuild.feature.staff.portal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.vesper.baubuild.core.model.StaffInquiry
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.DisplayFamily
import com.vesper.baubuild.feature.staff.inquiries.components.InquiryStatusChip
import com.vesper.baubuild.feature.staff.inquiries.components.StaffChip
import com.vesper.baubuild.feature.staff.inquiries.components.StaffIcons

// Building blocks shared by the desktop staff Overview and Inquiries pages (Web portal design inline styles).

internal object StaffPortalColors {
    val CardBorder = Color(0xFFD9DCD3)
    val RowLine = Color(0xFFE3E6DE)
    val Control = Color(0xFFC5CAD3)
    val RowHover = Color(0xFFF7F8F3)
    val Placeholder = Color(0xFF6B7385)
}

internal val StaffPortalGutter = 32.dp
private val CardShape = RoundedCornerShape(12.dp)
private val ButtonShape = RoundedCornerShape(6.dp)
private val FieldShape = RoundedCornerShape(8.dp)

/** White card, 12dp radius, #D9DCD3 hairline. */
internal fun Modifier.staffPortalCard(): Modifier =
    clip(CardShape).background(BauColors.Surface).border(1.dp, StaffPortalColors.CardBorder, CardShape)

/**
 * Page header: 30sp title at 24/32 padding, trailing actions (always a Refresh button — desktop has no pull to
 * refresh) and a 2dp progress line underneath while a refresh is running.
 */
@Composable
internal fun StaffPortalHeader(
    title: String,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = StaffPortalGutter, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (eyebrow != null) {
                    Text(eyebrow, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp), color = BauColors.BlueDeep)
                }
                Text(
                    title, modifier = Modifier.semantics { heading() },
                    style = TextStyle(fontFamily = DisplayFamily, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.015).em),
                    color = BauColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            actions()
            StaffPortalButton(if (refreshing) "Refreshing…" else "Refresh", onClick = onRefresh, enabled = !refreshing)
        }
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (refreshing) LinearProgressIndicator(Modifier.fillMaxSize(), color = BauColors.Blue, trackColor = BauColors.Paper)
        }
    }
}

internal enum class StaffPortalButtonStyle { Primary, Outline, Dark, OnWarning, Danger }

/**
 * Portal button: 44dp min height, 6dp radius, 20dp side padding. Hover tints it and shows the hand cursor;
 * keyboard focus (Tab) gets a blue ring. [loading] keeps the size and shows a spinner instead of the label.
 */
@Composable
internal fun StaffPortalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: StaffPortalButtonStyle = StaffPortalButtonStyle.Outline,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val active = enabled && !loading
    val hover = hovered && active
    val (bg, fg, border) = when (style) {
        StaffPortalButtonStyle.Primary -> Triple(if (hover) BauColors.YellowLight else BauColors.Yellow, BauColors.Ink, null)
        StaffPortalButtonStyle.Outline -> Triple(if (hover) BauColors.Ink.copy(alpha = 0.06f) else Color.Transparent, BauColors.Ink, BauColors.Ink)
        StaffPortalButtonStyle.Dark -> Triple(if (hover) Color(0xFF263049) else BauColors.Ink, Color.White, null)
        StaffPortalButtonStyle.OnWarning -> Triple(if (hover) Color(0xFFFFF6EC) else BauColors.Surface, BauColors.Ink, null)
        StaffPortalButtonStyle.Danger -> Triple(if (hover) BauColors.Danger.copy(alpha = 0.08f) else Color.Transparent, BauColors.Danger, BauColors.Danger)
    }
    Box(
        modifier
            .heightIn(min = 44.dp)
            .then(if (focused) Modifier.border(3.dp, BauColors.Blue, RoundedCornerShape(8.dp)).padding(1.dp) else Modifier)
            .clip(ButtonShape)
            .background(if (enabled) bg else BauColors.Line)
            .then(if (border != null) Modifier.border(1.5.dp, if (enabled) border else BauColors.TextTertiary, ButtonShape) else Modifier)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, enabled = active, role = Role.Button, onClick = onClick)
            .then(if (active) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 1,
            fontWeight = if (style == StaffPortalButtonStyle.Primary) FontWeight.Bold else FontWeight.SemiBold,
            color = when {
                !enabled -> BauColors.TextTertiary
                loading -> Color.Transparent
                else -> fg
            },
        )
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = fg, strokeWidth = 2.dp)
    }
}

/** Blue semibold text link with hand cursor (BauTextLink rule: only the words, no background; dims on hover). */
@Composable
internal fun StaffPortalLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, fontSize: Int = 14) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    Box(
        modifier.heightIn(min = 44.dp)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            modifier = if (focused) Modifier.border(2.dp, BauColors.Blue, RoundedCornerShape(4.dp)).padding(horizontal = 2.dp) else Modifier,
            style = TextStyle(fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold, lineHeight = (fontSize + 6).sp),
            color = if (hovered) BauColors.BlueDeep.copy(alpha = 0.84f) else BauColors.BlueDeep,
        )
    }
}

/** Segmented control from the portal design: white, #C5CAD3 border, 9dp radius, selected = ink fill. */
@Composable
internal fun <T> StaffPortalSegmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(9.dp)
    Row(
        modifier.clip(shape).background(BauColors.Surface).border(1.dp, StaffPortalColors.Control, shape).padding(3.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val source = remember { MutableInteractionSource() }
            val hovered by source.collectIsHoveredAsState()
            val focused by source.collectIsFocusedAsState()
            Box(
                Modifier
                    .then(if (fill) Modifier.weight(1f) else Modifier)
                    .heightIn(min = 38.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        when {
                            isSelected -> BauColors.Ink
                            hovered && enabled -> BauColors.Ink.copy(alpha = 0.06f)
                            else -> Color.Transparent
                        },
                    )
                    .then(if (focused) Modifier.border(2.dp, BauColors.Blue, RoundedCornerShape(6.dp)) else Modifier)
                    .hoverable(source)
                    .clickable(source, indication = null, enabled = enabled, role = Role.Tab) { onSelect(option) }
                    .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
                    .semantics { this.selected = isSelected }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = TextStyle(fontSize = 14.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium),
                    color = when {
                        isSelected -> Color.White
                        enabled -> BauColors.Ink
                        else -> BauColors.TextTertiary
                    },
                )
            }
        }
    }
}

/** Stat card: 13sp label over a 26sp display value. */
@Composable
internal fun StaffPortalStatCard(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = BauColors.Ink) {
    Column(
        modifier.staffPortalCard().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp), color = BauColors.TextSecondary)
        Text(value, style = TextStyle(fontFamily = DisplayFamily, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp), color = valueColor)
    }
}

/** CSS grid `repeat(auto-fit, minmax(min, 1fr))` with a fixed gap, laid out in rows. */
@Composable
internal fun <T> StaffPortalAutoGrid(
    items: List<T>,
    minCellWidth: Dp,
    gap: Dp,
    modifier: Modifier = Modifier,
    cell: @Composable (T, Modifier) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = ((maxWidth + gap) / (minCellWidth + gap)).toInt().coerceIn(1, items.size.coerceAtLeast(1))
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            items.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { cell(it, Modifier.weight(1f)) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Inquiry table
// ---------------------------------------------------------------------------------------------------------------

/** One table column: header caps label, flex weight and the cell content. */
internal class StaffInquiryColumn(
    val header: String,
    val weight: Float,
    val cell: @Composable (StaffInquiry) -> Unit,
)

private val CellPaddingH = 20.dp
private val CellPaddingV = 16.dp
private val OpenColumnWidth = 64.dp

/**
 * Inquiry table (Overview "Recent inquiries", Inquiries list). Each row is one hover/focus target (hand cursor,
 * light tint, Enter opens) ending in the design's chevron "Open" cell. Scrolls sideways below [minWidth].
 */
@Composable
internal fun StaffInquiryTable(
    inquiries: List<StaffInquiry>,
    columns: List<StaffInquiryColumn>,
    minWidth: Dp,
    onOpen: (StaffInquiry) -> Unit,
    modifier: Modifier = Modifier,
    empty: @Composable () -> Unit = {},
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val needsScroll = maxWidth < minWidth
        Column(
            Modifier
                .then(if (needsScroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
                .width(if (needsScroll) minWidth else maxWidth),
        ) {
            Row(Modifier.fillMaxWidth()) {
                columns.forEach { column ->
                    Text(
                        column.header,
                        modifier = Modifier.weight(column.weight).padding(horizontal = CellPaddingH, vertical = 12.dp),
                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.04.em, lineHeight = 16.sp),
                        color = BauColors.TextSecondary,
                    )
                }
                Spacer(Modifier.width(OpenColumnWidth))
            }
            if (inquiries.isEmpty()) {
                HorizontalDivider(thickness = 1.dp, color = StaffPortalColors.RowLine)
                empty()
            }
            inquiries.forEach { inquiry ->
                HorizontalDivider(thickness = 1.dp, color = StaffPortalColors.RowLine)
                StaffInquiryTableRow(inquiry, columns, onOpen = { onOpen(inquiry) })
            }
        }
    }
}

@Composable
private fun StaffInquiryTableRow(inquiry: StaffInquiry, columns: List<StaffInquiryColumn>, onOpen: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (hovered || focused) StaffPortalColors.RowHover else Color.Transparent)
            .then(if (focused) Modifier.border(2.dp, BauColors.Blue) else Modifier)
            .hoverable(source)
            .clickable(source, indication = null, role = Role.Button, onClickLabel = "Open ${inquiry.title}", onClick = onOpen)
            .pointerHoverIcon(PointerIcon.Hand),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        columns.forEach { column ->
            Box(Modifier.weight(column.weight).padding(horizontal = CellPaddingH, vertical = CellPaddingV)) { column.cell(inquiry) }
        }
        Box(Modifier.width(OpenColumnWidth).padding(end = 12.dp), contentAlignment = Alignment.CenterEnd) {
            Box(
                Modifier.size(44.dp).clip(FieldShape)
                    .background(if (hovered) BauColors.Ink.copy(alpha = 0.06f) else Color.Transparent)
                    .semantics { contentDescription = "Open ${inquiry.title}" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(StaffIcons.Chevron, null, tint = BauColors.TextSecondary, modifier = Modifier.size(width = 8.dp, height = 14.dp))
            }
        }
    }
}

/** Table cells shared by both pages. */
internal object StaffInquiryCells {
    val Title = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
    val Body = TextStyle(fontSize = 15.sp, lineHeight = 20.sp)

    @Composable
    fun InquiryTitle(inquiry: StaffInquiry) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(inquiry.title, modifier = Modifier.weight(1f, fill = false), style = Title, color = BauColors.Ink)
            if (inquiry.isUrgent) UrgentChip()
        }
    }

    @Composable
    fun Secondary(text: String) {
        Text(text, style = Body, color = BauColors.TextSecondary)
    }

    @Composable
    fun Status(inquiry: StaffInquiry) = InquiryStatusChip(inquiry.status)

    @Composable
    fun Latest(inquiry: StaffInquiry) {
        if (inquiry.hasOpenBlocker) {
            Text(inquiry.portalLatest, style = Body.copy(fontWeight = FontWeight.SemiBold), color = BauColors.WarningInk)
        } else {
            Text(inquiry.portalLatest, style = Body, color = BauColors.TextSecondary)
        }
    }
}

@Composable
internal fun UrgentChip() = StaffChip("Urgent", BauColors.WarningSoft, BauColors.WarningInk)

/** Message shown inside a card when a table has no rows. */
@Composable
internal fun StaffPortalEmpty(title: String, body: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = TextStyle(fontFamily = DisplayFamily, fontSize = 18.sp, fontWeight = FontWeight.Bold), color = BauColors.Ink)
        Text(body, style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp), color = BauColors.TextSecondary)
        if (action != null) Box(Modifier.padding(top = 8.dp)) { action() }
    }
}

/** "Search inquiries" field from the portal header (260dp, 44dp tall, #C5CAD3 border). Esc clears it. */
@Composable
internal fun StaffPortalSearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(fontSize = 15.sp, color = BauColors.Ink),
        cursorBrush = SolidColor(BauColors.Blue),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = modifier.widthIn(max = 260.dp).fillMaxWidth().height(44.dp)
            .onPreviewKeyEvent { e ->
                if (e.key == Key.Escape && e.type == KeyEventType.KeyDown && value.isNotEmpty()) { onValueChange(""); true } else false
            }
            .semantics { contentDescription = "Search inquiries" },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().height(44.dp).clip(FieldShape).background(BauColors.Surface)
                    .border(1.dp, StaffPortalColors.Control, FieldShape).padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text("Search inquiries", fontSize = 15.sp, color = StaffPortalColors.Placeholder, maxLines = 1)
                inner()
            }
        },
    )
}

/**
 * Labeled form field (portal inputs: 46dp min, #C5CAD3 border, 8dp radius, 16sp). Multi-line fields keep Tab /
 * Shift+Tab for focus traversal instead of inserting a tab; single-line fields submit with Enter via [onSubmit].
 */
@Composable
internal fun StaffPortalField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minHeight: Dp = 46.dp,
    placeholder: String? = null,
    prefix: String? = null,
    numeric: Boolean = false,
    onSubmit: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val focusManager = LocalFocusManager.current
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp), color = BauColors.TextSecondary)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            interactionSource = source,
            textStyle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, color = if (enabled) BauColors.Ink else BauColors.TextSecondary),
            cursorBrush = SolidColor(BauColors.Blue),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
                imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmit?.invoke() }),
            modifier = Modifier.fillMaxWidth()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        e.key == Key.Tab -> {
                            focusManager.moveFocus(if (e.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
                            true
                        }
                        singleLine && onSubmit != null && (e.key == Key.Enter || e.key == Key.NumPadEnter) -> { onSubmit(); true }
                        else -> false
                    }
                }
                .semantics { contentDescription = label },
            decorationBox = { inner ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = minHeight).clip(FieldShape)
                        .background(if (enabled) BauColors.Surface else BauColors.Paper)
                        .border(if (focused) 2.dp else 1.dp, if (focused) BauColors.Blue else StaffPortalColors.Control, FieldShape)
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (prefix != null) Text(prefix, style = TextStyle(fontSize = 16.sp, lineHeight = 22.sp), color = BauColors.TextSecondary)
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(placeholder, style = TextStyle(fontSize = 16.sp, lineHeight = 22.sp), color = StaffPortalColors.Placeholder)
                        }
                        inner()
                    }
                    trailing?.invoke()
                }
            },
        )
    }
}
