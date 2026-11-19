package com.vesper.baubuild.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.vesper.baubuild.designsystem.BauTextLink

/** BAU BUILD sends no email: passwords are reset by support. */
internal const val SUPPORT_EMAIL = "support@baubuild.vesperconnect.org"
internal const val SUPPORT_MAILTO = "mailto:$SUPPORT_EMAIL?subject=Password%20reset"
internal const val FORGOT_PASSWORD_MESSAGE = "Forgot your password? Ask BAU BUILD support to reset it."

/** Forgot password: no email/code flow — a card pointing to BAU BUILD support (mailto) and back to Log In. */
@Composable
internal fun ForgotPasswordScreen(
    onLogIn: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    AuthSubScreen(
        title = "Forgot password?",
        subtitle = "Password reset by email isn’t available.",
        onBack = onBack,
        modifier = modifier,
    ) {
        AuthNotice(FORGOT_PASSWORD_MESSAGE)
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BauTextLink(SUPPORT_EMAIL, onClick = { runCatching { uriHandler.openUri(SUPPORT_MAILTO) } })
            AuthTextAction(lead = null, action = "Back to Log In", onClick = onLogIn)
        }
    }
}
