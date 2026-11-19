package com.vesper.baubuild.feature.staff.portal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vesper.baubuild.core.model.SeniorEngineer
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.DisplayFamily
import com.vesper.baubuild.feature.review.portal.PortalButton
import com.vesper.baubuild.feature.review.portal.PortalTokens
import com.vesper.baubuild.feature.review.portal.pointerHand
import com.vesper.baubuild.feature.staff.profile.SeniorEngineerBioContent
import com.vesper.baubuild.feature.staff.profile.SeniorEngineerSheet

/**
 * "Senior engineers notified · Ilias, Arian" where each name is a link (hand cursor, underline on hover, dims on
 * press — no background, per the BauTextLink rule) that opens the engineer's bio dialog.
 */
@Composable
internal fun SeniorEngineersPortalRow(engineers: List<SeniorEngineer>, onOpen: (SeniorEngineer) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Senior engineers notified", fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = BauColors.Ink)
        Spacer(Modifier.weight(1f))
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.End) {
            engineers.forEachIndexed { index, engineer ->
                EngineerNameLink(engineer.name + if (index < engineers.lastIndex) "," else "", onClick = { onOpen(engineer) })
                if (index < engineers.lastIndex) Spacer(Modifier.width(4.dp))
            }
        }
    }
}

@Composable
private fun EngineerNameLink(text: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    Text(
        text,
        modifier = Modifier
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .pointerHand()
            .alpha(if (pressed) 0.45f else 1f),
        fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = BauColors.BlueDeep,
        textDecoration = if (hovered) TextDecoration.Underline else null,
    )
}

/** Centered portal dialog with the engineer's bio. Esc, clicking outside or "Close" dismisses it. */
@Composable
internal fun SeniorEngineerPortalDialog(sheet: SeniorEngineerSheet, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true, usePlatformDefaultWidth = false),
    ) {
        val shape = RoundedCornerShape(PortalTokens.CardRadius)
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Column(
            Modifier
                .padding(24.dp)
                .widthIn(max = 520.dp).fillMaxWidth()
                .heightIn(max = 640.dp)
                .clip(shape)
                .background(BauColors.Surface)
                .border(1.dp, PortalTokens.CardBorder, shape)
                .onPreviewKeyEvent { e ->
                    if (e.key == Key.Escape && e.type == KeyEventType.KeyDown) { onClose(); true } else false
                }
                .focusRequester(focus)
                .focusable(),
        ) {
            Text(
                "Senior engineer",
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp).semantics { heading() },
                fontFamily = DisplayFamily, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
                color = BauColors.TextSecondary,
            )
            HorizontalDivider(color = PortalTokens.Divider, thickness = 1.dp)
            SeniorEngineerBioContent(
                sheet,
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(24.dp),
                linkModifier = Modifier.pointerHand(),
            )
            HorizontalDivider(color = PortalTokens.Divider, thickness = 1.dp)
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), contentAlignment = Alignment.CenterEnd) {
                PortalButton("Close", onClick = onClose, modifier = Modifier.pointerHand())
            }
        }
    }
}
