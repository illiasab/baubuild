package com.vesper.baubuild.feature.github

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.BauPrimaryButton
import com.vesper.baubuild.designsystem.BauSpacing
import com.vesper.baubuild.designsystem.tabBarContentPadding

/**
 * Mobile "Connect GitHub" — pushed inside a tab (Profile → GitHub row, Edit Profile → Manage). Owns its back handling;
 * closing clears the typed token. "Connect" is pinned 24dp above the tab bar (or above the keyboard while typing:
 * `tabBarContentPadding` takes the higher of the two, so no separate imePadding).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ConnectGitHubScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val vm = rememberConnectGitHubViewModel("screen")
    val state by vm.state.collectAsStateWithLifecycle()
    val close = { vm.reset(); onClose() }
    BackHandler { close() }

    Column(
        modifier.fillMaxSize().background(BauColors.Paper)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = BauSpacing.screen, end = BauSpacing.screen, top = 12.dp, bottom = BauSpacing.lg)
                .then(if (state.isConnected) Modifier.tabBarContentPadding() else Modifier),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            BackButton(onClick = close)
            Text(
                "Connect GitHub", modifier = Modifier.semantics { heading() },
                fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.68).sp, color = BauColors.Ink,
            )
            ConnectGitHubBody(state, vm)
        }
        if (!state.isConnected) {
            Column(
                Modifier.fillMaxWidth().background(BauColors.Paper)
                    .padding(start = BauSpacing.screen, end = BauSpacing.screen, top = BauSpacing.md)
                    .tabBarContentPadding(),
            ) {
                BauPrimaryButton(
                    "Connect", onClick = vm::connect, enabled = state.canConnect, loading = state.connecting,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                )
            }
        }
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.55f))
            .border(1.dp, Color.White.copy(alpha = 0.85f), CircleShape)
            .clickable(onClickLabel = "Back", role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Back" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val s = size.width / 20f
            val w = 2.2f * s
            drawLine(BauColors.Ink, Offset(12.5f * s, 4f * s), Offset(6.5f * s, 10f * s), w, StrokeCap.Round)
            drawLine(BauColors.Ink, Offset(6.5f * s, 10f * s), Offset(12.5f * s, 16f * s), w, StrokeCap.Round)
        }
    }
}
