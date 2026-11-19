package com.vesper.baubuild.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.vesper.baubuild.designsystem.BauCard
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.BauPrimaryButton
import com.vesper.baubuild.designsystem.BauSecondaryButton
import com.vesper.baubuild.designsystem.BauSpacing

/**
 * Shown when Microsoft sign-in worked but the account isn't a BAU account. Explains the rule from
 * [com.vesper.baubuild.core.model.AccountPolicy] and offers a way out (another account / sign out).
 */
@Composable
internal fun NotAllowedScreen(
    status: AuthStatus,
    onUseAnotherAccount: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val email: String
    val title: String
    val body: String
    when (status) {
        is AuthStatus.RequiresPayment -> {
            email = status.email
            title = "This account needs a paid plan"
            body = "BAU BUILD is free for BAU students and staff. Accounts from outside BAU need a paid plan, " +
                "which isn't available in the app yet. Sign in with your BAU account to keep going for free."
        }
        is AuthStatus.BlockedDomain -> {
            email = status.email
            title = "BAU BUILD is open to BAU students and staff for now"
            body = "You signed in with an account from outside BAU. Use your BAU Microsoft account to join. " +
                "We plan to open BAU BUILD to everyone later."
        }
        else -> return
    }

    Box(modifier.fillMaxSize().background(BauColors.Paper), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 28.dp, vertical = BauSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(BauSpacing.lg),
        ) {
            Box(
                Modifier.size(64.dp).background(BauColors.Ink, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) { BauBuildMark(Modifier.size(36.dp)) }

            Text(
                title,
                modifier = Modifier.semantics { heading() },
                fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold,
                letterSpacing = (-0.02).em, color = BauColors.Ink,
            )
            Text(body, fontSize = 17.sp, lineHeight = 24.sp, color = BauColors.TextSecondary)

            BauCard(Modifier.fillMaxWidth()) {
                Text("SIGNED IN AS", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.06.em,
                    color = BauColors.TextTertiary)
                Text(email, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = BauColors.Ink,
                    modifier = Modifier.padding(top = 2.dp, bottom = BauSpacing.md))
                DomainRule("Students", "@stu.bau.edu")
                DomainRule("Staff", "@bau.edu")
            }

            Spacer(Modifier.height(BauSpacing.sm))
            BauPrimaryButton("Use My BAU Account", onUseAnotherAccount, Modifier.fillMaxWidth())
            BauSecondaryButton("Sign Out", onSignOut, Modifier.fillMaxWidth())
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
        fontSize = 15.sp, lineHeight = 22.sp, color = BauColors.TextSecondary,
    )
}
