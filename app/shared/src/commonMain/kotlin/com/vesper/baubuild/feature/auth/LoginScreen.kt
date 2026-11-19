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
 * Log In: BAU email + password, in the welcome-style hero header (animated wave + brand blocks).
 * Next on the email moves to the password; Done on the password submits.
 */
@Composable
internal fun LoginScreen(
    state: LoginUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisible: () -> Unit,
    onSubmit: () -> Unit,
    onForgotPassword: () -> Unit,
    onSignUp: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    val passwordFocus = remember { FocusRequester() }
    val submit = {
        if (state.canSubmit) {
            focus.clearFocus()
            onSubmit()
        }
    }
    AuthSubScreen(
        title = "Log In",
        subtitle = "Welcome back. Log in with your BAU email.",
        onBack = onBack,
        modifier = modifier,
        hero = true,
    ) {
        AuthTextField(
            label = "BAU email",
            value = state.email,
            onValueChange = onEmailChange,
            placeholder = "name@stu.bau.edu",
            keyboardOptions = EmailKeyboard,
            onImeAction = { runCatching { passwordFocus.requestFocus() } },
            contentType = ContentType.Username + ContentType.EmailAddress,
            enabled = !state.submitting,
            below = { if (state.domainCheck is DomainCheck.External) DomainCheckLine(state.domainCheck) },
        )
        AuthTextField(
            label = "Password",
            value = state.password,
            onValueChange = onPasswordChange,
            placeholder = "Your password",
            keyboardOptions = PasswordKeyboard.copy(imeAction = ImeAction.Done),
            onImeAction = submit,
            contentType = ContentType.Password,
            enabled = !state.submitting,
            focusRequester = passwordFocus,
            password = true,
            passwordVisible = state.passwordVisible,
            onTogglePasswordVisible = onTogglePasswordVisible,
        )

        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.error?.let { AuthInlineError(it) }
            // Same stack as Welcome: the button, then "Forgot password?" aligned right beneath it.
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                AuthPrimaryButton(
                    text = "Log In",
                    onClick = submit,
                    enabled = state.canSubmit,
                    loading = state.submitting,
                    loadingDescription = "Logging in",
                    modifier = Modifier.fillMaxWidth(),
                )
                AuthTextAction(lead = null, action = "Forgot password?", onClick = onForgotPassword)
            }
            AuthTextAction(lead = "New here?", action = "Sign Up", onClick = onSignUp)
        }
    }
}
