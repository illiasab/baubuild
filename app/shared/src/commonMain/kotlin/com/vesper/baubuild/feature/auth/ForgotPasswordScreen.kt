package com.vesper.baubuild.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vesper.baubuild.designsystem.BauCard
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.BauSecondaryButton

/**
 * Forgot Password. BAU BUILD has no password of its own: it's the BAU Microsoft account's password, so we
 * send the user to Microsoft self-service reset, or to BAU IT.
 */
@Composable
internal fun ForgotPasswordScreen(
    state: ForgotPasswordUiState,
    onResetWithMicrosoft: () -> Unit,
    onContactIt: () -> Unit,
    onBackToSignIn: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AuthSubScreen(
        title = "Forgot password?",
        subtitle = "BAU BUILD doesn’t have its own password. You sign in with your BAU Microsoft account.",
        onBack = onBack,
        modifier = modifier,
    ) {
        BauCard(Modifier.fillMaxWidth()) {
            Text(
                buildAnnotatedString {
                    append("Your password is the one for your ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = BauColors.Ink)) { append("BAU Microsoft account") }
                    append(" — the same as BAU email, Teams and Office. Resetting it there resets it everywhere, including here.")
                },
                fontSize = 15.sp, lineHeight = 22.sp, color = BauColors.TextSecondary,
            )
        }

        AuthSectionLabel("What to do")
        AuthStep(1, "Reset it with Microsoft", "Verify with your phone or backup email on Microsoft’s reset page.")
        AuthStep(2, "Come back and sign in", "Use “Log In” and continue with Microsoft.")
        AuthStep(3, "Still stuck?", "BAU IT can unlock your account or set up recovery options.")

        Column(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.unopenedLink?.let { link ->
                AuthInlineError("Couldn’t open that link on this device.")
                SelectionContainer {
                    Text(
                        link.removePrefix("mailto:").substringBefore('?'),
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = BauColors.Ink,
                    )
                }
            }
            AuthPrimaryButton("Reset with Microsoft", onResetWithMicrosoft)
            BauSecondaryButton("Contact BAU IT", onContactIt, Modifier.fillMaxWidth())
            AuthTextAction(lead = "Remembered it?", action = "Back to sign in", onClick = onBackToSignIn)
        }
    }
}
