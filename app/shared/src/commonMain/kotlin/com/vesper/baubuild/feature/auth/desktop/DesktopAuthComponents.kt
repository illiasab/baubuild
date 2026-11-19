package com.vesper.baubuild.feature.auth.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.DisplayFamily
import com.vesper.baubuild.designsystem.MonoFamily
import com.vesper.baubuild.feature.auth.authFieldColors

/** Colours from the public website (docs__Main.html) that the app tokens don't carry. */
internal object WebColors {
    val Border = Color(0xFFD9DCD3)        // card / field borders on paper
    val PanelBody = Color(0xFFC9D1E2)     // hero pitch text
    val PanelMuted = Color(0xFFA3ADC2)    // hero secondary text
    val PanelRule = Color(0xFF263049)     // hero divider
    val ButtonHover = Color(0xFFFFE03D)   // yellow CTA hover (website: opacity .84 over ink)
    val SecondaryHover = Color(0x0D0D1220)
}

/** Website buttons are 6px-radius rectangles. */
private const val ButtonRadius = 6
private val FocusRingWidth = 2.dp
private val FocusRingGap = 3.dp

/**
 * Keyboard focus ring like the website's `:focus-visible { outline: 3px solid #2B6BFF; outline-offset: 2px }`.
 * Always reserves the gap so focusing never shifts layout.
 */
internal fun Modifier.focusRing(focused: Boolean, radius: Dp): Modifier =
    this
        .border(
            FocusRingWidth,
            if (focused) BauColors.Blue else Color.Transparent,
            RoundedCornerShape(radius + FocusRingGap),
        )
        .padding(FocusRingGap)

/** Runs [action] on Enter / numpad Enter (desktop "Enter submits"); consumes the key so IME actions don't double-fire. */
internal fun Modifier.onEnter(action: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
        action()
        true
    } else false
}

/** Yellow website CTA: 6dp radius, 50dp tall, hover lightens, pointer cursor, focus ring, spinner while [loading]. */
@Composable
internal fun DesktopPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    loadingDescription: String = "Signing in",
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val active = enabled && !loading
    val container = when {
        loading -> BauColors.YellowLight
        !enabled -> BauColors.Line
        pressed -> BauColors.YellowDeep
        hovered -> WebColors.ButtonHover
        else -> BauColors.Yellow
    }
    val shape = RoundedCornerShape(ButtonRadius.dp)
    Box(
        modifier
            .fillMaxWidth()
            .focusRing(focused, ButtonRadius.dp)
            .heightIn(min = 50.dp)
            .clip(shape)
            .background(container)
            .then(if (active) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = active,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { if (loading) contentDescription = loadingDescription }
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), color = BauColors.Ink, strokeWidth = 2.dp)
        } else {
            Text(
                text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) BauColors.Ink else BauColors.TextTertiary,
            )
        }
    }
}

/** Website's outlined button (1.5dp ink border). */
@Composable
internal fun DesktopSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(ButtonRadius.dp)
    Box(
        modifier
            .fillMaxWidth()
            .focusRing(focused, ButtonRadius.dp)
            .heightIn(min = 50.dp)
            .clip(shape)
            .background(if (hovered && enabled) WebColors.SecondaryHover else Color.Transparent)
            .border(1.5.dp, if (enabled) BauColors.Ink else BauColors.Line, shape)
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) BauColors.Ink else BauColors.TextTertiary)
    }
}

/** "New here? **Sign Up**" link: blue action, underlined on hover, pointer cursor, focus ring. */
@Composable
internal fun DesktopTextLink(
    action: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    lead: String? = null,
    color: Color = BauColors.BlueDeep,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier.heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (lead != null) Text(lead, fontSize = 14.sp, color = BauColors.TextSecondary)
        Text(
            action,
            modifier = Modifier
                .focusRing(focused, 4.dp)
                .clip(RoundedCornerShape(4.dp))
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
                .padding(horizontal = 2.dp, vertical = 2.dp),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            textDecoration = if (hovered) TextDecoration.Underline else null,
        )
    }
}

/** "← Back" at the top of a sub-screen card. Esc does the same (system back). */
@Composable
internal fun DesktopBackLink(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier
            .focusRing(focused, 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (hovered) WebColors.SecondaryHover else Color.Transparent)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = "Back", onClick = onBack)
            .heightIn(min = 32.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("←", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = BauColors.Ink)
        Text("Back", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = BauColors.Ink)
        Text(
            "Esc",
            modifier = Modifier
                .border(1.dp, WebColors.Border, RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp),
            fontFamily = MonoFamily,
            fontSize = 11.sp,
            color = BauColors.TextTertiary,
        )
    }
}

/** White website card (12dp radius, hairline border) holding one screen's form; max 420dp wide. */
@Composable
internal fun DesktopFormCard(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .background(BauColors.Surface, shape)
            .border(1.dp, WebColors.Border, shape)
            .padding(horizontal = 32.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (onBack != null) DesktopBackLink(onBack, Modifier.padding(bottom = 2.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                modifier = Modifier.semantics { heading() },
                fontFamily = DisplayFamily,
                fontSize = 30.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.02).em,
                color = BauColors.Ink,
            )
            if (subtitle != null) Text(subtitle, fontSize = 15.sp, lineHeight = 22.sp, color = BauColors.TextSecondary)
        }
        content()
    }
}

/** Mono uppercase eyebrow, as on the website ("HOW A PROJECT MOVES"). */
@Composable
internal fun DesktopEyebrow(text: String, modifier: Modifier = Modifier, color: Color = BauColors.BlueDeep) {
    Text(
        text.uppercase(),
        modifier = modifier,
        fontFamily = MonoFamily,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.08.em,
        color = color,
    )
}

@Composable
internal fun DesktopFieldLabel(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = BauColors.TextSecondary)
}

/** Single-line field in the website style (8dp radius, blue focus border). Enter runs [onEnter]. */
@Composable
internal fun DesktopTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardOptions: KeyboardOptions,
    onEnter: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        enabled = enabled,
        isError = isError,
        placeholder = { Text(placeholder, color = BauColors.TextTertiary) },
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        shape = RoundedCornerShape(8.dp),
        colors = authFieldColors(),
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp).onEnter(onEnter),
    )
}

/** Website's checkbox line ("I agree to …"): whole row is the target, hover tint, focus ring. */
@Composable
internal fun DesktopCheckboxRow(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: AnnotatedString,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .focusRing(focused, 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (hovered && enabled) WebColors.SecondaryHover else Color.Transparent)
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                interactionSource = interaction,
                indication = null,
                onValueChange = onCheckedChange,
            )
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = CheckboxDefaults.colors(
                checkedColor = BauColors.Ink,
                uncheckedColor = BauColors.TextTertiary,
                checkmarkColor = BauColors.Yellow,
            ),
            modifier = Modifier.size(40.dp),
        )
        Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp, lineHeight = 20.sp, color = BauColors.TextSecondary)
    }
}

/** Website's `[data-pick]` segmented pills: transparent, ink when selected. Arrow keys / Tab + Space work. */
@Composable
internal fun DesktopSegmentedPills(
    options: List<Pair<String, String>>, // value to label
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    describe: (String) -> String = { it },
) {
    val outer: Shape = RoundedCornerShape(999.dp)
    Row(
        modifier
            .border(1.dp, WebColors.Border, outer)
            .padding(3.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (value, label) ->
            val interaction = remember(value) { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            val focused by interaction.collectIsFocusedAsState()
            val isSelected = value == selected
            Box(
                Modifier
                    .clip(outer)
                    .border(FocusRingWidth, if (focused) BauColors.Blue else Color.Transparent, outer)
                    .background(
                        when {
                            isSelected -> BauColors.Ink
                            hovered && enabled -> WebColors.SecondaryHover
                            else -> Color.Transparent
                        },
                    )
                    .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
                    .selectable(
                        selected = isSelected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        interactionSource = interaction,
                        indication = null,
                        onClick = { onSelect(value) },
                    )
                    .semantics { contentDescription = describe(value) }
                    .height(34.dp)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) Color.White else BauColors.Ink,
                )
            }
        }
    }
}

/** Numbered step in the website's "how it works" style: mono "01" + title + body. */
@Composable
internal fun DesktopStep(number: Int, title: String, body: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            number.toString().padStart(2, '0'),
            modifier = Modifier.padding(top = 2.dp),
            fontFamily = MonoFamily, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = BauColors.BlueDeep,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = BauColors.Ink)
            Text(body, fontSize = 14.sp, lineHeight = 19.sp, color = BauColors.TextSecondary)
        }
    }
}
