package com.vesper.baubuild.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vesper.baubuild.designsystem.BauColors

/**
 * Log In, in the same sub-screen layout as Sign Up / Forgot Password. Microsoft only: the email (optional) is
 * passed to Microsoft as `login_hint`. Progress and errors come from [AuthViewModel] via [authState].
 */
@Composable
internal fun LoginScreen(
    authState: AuthUiState,
    state: LoginUiState,
    onEmailChange: (String) -> Unit,
    onContinue: () -> Unit,
    onCancelSignIn: () -> Unit,
    onForgotPassword: () -> Unit,
    onSignUp: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    val signingIn = authState.isSigningIn
    val continueIfReady = {
        if (!signingIn) {
            focus.clearFocus()
            onContinue()
        }
    }
    AuthSubScreen(
        title = "Log In",
        subtitle = "Welcome back. Continue with your BAU Microsoft account.",
        onBack = onBack,
        modifier = modifier,
        hero = true,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel("BAU email")
            OutlinedTextField(
                value = state.email,
                onValueChange = onEmailChange,
                singleLine = true,
                enabled = !signingIn,
                placeholder = { Text("name@stu.bau.edu", color = BauColors.TextTertiary) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { continueIfReady() }),
                shape = RoundedCornerShape(16.dp),
                colors = authFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .semantics { contentType = ContentType.Username + ContentType.EmailAddress },
            )
            DomainCheckLine(state.domainCheck)
        }

        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (authState.status as? AuthStatus.Error)?.let { AuthInlineError(it.message) }
            // Same stack as Welcome: the button, then "Forgot password?" aligned right beneath it.
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                AuthPrimaryButton(
                    text = "Continue with Microsoft",
                    onClick = continueIfReady,
                    loading = signingIn,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!signingIn) AuthTextAction(lead = null, action = "Forgot password?", onClick = onForgotPassword)
            }
            if (signingIn) {
                Text("Finish signing in with Microsoft…", fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.TextSecondary)
                AuthTextAction(lead = null, action = "Cancel", onClick = onCancelSignIn)
            } else {
                AuthTextAction(lead = "New here?", action = "Sign Up", onClick = onSignUp)
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = BauColors.TextSecondary)
}
