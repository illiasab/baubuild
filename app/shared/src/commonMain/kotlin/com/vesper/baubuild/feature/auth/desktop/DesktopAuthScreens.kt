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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.MonoFamily
import com.vesper.baubuild.feature.auth.AuthInlineError
import com.vesper.baubuild.feature.auth.AuthStatus
import com.vesper.baubuild.feature.auth.AuthUiState
import com.vesper.baubuild.feature.auth.BauBuildMark
import com.vesper.baubuild.feature.auth.DomainCheckLine
import com.vesper.baubuild.feature.auth.ForgotPasswordUiState
import com.vesper.baubuild.feature.auth.LoginUiState
import com.vesper.baubuild.feature.auth.SignUpUiState
import com.vesper.baubuild.feature.auth.UNKNOWN_ACCOUNT_LABEL

// Desktop variants of the signed-out screens. Same callbacks as the mobile screens (AuthRoot wires both to the
// same ViewModels); only the layout differs. Each renders the form card that DesktopAuthFrame centres.

private val emailKeyboard = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Email,
    imeAction = ImeAction.Next,
)

/** Moves keyboard focus into the form when the screen appears (desktop: start typing right away). */
@Composable
private fun AutoFocus(requester: FocusRequester) {
    LaunchedEffect(requester) { runCatching { requester.requestFocus() } }
}

@Composable
internal fun DesktopWelcome(
    state: AuthUiState,
    onLogIn: () -> Unit,
    onSignUp: () -> Unit,
    onForgotPassword: () -> Unit,
    onCancel: () -> Unit,
) {
    DesktopFormCard(
        title = "Start building",
        subtitle = "Sign in with your BAU Microsoft account. No new password to remember.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            (state.status as? AuthStatus.Error)?.let { AuthInlineError(it.message) }
            // "Sign Up" and "Log In" only navigate; Microsoft sign-in starts from Sign Up's Continue.
            DesktopPrimaryButton("Sign Up", onClick = onSignUp, loading = state.isSigningIn)
            if (state.isSigningIn) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Finish signing in with Microsoft…", fontSize = 13.sp, color = BauColors.TextSecondary)
                    DesktopTextLink("Cancel", onCancel, Modifier.padding(start = 6.dp))
                }
            } else {
                DesktopSecondaryButton("Log In", onClick = onLogIn)
            }
        }
        if (!state.isSigningIn) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                DesktopTextLink("Forgot password?", onForgotPassword)
            }
        }
    }
}


@Composable
internal fun DesktopLogin(
    authState: AuthUiState,
    state: LoginUiState,
    onEmailChange: (String) -> Unit,
    onContinue: () -> Unit,
    onCancelSignIn: () -> Unit,
    onForgotPassword: () -> Unit,
    onSignUp: () -> Unit,
    onBack: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val emailFocus = remember { FocusRequester() }
    val signingIn = authState.isSigningIn
    val continueIfReady = {
        if (!signingIn) {
            focus.clearFocus()
            onContinue()
        }
    }
    AutoFocus(emailFocus)

    DesktopFormCard(
        title = "Log In",
        subtitle = "Welcome back. Continue with your BAU Microsoft account.",
        onBack = onBack,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DesktopFieldLabel("BAU email")
            DesktopTextField(
                value = state.email,
                onValueChange = onEmailChange,
                placeholder = "name@stu.bau.edu",
                keyboardOptions = emailKeyboard.copy(imeAction = ImeAction.Go),
                // Enter: continue to Microsoft with the typed address as the hint.
                onEnter = continueIfReady,
                enabled = !signingIn,
                modifier = Modifier
                    .focusRequester(emailFocus)
                    .semantics { contentType = ContentType.Username + ContentType.EmailAddress },
            )
            DomainCheckLine(state.domainCheck)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            (authState.status as? AuthStatus.Error)?.let { AuthInlineError(it.message) }
            DesktopPrimaryButton("Continue with Microsoft", onClick = continueIfReady, loading = signingIn)
            if (signingIn) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Finish signing in with Microsoft…", fontSize = 13.sp, color = BauColors.TextSecondary)
                    DesktopTextLink("Cancel", onCancelSignIn, Modifier.padding(start = 6.dp))
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    DesktopTextLink("Forgot password?", onForgotPassword)
                }
            }
        }
        if (!signingIn) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                DesktopTextLink("Sign Up", onSignUp, lead = "New here?")
            }
        }
    }
}

@Composable
internal fun DesktopSignUp(
    authState: AuthUiState,
    state: SignUpUiState,
    onEmailChange: (String) -> Unit,
    onTermsAcceptedChange: (Boolean) -> Unit,
    onContinue: () -> Unit,
    onCancelSignIn: () -> Unit,
    onLogIn: () -> Unit,
    onBack: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val emailFocus = remember { FocusRequester() }
    val continueIfReady = {
        if (state.canContinue && !authState.isSigningIn) {
            focus.clearFocus()
            onContinue()
        }
    }
    AutoFocus(emailFocus)

    DesktopFormCard(
        title = "Join BAU BUILD",
        subtitle = "Free for BAU students and staff. You sign up with your BAU Microsoft account — no new password.",
        onBack = onBack,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(BauColors.Paper, RoundedCornerShape(10.dp))
                .border(1.dp, WebColors.Border, RoundedCornerShape(10.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AudienceRow("Students", "@stu.bau.edu", "Pick projects, build them, get them approved.")
            AudienceRow("Staff", "@bau.edu", "Everything students can do, plus request projects.")
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DesktopFieldLabel("BAU email (optional)")
            DesktopTextField(
                value = state.email,
                onValueChange = onEmailChange,
                placeholder = "name@stu.bau.edu",
                keyboardOptions = emailKeyboard.copy(imeAction = ImeAction.Done),
                onEnter = continueIfReady,
                enabled = !authState.isSigningIn,
                modifier = Modifier.focusRequester(emailFocus),
            )
            DomainCheckLine(state.domainCheck)
        }
        DesktopCheckboxRow(
            checked = state.termsAccepted,
            enabled = !authState.isSigningIn,
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
            (authState.status as? AuthStatus.Error)?.let { AuthInlineError(it.message) }
            DesktopPrimaryButton(
                "Continue with Microsoft",
                onClick = continueIfReady,
                enabled = state.canContinue,
                loading = authState.isSigningIn,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (authState.isSigningIn) {
                    Text("Finish signing in with Microsoft…", fontSize = 13.sp, color = BauColors.TextSecondary)
                    DesktopTextLink("Cancel", onCancelSignIn, Modifier.padding(start = 6.dp))
                } else if (!state.canContinue) {
                    Text("Accept the terms to continue", fontSize = 13.sp, color = BauColors.TextSecondary)
                }
            }
        }
        if (!authState.isSigningIn) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                DesktopTextLink("Log In", onLogIn, lead = "Already a member?")
            }
        }
    }
}

@Composable
private fun AudienceRow(who: String, domain: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(top = 6.dp).size(8.dp).background(BauColors.Yellow, CircleShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(who) }
                    append(" · ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(domain) }
                },
                fontSize = 15.sp, lineHeight = 20.sp, color = BauColors.Ink,
            )
            Text(detail, fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.TextSecondary)
        }
    }
}

@Composable
internal fun DesktopForgotPassword(
    state: ForgotPasswordUiState,
    onResetWithMicrosoft: () -> Unit,
    onContactIt: () -> Unit,
    onBackToSignIn: () -> Unit,
    onBack: () -> Unit,
) {
    DesktopFormCard(
        title = "Forgot password?",
        subtitle = "BAU BUILD doesn’t have its own password. You sign in with your BAU Microsoft account.",
        onBack = onBack,
    ) {
        Text(
            buildAnnotatedString {
                append("Your password is the one for your ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = BauColors.Ink)) { append("BAU Microsoft account") }
                append(" — the same as BAU email, Teams and Office. Resetting it there resets it everywhere, including here.")
            },
            modifier = Modifier
                .fillMaxWidth()
                .background(BauColors.Paper, RoundedCornerShape(10.dp))
                .border(1.dp, WebColors.Border, RoundedCornerShape(10.dp))
                .padding(16.dp),
            fontSize = 14.sp, lineHeight = 21.sp, color = BauColors.TextSecondary,
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DesktopEyebrow("What to do")
            DesktopStep(1, "Reset it with Microsoft", "Verify with your phone or backup email on Microsoft’s reset page.")
            DesktopStep(2, "Come back and sign in", "Use “Log In” and continue with Microsoft.")
            DesktopStep(3, "Still stuck?", "BAU IT can unlock your account or set up recovery options.")
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.unopenedLink?.let { link ->
                AuthInlineError("Couldn’t open that link on this device.")
                SelectionContainer(Modifier.align(Alignment.CenterHorizontally)) {
                    Text(
                        link.removePrefix("mailto:").substringBefore('?'),
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = BauColors.Ink,
                    )
                }
            }
            DesktopPrimaryButton("Reset with Microsoft", onResetWithMicrosoft)
            DesktopSecondaryButton("Contact BAU IT", onContactIt)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            DesktopTextLink("Back to sign in", onBackToSignIn, lead = "Remembered it?")
        }
    }
}

@Composable
internal fun DesktopNotAllowed(
    status: AuthStatus,
    onUseAnotherAccount: () -> Unit,
    onSignOut: () -> Unit,
) {
    val (email, title, body) = when (status) {
        is AuthStatus.RequiresPayment -> Triple(
            status.email,
            "This account needs a paid plan",
            "BAU BUILD is free for BAU students and staff. Accounts from outside BAU need a paid plan, " +
                "which isn't available in the app yet. Sign in with your BAU account to keep going for free.",
        )
        is AuthStatus.BlockedDomain -> Triple(
            status.email,
            "BAU BUILD is open to BAU students and staff for now",
            "You signed in with an account from outside BAU. Use your BAU Microsoft account to join. " +
                "We plan to open BAU BUILD to everyone later.",
        )
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
                DesktopEyebrow("Signed in as", color = BauColors.TextTertiary)
                SelectionContainer {
                    Text(email.ifBlank { UNKNOWN_ACCOUNT_LABEL }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = BauColors.Ink)
                }
                Box(Modifier.height(6.dp))
                DomainRule("Students", "@stu.bau.edu")
                DomainRule("Staff", "@bau.edu")
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DesktopPrimaryButton("Use My BAU Account", onUseAnotherAccount)
                DesktopSecondaryButton("Sign Out", onSignOut)
            }
        }
    }
}

@Composable
private fun DomainRule(who: String, domain: String) {
    Text(
        buildAnnotatedString {
            append("$who sign in with ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = BauColors.Ink)) { append(domain) }
        },
        fontSize = 14.sp, lineHeight = 20.sp, color = BauColors.TextSecondary,
    )
}
