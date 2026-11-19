package com.vesper.baubuild.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
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
import com.vesper.baubuild.designsystem.BauCard
import com.vesper.baubuild.designsystem.BauColors

/**
 * Sign Up: BAU BUILD has no passwords of its own; "signing up" is the first Microsoft sign-in with a BAU
 * account. This screen explains who it's for, optionally checks an email's domain offline, and starts the
 * Microsoft browser sign-in (with the email as `login_hint`).
 */
@Composable
internal fun SignUpScreen(
    authState: AuthUiState,
    state: SignUpUiState,
    onEmailChange: (String) -> Unit,
    onTermsAcceptedChange: (Boolean) -> Unit,
    onContinue: () -> Unit,
    onCancelSignIn: () -> Unit,
    onLogIn: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    AuthSubScreen(
        title = "Join BAU BUILD",
        subtitle = "Free for BAU students and staff. You sign up with your BAU Microsoft account — no new password.",
        onBack = onBack,
        modifier = modifier,
    ) {
        BauCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AudienceRow("Students", "@stu.bau.edu", "Pick projects, build them, get them approved.")
                AudienceRow("Staff", "@bau.edu", "Everything students can do, plus request projects.")
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("BAU email (optional)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = BauColors.TextSecondary)
            OutlinedTextField(
                value = state.email,
                onValueChange = onEmailChange,
                singleLine = true,
                enabled = !authState.isSigningIn,
                placeholder = { Text("name@stu.bau.edu", color = BauColors.TextTertiary) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                shape = RoundedCornerShape(16.dp),
                colors = authFieldColors(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            )
            DomainCheckLine(state.domainCheck)
        }

        AuthSectionLabel("How it works")
        AuthStep(1, "Sign in with your BAU Microsoft account", "The same account you use for BAU email and Teams.")
        AuthStep(2, "Pick a project", "Browse real BAU projects and say “I’m In”.")
        AuthStep(3, "Get it approved", "Build it with an AI mentor, then a dev lead reviews and approves it.")

        TermsCheckbox(
            checked = state.termsAccepted,
            enabled = !authState.isSigningIn,
            onCheckedChange = onTermsAcceptedChange,
        )

        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (authState.status as? AuthStatus.Error)?.let { AuthInlineError(it.message) }
            AuthPrimaryButton(
                text = "Continue with Microsoft",
                onClick = {
                    focus.clearFocus()
                    onContinue()
                },
                enabled = state.canContinue,
                loading = authState.isSigningIn,
            )
            if (authState.isSigningIn) {
                Text("Finish signing in with Microsoft…", fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.TextSecondary)
                AuthTextAction(lead = null, action = "Cancel", onClick = onCancelSignIn)
            } else {
                if (!state.canContinue) {
                    Text("Accept the terms to continue", fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.TextSecondary)
                }
                AuthTextAction(lead = "Already a member?", action = "Log In", onClick = onLogIn)
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
                fontSize = 16.sp, lineHeight = 21.sp, color = BauColors.Ink,
            )
            Text(detail, fontSize = 14.sp, lineHeight = 19.sp, color = BauColors.TextSecondary)
        }
    }
}

/** Live, offline domain feedback under the email field. Announced politely to screen readers. */
@Composable
internal fun DomainCheckLine(check: DomainCheck) {
    val (text, container, content) = when (check) {
        DomainCheck.None -> return
        DomainCheck.Student -> Triple("Student account · free", BauColors.BlueSoft, BauColors.BlueDeep)
        DomainCheck.Staff -> Triple("Staff account · free · can request projects", BauColors.BlueSoft, BauColors.BlueDeep)
        is DomainCheck.External -> Triple(
            if (check.requiresPayment) "Outside BAU · needs a paid plan" else "Only BAU accounts for now",
            BauColors.WarningSoft, BauColors.WarningInk,
        )
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
