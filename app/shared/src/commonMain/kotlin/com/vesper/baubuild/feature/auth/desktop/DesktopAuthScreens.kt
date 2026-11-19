package com.vesper.baubuild.feature.auth.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.feature.auth.AuthFieldHint
import com.vesper.baubuild.feature.auth.AuthInlineError
import com.vesper.baubuild.feature.auth.AuthNotice
import com.vesper.baubuild.feature.auth.AuthStatus
import com.vesper.baubuild.feature.auth.BauBuildMark
import com.vesper.baubuild.feature.auth.CODE_LENGTH
import com.vesper.baubuild.feature.auth.DomainCheck
import com.vesper.baubuild.feature.auth.DomainCheckLine
import com.vesper.baubuild.feature.auth.EmailKeyboard
import com.vesper.baubuild.feature.auth.FORGOT_PASSWORD_MESSAGE
import com.vesper.baubuild.feature.auth.LoginUiState
import com.vesper.baubuild.feature.auth.MSG_BAU_ONLY
import com.vesper.baubuild.feature.auth.NOT_ALLOWED_BODY
import com.vesper.baubuild.feature.auth.NameKeyboard
import com.vesper.baubuild.feature.auth.PasswordKeyboard
import com.vesper.baubuild.feature.auth.PasswordRulesLine
import com.vesper.baubuild.feature.auth.ResendCodeLine
import com.vesper.baubuild.feature.auth.SUPPORT_EMAIL
import com.vesper.baubuild.feature.auth.SUPPORT_MAILTO
import com.vesper.baubuild.feature.auth.SignUpUiState
import com.vesper.baubuild.feature.auth.UNKNOWN_ACCOUNT_LABEL
import com.vesper.baubuild.feature.auth.VerificationCodeField
import com.vesper.baubuild.feature.auth.VerifyEmailUiState
import com.vesper.baubuild.feature.auth.normalizeCode

// Desktop variants of the signed-out screens. Same ViewModels as the mobile screens (AuthRoot wires both);
// only the layout differs. Each renders the form card that DesktopAuthFrame centres. Enter moves to the next
// field and submits on the last one; Tab works as usual.

/** Moves keyboard focus into the form when the screen appears (desktop: start typing right away). */
@Composable
private fun AutoFocus(requester: FocusRequester) {
    LaunchedEffect(requester) { runCatching { requester.requestFocus() } }
}

private fun FocusRequester.tryFocus() {
    runCatching { requestFocus() }
}

@Composable
private fun DesktopPasswordToggle(visible: Boolean, onToggle: () -> Unit) {
    DesktopTextLink(
        if (visible) "Hide" else "Show",
        onToggle,
        Modifier.padding(end = 8.dp).semantics { contentDescription = if (visible) "Hide password" else "Show password" },
    )
}

/** Labelled desktop field (with optional secure entry + Show/Hide). */
@Composable
private fun DesktopLabelledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    imeAction: ImeAction,
    onEnter: () -> Unit,
    contentType: ContentType,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    keyboard: androidx.compose.foundation.text.KeyboardOptions = EmailKeyboard,
    isError: Boolean = false,
    password: Boolean = false,
    passwordVisible: Boolean = false,
    onTogglePasswordVisible: () -> Unit = {},
    below: @Composable (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DesktopFieldLabel(label)
        DesktopTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            keyboardOptions = keyboard.copy(imeAction = imeAction),
            onEnter = onEnter,
            enabled = enabled,
            isError = isError,
            visualTransformation = if (password && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
            trailingIcon = if (password) {
                { DesktopPasswordToggle(passwordVisible, onTogglePasswordVisible) }
            } else null,
            modifier = modifier.semantics { this.contentType = contentType },
        )
        below?.invoke()
    }
}

@Composable
private fun CenteredLink(action: String, onClick: () -> Unit, lead: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        DesktopTextLink(action, onClick, lead = lead)
    }
}

@Composable
private fun DesktopResendLine(secondsLeft: Long, canResend: Boolean, resending: Boolean, onResend: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        ResendCodeLine(secondsLeft, canResend, resending, onResend) { text, onClick, enabled ->
            if (enabled) DesktopTextLink(text, onClick) else Text(text, fontSize = 14.sp, color = BauColors.TextTertiary)
        }
    }
}

@Composable
internal fun DesktopWelcome(
    onLogIn: () -> Unit,
    onSignUp: () -> Unit,
    onForgotPassword: () -> Unit,
) {
    DesktopFormCard(
        title = "Start building",
        subtitle = "Free for BAU students and staff. Sign up with your @stu.bau.edu or @bau.edu email.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DesktopPrimaryButton("Sign Up", onClick = onSignUp)
            DesktopSecondaryButton("Log In", onClick = onLogIn)
        }
        CenteredLink("Forgot password?", onForgotPassword)
    }
}

@Composable
internal fun DesktopLogin(
    state: LoginUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisible: () -> Unit,
    onSubmit: () -> Unit,
    onForgotPassword: () -> Unit,
    onSignUp: () -> Unit,
    onBack: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val emailFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val submit = {
        if (state.canSubmit) {
            focus.clearFocus()
            onSubmit()
        }
    }
    AutoFocus(emailFocus)

    DesktopFormCard(title = "Log In", subtitle = "Welcome back. Log in with your BAU email.", onBack = onBack) {
        DesktopLabelledField(
            label = "BAU email",
            value = state.email,
            onValueChange = onEmailChange,
            placeholder = "name@stu.bau.edu",
            imeAction = ImeAction.Next,
            onEnter = { passwordFocus.tryFocus() },
            contentType = ContentType.Username + ContentType.EmailAddress,
            enabled = !state.submitting,
            modifier = Modifier.focusRequester(emailFocus),
            below = { if (state.domainCheck is DomainCheck.External) DomainCheckLine(state.domainCheck) },
        )
        DesktopLabelledField(
            label = "Password",
            value = state.password,
            onValueChange = onPasswordChange,
            placeholder = "Your password",
            imeAction = ImeAction.Done,
            onEnter = submit,
            contentType = ContentType.Password,
            enabled = !state.submitting,
            keyboard = PasswordKeyboard,
            modifier = Modifier.focusRequester(passwordFocus),
            password = true,
            passwordVisible = state.passwordVisible,
            onTogglePasswordVisible = onTogglePasswordVisible,
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.error?.let { AuthInlineError(it) }
            DesktopPrimaryButton("Log In", onClick = submit, enabled = state.canSubmit, loading = state.submitting, loadingDescription = "Logging in")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                DesktopTextLink("Forgot password?", onForgotPassword)
            }
        }
        CenteredLink("Sign Up", onSignUp, lead = "New here?")
    }
}

@Composable
internal fun DesktopSignUp(
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
) {
    val focus = LocalFocusManager.current
    val nameFocus = remember { FocusRequester() }
    val emailFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val confirmFocus = remember { FocusRequester() }
    val submit = {
        if (state.canSubmit) {
            focus.clearFocus()
            onSubmit()
        }
    }
    val editable = !state.submitting
    AutoFocus(nameFocus)

    DesktopFormCard(
        title = "Join BAU BUILD",
        subtitle = "Free for BAU students and staff. Sign up with your BAU email.",
        onBack = onBack,
    ) {
        DesktopLabelledField(
            label = "Full name",
            value = state.name,
            onValueChange = onNameChange,
            placeholder = "Sam Carter",
            imeAction = ImeAction.Next,
            onEnter = { emailFocus.tryFocus() },
            contentType = ContentType.PersonFullName,
            enabled = editable,
            keyboard = NameKeyboard,
            modifier = Modifier.focusRequester(nameFocus),
        )
        DesktopLabelledField(
            label = "BAU email",
            value = state.email,
            onValueChange = onEmailChange,
            placeholder = "name@stu.bau.edu",
            imeAction = ImeAction.Next,
            onEnter = { passwordFocus.tryFocus() },
            contentType = ContentType.NewUsername + ContentType.EmailAddress,
            enabled = editable,
            isError = state.domainCheck is DomainCheck.External || state.emailTaken,
            modifier = Modifier.focusRequester(emailFocus),
            below = { DomainCheckLine(state.domainCheck) },
        )
        DesktopLabelledField(
            label = "Password",
            value = state.password,
            onValueChange = onPasswordChange,
            placeholder = "At least 8 characters",
            imeAction = ImeAction.Next,
            onEnter = { confirmFocus.tryFocus() },
            contentType = ContentType.NewPassword,
            enabled = editable,
            keyboard = PasswordKeyboard,
            modifier = Modifier.focusRequester(passwordFocus),
            password = true,
            passwordVisible = state.passwordVisible,
            onTogglePasswordVisible = onTogglePasswordVisible,
            below = { PasswordRulesLine(state.passwordCheck) },
        )
        DesktopLabelledField(
            label = "Confirm password",
            value = state.confirmPassword,
            onValueChange = onConfirmPasswordChange,
            placeholder = "Type it again",
            imeAction = ImeAction.Done,
            onEnter = submit,
            contentType = ContentType.NewPassword,
            enabled = editable,
            keyboard = PasswordKeyboard,
            isError = state.confirmMismatch,
            modifier = Modifier.focusRequester(confirmFocus),
            password = true,
            passwordVisible = state.passwordVisible,
            onTogglePasswordVisible = onTogglePasswordVisible,
            below = { if (state.confirmMismatch) AuthFieldHint("Passwords don’t match") },
        )
        DesktopCheckboxRow(
            checked = state.termsAccepted,
            enabled = editable,
            onCheckedChange = onTermsAcceptedChange,
            label = buildAnnotatedString {
                append("I agree to the BAU BUILD ")
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = BauColors.Ink)) { append("Terms of Use") }
                append(" and ")
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = BauColors.Ink)) { append("Privacy Policy") }
                append(".") // TODO: link both once the documents are published (same as mobile).
            },
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.emailTaken) {
                Column(
                    Modifier.fillMaxWidth().background(BauColors.WarningSoft, RoundedCornerShape(10.dp)).padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("An account with this email already exists.", fontSize = 14.sp, color = BauColors.WarningInk)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DesktopTextLink("Log In", onLogIn)
                        Text(" instead?", fontSize = 14.sp, color = BauColors.WarningInk)
                    }
                }
            }
            state.error?.let { AuthInlineError(it) }
            DesktopPrimaryButton(
                "Create Account",
                onClick = submit,
                enabled = state.canSubmit,
                loading = state.submitting,
                loadingDescription = "Creating your account",
            )
        }
        CenteredLink("Log In", onLogIn, lead = "Already a member?")
    }
}

@Composable
internal fun DesktopVerifyEmail(
    state: VerifyEmailUiState,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onResend: () -> Unit,
    onChangeEmail: () -> Unit,
) {
    val codeFocus = remember { FocusRequester() }
    AutoFocus(codeFocus)
    DesktopFormCard(
        title = "Check your BAU inbox",
        subtitle = "Enter the 6-digit code we emailed you. It’s valid for 15 minutes.",
        onBack = onChangeEmail,
    ) {
        Text(
            buildAnnotatedString {
                append("Code sent to ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = BauColors.Ink)) { append(state.email) }
            },
            fontSize = 14.sp, lineHeight = 20.sp, color = BauColors.TextSecondary,
        )
        VerificationCodeField(
            code = state.code,
            onCodeChange = onCodeChange,
            enabled = !state.submitting,
            isError = state.error != null,
            focusRequester = codeFocus,
            onDone = onSubmit,
            cellHeight = 56.dp,
            cellShape = RoundedCornerShape(8.dp),
        )
        Text("Can’t find it? Check your Junk or Spam folder.", fontSize = 13.sp, color = BauColors.TextTertiary)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.notice?.let { AuthNotice(it) }
            state.error?.let { AuthInlineError(it) }
            DesktopPrimaryButton("Verify Email", onClick = onSubmit, enabled = state.canSubmit, loading = state.submitting, loadingDescription = "Checking the code")
        }
        DesktopResendLine(state.resendInSeconds, state.canResend, state.resending, onResend)
        CenteredLink("Change it", onChangeEmail, lead = "Wrong email?")
    }
}

@Composable
internal fun DesktopForgotPassword(
    onLogIn: () -> Unit,
    onBack: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    DesktopFormCard(
        title = "Forgot password?",
        subtitle = "Password reset by email isn’t available.",
        onBack = onBack,
    ) {
        AuthNotice(FORGOT_PASSWORD_MESSAGE)
        CenteredLink(SUPPORT_EMAIL, { runCatching { uriHandler.openUri(SUPPORT_MAILTO) } }, lead = "Email")
        CenteredLink("Back to Log In", onLogIn)
    }
}

@Composable
internal fun DesktopNotAllowed(
    status: AuthStatus,
    onUseAnotherAccount: () -> Unit,
    onDismiss: () -> Unit,
) {
    val (email, title, body) = when (status) {
        is AuthStatus.RequiresPayment -> Triple(
            status.email,
            "This account needs a paid plan",
            "BAU BUILD is free for BAU students and staff. Accounts from outside BAU need a paid plan, " +
                "which isn't available in the app yet. Log in with your BAU email to keep going for free.",
        )
        is AuthStatus.BlockedDomain -> Triple(status.email, "BAU BUILD is open to BAU students and staff for now", NOT_ALLOWED_BODY)
        else -> return
    }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Box(
            Modifier.size(56.dp).background(BauColors.Ink, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) { BauBuildMark(Modifier.size(30.dp)) }
        DesktopFormCard(title = title, subtitle = body) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(BauColors.Paper, RoundedCornerShape(10.dp))
                    .border(1.dp, WebColors.Border, RoundedCornerShape(10.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DesktopEyebrow("Email", color = BauColors.TextTertiary)
                SelectionContainer {
                    Text(email.ifBlank { UNKNOWN_ACCOUNT_LABEL }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = BauColors.Ink)
                }
                Box(Modifier.height(6.dp))
                Text(MSG_BAU_ONLY, fontSize = 14.sp, lineHeight = 20.sp, color = BauColors.TextSecondary)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DesktopPrimaryButton("Log In with a BAU Email", onUseAnotherAccount)
                DesktopSecondaryButton("Back", onDismiss)
            }
        }
    }
}
