package com.vesper.baubuild.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vesper.baubuild.designsystem.BauColors

/** Verify Email: the 6-digit code from the BAU inbox (auto-submits on the 6th digit), resend with cooldown. */
@Composable
internal fun VerifyEmailScreen(
    state: VerifyEmailUiState,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onResend: () -> Unit,
    onChangeEmail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val codeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { codeFocus.requestFocus() } }
    AuthSubScreen(
        title = "Check your BAU inbox",
        subtitle = "Enter the 6-digit code we emailed you. It’s valid for 15 minutes.",
        onBack = onChangeEmail,
        modifier = modifier,
    ) {
        Text(
            buildAnnotatedString {
                append("Code sent to ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = BauColors.Ink)) { append(state.email) }
            },
            fontSize = 15.sp, lineHeight = 21.sp, color = BauColors.TextSecondary,
        )
        VerificationCodeField(
            code = state.code,
            onCodeChange = onCodeChange,
            enabled = !state.submitting,
            isError = state.error != null,
            focusRequester = codeFocus,
            onDone = onSubmit,
        )
        Text(
            "Can’t find it? Check your Junk or Spam folder.",
            fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.TextTertiary,
        )
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.notice?.let { AuthNotice(it) }
            state.error?.let { AuthInlineError(it) }
            AuthPrimaryButton(
                text = "Verify Email",
                onClick = onSubmit,
                enabled = state.canSubmit,
                loading = state.submitting,
                loadingDescription = "Checking the code",
            )
            ResendCodeLine(state.resendInSeconds, state.canResend, state.resending, onResend)
            AuthTextAction(lead = "Wrong email?", action = "Change it", onClick = onChangeEmail)
        }
    }
}
