package com.vesper.baubuild.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.BauPill
import com.vesper.baubuild.designsystem.BauSpacing

/** Welcome / sign-in screen from the design ("iOS app" → Welcome). */
@Composable
internal fun WelcomeScreen(
    state: AuthUiState,
    onSignIn: () -> Unit,
    onCancel: () -> Unit,
    onSelectDemoAccount: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(BauColors.Paper), contentAlignment = Alignment.TopCenter) {
        // Phone-shaped column on wide desktop windows; full width on phones.
        BoxWithConstraints(Modifier.widthIn(max = 560.dp).fillMaxSize()) {
            val heroHeight = (maxHeight * 0.51f).coerceIn(300.dp, 470.dp)
            val viewportHeight = maxHeight
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = viewportHeight),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Hero(Modifier.fillMaxWidth().height(heroHeight))
                    Column(
                        Modifier.padding(horizontal = 28.dp).padding(top = 32.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "Build something real.",
                            modifier = Modifier.semantics { heading() },
                            fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.02).em, color = BauColors.Ink,
                        )
                        Text(
                            "Pick a BAU project, build it with an AI mentor, and get it approved by a dev lead.",
                            fontSize = 17.sp, lineHeight = 24.sp, color = BauColors.TextSecondary,
                        )
                    }
                }
                Actions(state, onSignIn, onCancel, onSelectDemoAccount)
            }
        }
    }
}

@Composable
private fun Hero(modifier: Modifier) {
    Box(modifier.background(BauColors.Ink, RoundedCornerShape(bottomStart = 44.dp, bottomEnd = 44.dp))) {
        BrandBlocksAnimation(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 8.dp, bottom = 16.dp)
                .heightIn(max = 320.dp)
                .fillMaxHeight(0.78f)
                .aspectRatio(280f / 400f),
        )
        Column(
            Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(start = 28.dp, top = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BauBuildMark(Modifier.size(44.dp))
            Text(
                "BAU BUILD",
                fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 0.08.em, color = Color.White,
            )
        }
    }
}

@Composable
private fun Actions(
    state: AuthUiState,
    onSignIn: () -> Unit,
    onCancel: () -> Unit,
    onSelectDemoAccount: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp)
            .padding(bottom = 32.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        (state.status as? AuthStatus.Error)?.let { error ->
            Text(
                error.message,
                modifier = Modifier.fillMaxWidth()
                    .background(BauColors.WarningSoft, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                fontSize = 14.sp, lineHeight = 19.sp, color = BauColors.WarningInk, textAlign = TextAlign.Center,
            )
        }
        SignUpButton(loading = state.isSigningIn, onClick = onSignIn)
        if (state.isSigningIn) {
            Text("Finish signing in with Microsoft…", fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.TextSecondary)
            TextAction(lead = null, action = "Cancel", onClick = onCancel)
        } else {
            Text("Free with your BAU email", fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.TextSecondary)
            TextAction(lead = "Already a member?", action = "Log In", onClick = onSignIn)
        }
        state.demo?.let { DemoAccountPicker(it, enabled = !state.isSigningIn, onSelect = onSelectDemoAccount) }
    }
}

/** The design's 58dp yellow pill with soft drop shadow. */
@Composable
private fun SignUpButton(loading: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(29.dp)
    Button(
        onClick = onClick,
        enabled = !loading,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .shadow(12.dp, shape, ambientColor = BauColors.Ink.copy(alpha = 0.16f), spotColor = BauColors.Ink.copy(alpha = 0.16f))
            .semantics { if (loading) contentDescription = "Signing in" },
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = BauColors.Yellow, contentColor = BauColors.Ink,
            disabledContainerColor = BauColors.YellowLight, disabledContentColor = BauColors.Ink,
        ),
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(22.dp), color = BauColors.Ink, strokeWidth = 2.dp)
        else Text("Sign Up", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em)
    }
}

@Composable
private fun TextAction(lead: String?, action: String, onClick: () -> Unit) {
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = BauSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (lead != null) Text(lead, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = BauColors.Ink)
        Text(action, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = BauColors.BlueDeep)
    }
}

/** Development only (fake sign-in): choose which Microsoft account the demo flow "returns". */
@Composable
private fun DemoAccountPicker(demo: DemoAccounts, enabled: Boolean, onSelect: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = BauSpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("DEMO SIGN-IN (no Microsoft config)", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.06.em, color = BauColors.TextTertiary)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            demo.emails.forEach { email ->
                BauPill(
                    text = demoLabel(email),
                    selected = email == demo.selected,
                    onClick = { if (enabled) onSelect(email) },
                    modifier = Modifier.semantics { contentDescription = "Demo account $email" },
                )
            }
        }
        Text(demo.selected, fontSize = 12.sp, color = BauColors.TextTertiary)
    }
}

private fun demoLabel(email: String): String = when {
    email.endsWith("@stu.bau.edu") -> "Student"
    email.endsWith("@bau.edu") -> "Staff"
    else -> "Other"
}
