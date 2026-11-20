package com.vesper.baubuild.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vesper.baubuild.core.model.PasswordCheck
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.BauTextLink

// Fields shared by the mobile auth screens (and, for the code input and hint lines, the desktop ones).

internal val EmailKeyboard = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Email,
    imeAction = ImeAction.Next,
)

internal val PasswordKeyboard = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Password,
    imeAction = ImeAction.Next,
)

internal val NameKeyboard = KeyboardOptions(
    capitalization = KeyboardCapitalization.Words,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Text,
    imeAction = ImeAction.Next,
)

@Composable
internal fun AuthFieldLabel(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = BauColors.TextSecondary)
}

/**
 * Labelled single-line field of the mobile auth screens. [password] turns on secure entry plus a "Show"/"Hide"
 * text link (BauTextLink rule: no ripple). [onImeAction] runs for the keyboard's Next / Done key.
 */
@Composable
internal fun AuthTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardOptions: KeyboardOptions,
    onImeAction: () -> Unit,
    contentType: ContentType,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    focusRequester: FocusRequester? = null,
    password: Boolean = false,
    passwordVisible: Boolean = false,
    onTogglePasswordVisible: () -> Unit = {},
    below: @Composable (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AuthFieldLabel(label)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            enabled = enabled,
            isError = isError,
            placeholder = { Text(placeholder, color = BauColors.TextTertiary) },
            keyboardOptions = keyboardOptions,
            keyboardActions = KeyboardActions(onNext = { onImeAction() }, onDone = { onImeAction() }, onGo = { onImeAction() }),
            visualTransformation = if (password && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
            trailingIcon = if (password) {
                { PasswordToggle(passwordVisible, onTogglePasswordVisible) }
            } else null,
            shape = RoundedCornerShape(16.dp),
            colors = authFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .semantics { this.contentType = contentType },
        )
        below?.invoke()
    }
}

/** "Show" / "Hide" inside a password field. */
@Composable
internal fun PasswordToggle(visible: Boolean, onToggle: () -> Unit) {
    BauTextLink(
        if (visible) "Hide" else "Show",
        onClick = onToggle,
        modifier = Modifier
            .padding(end = 8.dp)
            .semantics { contentDescription = if (visible) "Hide password" else "Show password" },
        fontSize = 14.sp,
    )
}

/** Live password rule hint: "8+ characters · a letter · a number", each ticked once met. */
@Composable
internal fun PasswordRulesLine(check: PasswordCheck, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RuleChip("8+ characters", check.longEnough)
        RuleChip("a letter", check.hasLetter)
        RuleChip("a number", check.hasDigit)
    }
}

@Composable
private fun RuleChip(text: String, met: Boolean) {
    Text(
        (if (met) "✓ " else "• ") + text,
        modifier = Modifier.semantics { contentDescription = "$text, ${if (met) "done" else "missing"}" },
        fontSize = 13.sp, lineHeight = 18.sp,
        fontWeight = if (met) FontWeight.SemiBold else FontWeight.Normal,
        color = if (met) BauColors.BlueDeep else BauColors.TextTertiary,
    )
}

/** Small warning line under a field (e.g. "Passwords don't match"). */
@Composable
internal fun AuthFieldHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = BauColors.WarningInk,
    )
}

/** Blue info box ("We sent a new code to …"). */
@Composable
internal fun AuthNotice(message: String, modifier: Modifier = Modifier) {
    Text(
        message,
        modifier = modifier.fillMaxWidth()
            .background(BauColors.BlueSoft, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        fontSize = 14.sp, lineHeight = 19.sp, color = BauColors.BlueDeep, textAlign = TextAlign.Center,
    )
}
