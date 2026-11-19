package com.vesper.baubuild.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.BauTextLink

/**
 * Sign Up: full name, BAU email (live student / staff / not-allowed hint), password with rule hint, confirm,
 * terms. "Create Account" stays disabled until everything is valid; the server then emails a 6-digit code.
 */
@Composable
internal fun SignUpScreen(
    state: SignUpUiState,
    onNameChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onTogglePasswordVisible: () -> Unit,
    onTermsAcceptedChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onLogIn: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    val emailFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val confirmFocus = remember { FocusRequester() }
    val submit = {
        if (state.canSubmit) {
            focus.clearFocus()
            onSubmit()
        } else {
            focus.clearFocus()
        }
    }
    val editable = !state.submitting
    AuthSubScreen(
        title = "Join BAU BUILD",
        subtitle = "Free for BAU students and staff. Sign up with your BAU email.",
        onBack = onBack,
        modifier = modifier,
    ) {
        AuthTextField(
            label = "Full name",
            value = state.name,
            onValueChange = onNameChange,
            placeholder = "Sam Carter",
            keyboardOptions = NameKeyboard,
            onImeAction = { runCatching { emailFocus.requestFocus() } },
            contentType = ContentType.PersonFullName,
            enabled = editable,
        )
        AuthTextField(
            label = "BAU email",
            value = state.email,
            onValueChange = onEmailChange,
            placeholder = "name@stu.bau.edu",
            keyboardOptions = EmailKeyboard,
            onImeAction = { runCatching { passwordFocus.requestFocus() } },
            contentType = ContentType.NewUsername + ContentType.EmailAddress,
            enabled = editable,
            isError = state.domainCheck is DomainCheck.External || state.emailTaken,
            focusRequester = emailFocus,
            below = { DomainCheckLine(state.domainCheck) },
        )
        AuthTextField(
            label = "Password",
            value = state.password,
            onValueChange = onPasswordChange,
            placeholder = "At least 8 characters",
            keyboardOptions = PasswordKeyboard,
            onImeAction = { runCatching { confirmFocus.requestFocus() } },
            contentType = ContentType.NewPassword,
            enabled = editable,
            focusRequester = passwordFocus,
            password = true,
            passwordVisible = state.passwordVisible,
            onTogglePasswordVisible = onTogglePasswordVisible,
            below = { PasswordRulesLine(state.passwordCheck) },
        )
        AuthTextField(
            label = "Confirm password",
            value = state.confirmPassword,
            onValueChange = onConfirmPasswordChange,
            placeholder = "Type it again",
            keyboardOptions = PasswordKeyboard.copy(imeAction = ImeAction.Done),
            onImeAction = submit,
            contentType = ContentType.NewPassword,
            enabled = editable,
            isError = state.confirmMismatch,
            focusRequester = confirmFocus,
            password = true,
            passwordVisible = state.passwordVisible,
            onTogglePasswordVisible = onTogglePasswordVisible,
            below = { if (state.confirmMismatch) AuthFieldHint("Passwords don’t match") },
        )

        TermsCheckbox(checked = state.termsAccepted, enabled = editable, onCheckedChange = onTermsAcceptedChange)

        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.emailTaken) EmailTakenBox(onLogIn)
            state.error?.let { AuthInlineError(it) }
            AuthPrimaryButton(
                text = "Create Account",
                onClick = submit,
                enabled = state.canSubmit,
                loading = state.submitting,
                loadingDescription = "Creating your account",
            )
            AuthTextAction(lead = "Already a member?", action = "Log In", onClick = onLogIn)
        }
    }
}

/** `email_taken`: "An account with this email already exists. **Log In** instead?" */
@Composable
internal fun EmailTakenBox(onLogIn: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth()
            .background(BauColors.WarningSoft, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "An account with this email already exists.",
            fontSize = 14.sp, lineHeight = 19.sp, color = BauColors.WarningInk,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            BauTextLink("Log In", onClick = onLogIn, fontSize = 14.sp)
            Text("instead?", fontSize = 14.sp, color = BauColors.WarningInk)
        }
    }
}

/** Live, offline domain feedback under the email field. Announced politely to screen readers. */
@Composable
internal fun DomainCheckLine(check: DomainCheck) {
    val (text, container, content) = when (check) {
        DomainCheck.None -> return
        DomainCheck.Student -> Triple("Student account", BauColors.BlueSoft, BauColors.BlueDeep)
        DomainCheck.Staff -> Triple("Staff account · can request projects", BauColors.BlueSoft, BauColors.BlueDeep)
        is DomainCheck.External -> Triple(MSG_BAU_ONLY, BauColors.WarningSoft, BauColors.WarningInk)
    }
    Text(
        text,
        modifier = Modifier
            .padding(top = 2.dp)
            .background(container, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = content,
    )
}

@Composable
private fun TermsCheckbox(checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Row handles the click so the whole line is one 48dp target; the box just shows state.
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = CheckboxDefaults.colors(
                checkedColor = BauColors.Ink,
                uncheckedColor = BauColors.TextTertiary,
                checkmarkColor = BauColors.Yellow,
            ),
            modifier = Modifier.size(48.dp),
        )
        Text(
            buildAnnotatedString {
                append("I agree to the BAU BUILD ")
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = BauColors.Ink)) { append("Terms of Use") }
                append(" and ")
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = BauColors.Ink)) { append("Privacy Policy") }
                append(".") // TODO: link both once the documents are published.
            },
            modifier = Modifier.weight(1f),
            fontSize = 14.sp, lineHeight = 20.sp, color = BauColors.TextSecondary,
        )
    }
}
