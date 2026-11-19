package com.vesper.baubuild.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * Forgot password: step 1 asks for the BAU email and sends a code; step 2 takes the code and a new password and
 * signs in. Back on step 2 returns to step 1 ("Wrong email? Change it").
 */
@Composable
internal fun ForgotPasswordScreen(
    state: ForgotPasswordUiState,
    onEmailChange: (String) -> Unit,
    onSendCode: () -> Unit,
    onCodeChange: (String) -> Unit,
    onNewPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onTogglePasswordVisible: () -> Unit,
    onReset: () -> Unit,
    onResend: () -> Unit,
    onChangeEmail: () -> Unit,
    onLogIn: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    when (state.step) {
        ForgotPasswordStep.EnterEmail -> {
            val send = {
                if (state.canSendCode) {
                    focus.clearFocus()
                    onSendCode()
                }
            }
            AuthSubScreen(
                title = "Forgot password?",
                subtitle = "Enter your BAU email and we’ll send you a code to reset it.",
                onBack = onBack,
                modifier = modifier,
            ) {
                AuthTextField(
                    label = "BAU email",
                    value = state.email,
                    onValueChange = onEmailChange,
                    placeholder = "name@stu.bau.edu",
                    keyboardOptions = EmailKeyboard.copy(imeAction = ImeAction.Send),
                    onImeAction = send,
                    contentType = ContentType.Username + ContentType.EmailAddress,
                    enabled = !state.submitting,
                    below = { if (state.domainCheck is DomainCheck.External) DomainCheckLine(state.domainCheck) },
                )
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.error?.let { AuthInlineError(it) }
                    AuthPrimaryButton(
                        text = "Send code",
                        onClick = send,
                        enabled = state.canSendCode,
                        loading = state.submitting,
                        loadingDescription = "Sending the code",
                    )
                    AuthTextAction(lead = "Remembered it?", action = "Log In", onClick = onLogIn)
                }
            }
        }
        ForgotPasswordStep.Reset -> {
            val passwordFocus = remember { FocusRequester() }
            val confirmFocus = remember { FocusRequester() }
            val reset = {
                focus.clearFocus()
                if (state.canReset) onReset()
            }
            AuthSubScreen(
                title = "Reset password",
                subtitle = "Enter the code from your BAU inbox and choose a new password.",
                onBack = onChangeEmail,
                modifier = modifier,
            ) {
                state.notice?.let { AuthNotice(it) }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AuthFieldLabel("6-digit code")
                    VerificationCodeField(
                        code = state.code,
                        onCodeChange = { raw ->
                            onCodeChange(raw)
                            if (normalizeCode(raw).length == CODE_LENGTH) runCatching { passwordFocus.requestFocus() }
                        },
                        enabled = !state.submitting,
                        onDone = { runCatching { passwordFocus.requestFocus() } },
                    )
                }
                AuthTextField(
                    label = "New password",
                    value = state.newPassword,
                    onValueChange = onNewPasswordChange,
                    placeholder = "At least 8 characters",
                    keyboardOptions = PasswordKeyboard,
                    onImeAction = { runCatching { confirmFocus.requestFocus() } },
                    contentType = ContentType.NewPassword,
                    enabled = !state.submitting,
                    focusRequester = passwordFocus,
                    password = true,
                    passwordVisible = state.passwordVisible,
                    onTogglePasswordVisible = onTogglePasswordVisible,
                    below = { PasswordRulesLine(state.passwordCheck) },
                )
                AuthTextField(
                    label = "Confirm new password",
                    value = state.confirmPassword,
                    onValueChange = onConfirmPasswordChange,
                    placeholder = "Type it again",
                    keyboardOptions = PasswordKeyboard.copy(imeAction = ImeAction.Done),
                    onImeAction = reset,
                    contentType = ContentType.NewPassword,
                    enabled = !state.submitting,
                    isError = state.confirmMismatch,
                    focusRequester = confirmFocus,
                    password = true,
                    passwordVisible = state.passwordVisible,
                    onTogglePasswordVisible = onTogglePasswordVisible,
                    below = { if (state.confirmMismatch) AuthFieldHint("Passwords don’t match") },
                )
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.error?.let { AuthInlineError(it) }
                    AuthPrimaryButton(
                        text = "Reset Password",
                        onClick = reset,
                        enabled = state.canReset,
                        loading = state.submitting,
                        loadingDescription = "Resetting your password",
                    )
                    ResendCodeLine(state.resendInSeconds, state.canResend, state.resending, onResend)
                    AuthTextAction(lead = "Wrong email?", action = "Change it", onClick = onChangeEmail)
                }
            }
        }
    }
}
