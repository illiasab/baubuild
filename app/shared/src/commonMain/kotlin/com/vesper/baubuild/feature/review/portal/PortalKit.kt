package com.vesper.baubuild.feature.review.portal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.DisplayFamily
import com.vesper.baubuild.designsystem.MonoFamily

/**
 * Small toolkit shared by the desktop Profile and Review pages (Web portal design):
 * tokens from the portal mockups, page header, section cards and hover-aware buttons.
 */
internal object PortalTokens {
    /** Card border in the portal design (#D9DCD3). */
    val CardBorder = Color(0xFFD9DCD3)
    /** Row dividers inside cards (#E3E6DE). */
    val Divider = Color(0xFFE3E6DE)
    /** Muted text on dark cards (#C9D1E2). */
    val OnDarkMuted = Color(0xFFC9D1E2)
    /** Hairline on dark cards (#2C3757). */
    val OnDarkLine = Color(0xFF2C3757)
    /** Hover wash for list rows. */
    val RowHover = Color(0xFFF7F8F4)
    val CardRadius = 12.dp
    val ButtonRadius = 6.dp
    val PagePadding = 32.dp
    val Gap = 20.dp
}

/** Page title row ("Profile", "Review") — 30sp display, 24/32 padding as in the portal header. */
@Composable
internal fun PortalPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = PortalTokens.PagePadding, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title, modifier = Modifier.semantics { heading() },
                fontFamily = DisplayFamily, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold,
                letterSpacing = (-0.45).sp, color = BauColors.Ink,
            )
            if (subtitle != null) Text(subtitle, fontSize = 15.sp, lineHeight = 22.sp, color = BauColors.TextSecondary)
        }
        actions()
    }
}

/** White card, 1dp #D9DCD3 border, 12dp radius. Content padding is up to the caller. */
@Composable
internal fun PortalCard(
    modifier: Modifier = Modifier,
    padding: Dp = 24.dp,
    gap: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(PortalTokens.CardRadius)
    Column(
        modifier.clip(shape).background(BauColors.Surface).border(1.dp, PortalTokens.CardBorder, shape).padding(padding),
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

/** "PROFESSIONAL"-style caps strip at the top of a list card, with a divider under it. */
@Composable
internal fun PortalCardStrip(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).semantics { heading() },
        fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.48.sp,
        color = BauColors.TextSecondary,
    )
    HorizontalDivider(color = PortalTokens.Divider, thickness = 1.dp)
}

/** Card section title (h2): 22–24sp display bold. */
@Composable
internal fun PortalSectionTitle(text: String, fontSize: Int = 22) {
    Text(
        text, modifier = Modifier.semantics { heading() },
        fontFamily = DisplayFamily, fontSize = fontSize.sp, lineHeight = (fontSize + 6).sp,
        fontWeight = FontWeight.Bold, color = BauColors.Ink,
    )
}

@Composable
internal fun MonoLabel(text: String, color: Color = BauColors.BlueDeep, modifier: Modifier = Modifier) {
    Text(
        text, modifier = modifier, fontFamily = MonoFamily, fontSize = 12.sp, lineHeight = 16.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 0.72.sp, color = color,
    )
}

internal enum class PortalButtonStyle { Primary, Outline }

/**
 * Portal button: 44dp min height, 6dp radius, 20dp side padding. Primary = yellow fill; Outline = 1.5dp ink border.
 * Hover darkens the fill (or washes the outline) and shows the hand cursor.
 */
@Composable
internal fun PortalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PortalButtonStyle = PortalButtonStyle.Outline,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val shape = RoundedCornerShape(PortalTokens.ButtonRadius)
    val (bg, border) = when (style) {
        PortalButtonStyle.Primary -> (if (hovered && enabled) BauColors.YellowLight else BauColors.Yellow) to null
        PortalButtonStyle.Outline -> (if (hovered && enabled) BauColors.Ink.copy(alpha = 0.06f) else Color.Transparent) to BauColors.Ink
    }
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(if (enabled) bg else BauColors.Line)
            .then(if (border != null) Modifier.border(1.5.dp, if (enabled) border else BauColors.TextTertiary, shape) else Modifier)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, fontSize = 15.sp, lineHeight = 20.sp,
            fontWeight = if (style == PortalButtonStyle.Primary) FontWeight.Bold else FontWeight.SemiBold,
            color = if (enabled) BauColors.Ink else BauColors.TextTertiary,
        )
    }
}

/** Hand cursor on hover (desktop) for anything clickable. */
internal fun Modifier.pointerHand(): Modifier = pointerHoverIcon(PointerIcon.Hand)
