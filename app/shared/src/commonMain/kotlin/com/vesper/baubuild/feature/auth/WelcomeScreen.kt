package com.vesper.baubuild.feature.auth

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
    onLogIn: () -> Unit,
    onSignUp: () -> Unit,
    onForgotPassword: () -> Unit,
    onCancel: () -> Unit,
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
                Actions(state, onLogIn, onSignUp, onForgotPassword, onCancel)
            }
        }
    }
}

@Composable
private fun Hero(modifier: Modifier) {
    Box(modifier) {
        // Sea-wave bottom edge.
        Box(Modifier.matchParentSize().animatedWaveBackground(BauColors.Ink))
        BrandBlocksAnimation(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 8.dp, bottom = 42.dp)
                .heightIn(max = 320.dp)
                .fillMaxHeight(0.74f)
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
    onLogIn: () -> Unit,
    onSignUp: () -> Unit,
    onForgotPassword: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        (state.status as? AuthStatus.Error)?.let { AuthInlineError(it.message) }
        // "Sign Up" and "Log In" only navigate; Microsoft sign-in starts from their "Continue with Microsoft".
        // No role picker: student vs staff is detected from the email domain (AccountPolicy).
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            AuthPrimaryButton("Sign Up", onClick = onSignUp, loading = state.isSigningIn, modifier = Modifier.fillMaxWidth())
            if (!state.isSigningIn) AuthTextAction(lead = null, action = "Forgot password?", onClick = onForgotPassword)
        }
        if (state.isSigningIn) {
            Text("Finish signing in with Microsoft…", fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.TextSecondary)
            AuthTextAction(lead = null, action = "Cancel", onClick = onCancel)
        }
        if (!state.isSigningIn) {
            AuthTextAction(lead = "Already a member?", action = "Log In", onClick = onLogIn)
        }
    }
}
