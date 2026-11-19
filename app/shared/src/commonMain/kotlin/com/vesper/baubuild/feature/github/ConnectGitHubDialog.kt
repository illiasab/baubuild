package com.vesper.baubuild.feature.github

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.DisplayFamily
import com.vesper.baubuild.feature.review.portal.PortalButton
import com.vesper.baubuild.feature.review.portal.PortalButtonStyle
import com.vesper.baubuild.feature.review.portal.PortalTokens

private val DialogWidth = 520.dp
private val DialogMaxHeight = 760.dp

/**
 * Desktop (Web portal) Connect GitHub: the shared body in a centered ~520dp card. Enter connects, Esc / backdrop /
 * Close dismiss (and clear the typed token). Opened from the portal Profile "PROFESSIONAL" card's GitHub row.
 */
@Composable
fun ConnectGitHubDialog(onDismiss: () -> Unit) {
    ConnectGitHubDialogContent(rememberConnectGitHubViewModel("portal"), onDismiss = onDismiss)
}

/** [onConnected] non-null = prompt mode (continues the caller's flow as soon as an account is connected). */
@Composable
internal fun ConnectGitHubDialogContent(
    vm: ConnectGitHubViewModel,
    onDismiss: () -> Unit,
    why: String = GitHubWhy,
    onConnected: (() -> Unit)? = null,
    repoFullName: String? = null,
    dismissLabel: String = if (onConnected != null) "Not now" else "Cancel",
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val dismiss = { vm.reset(); onDismiss() }
    val focus = remember { FocusRequester() }
    val currentOnConnected by rememberUpdatedState(onConnected)
    LaunchedEffect(state.isConnected) {
        if (state.isConnected) currentOnConnected?.let { vm.reset(); it() }
        else runCatching { focus.requestFocus() }
    }

    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = !state.connecting, usePlatformDefaultWidth = false),
    ) {
        val shape = RoundedCornerShape(PortalTokens.CardRadius)
        Column(
            Modifier
                .padding(24.dp)
                .widthIn(max = DialogWidth).fillMaxWidth()
                .heightIn(max = DialogMaxHeight)
                .clip(shape)
                .background(BauColors.Surface)
                .border(1.dp, PortalTokens.CardBorder, shape)
                .onPreviewKeyEvent { e ->
                    when {
                        e.key == Key.Escape -> { if (e.type == KeyEventType.KeyDown && !state.showDisconnectConfirm) dismiss(); true }
                        (e.key == Key.Enter || e.key == Key.NumPadEnter) && !state.isConnected -> {
                            if (e.type == KeyEventType.KeyDown) vm.connect()
                            true
                        }
                        else -> false
                    }
                },
        ) {
            Text(
                "Connect GitHub",
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp).semantics { heading() },
                fontFamily = DisplayFamily, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = BauColors.Ink,
            )
            HorizontalDivider(color = PortalTokens.Divider, thickness = 1.dp)
            ConnectGitHubBody(
                state, vm,
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(24.dp),
                why = why,
                tokenFocus = focus,
                repoFullName = repoFullName,
            )
            HorizontalDivider(color = PortalTokens.Divider, thickness = 1.dp)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.isConnected) {
                    PortalButton("Done", onClick = dismiss, style = PortalButtonStyle.Primary)
                } else {
                    PortalButton(dismissLabel, onClick = dismiss, enabled = !state.connecting)
                    PortalButton(
                        if (state.connecting) "Connecting…" else "Connect",
                        onClick = vm::connect, style = PortalButtonStyle.Primary, enabled = state.canConnect,
                    )
                }
            }
        }
    }
}
