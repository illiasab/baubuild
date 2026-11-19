package com.vesper.baubuild.feature.github

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.BauPrimaryButton
import com.vesper.baubuild.designsystem.BauSpacing
import com.vesper.baubuild.designsystem.BauTextLink
import com.vesper.baubuild.shell.usesPortalLayout

internal const val GitHubPromptWhy =
    "Connect GitHub to start this task. You’ll code and commit to your project's repository right from BAU BUILD."

/** Shown right after joining a project that has a repository. */
const val GitHubJoinPromptWhy =
    "Next step: connect GitHub so you can code in the app. You'll work on this project's repository right from BAU BUILD."

/**
 * Reusable "connect GitHub first" prompt (e.g. "Start Task" without GitHub). Show it while needed:
 *
 * ```
 * if (askGitHub) ConnectGitHubPrompt(onConnected = { askGitHub = false; startTask() }, onDismiss = { askGitHub = false })
 * ```
 *
 * - Mobile (iOS / Android): modal bottom sheet. Desktop ([usesPortalLayout]): centered portal dialog
 *   (same as [ConnectGitHubDialog]; Enter connects, Esc closes). One API for both.
 * - [onConnected] runs once an account is connected — right after a successful Connect, or immediately if GitHub
 *   was already connected when the prompt was shown. The caller should then hide the prompt.
 * - [repoFullName]: the project's repo ("owner/name"), named in the token guide (repository access + clone URL).
 * - [why] / [dismissLabel]: e.g. [GitHubJoinPromptWhy] / "Later" for the after-join prompt.
 * - [onDismiss]: user closed it without connecting ("Not now", swipe down, backdrop, Esc). The typed token is cleared.
 */
@Composable
fun ConnectGitHubPrompt(
    onConnected: () -> Unit,
    onDismiss: () -> Unit,
    repoFullName: String? = null,
    why: String = GitHubPromptWhy,
    dismissLabel: String = "Not now",
) {
    val vm = rememberConnectGitHubViewModel("prompt")
    DisposableEffect(vm) { onDispose { vm.reset() } }
    if (usesPortalLayout) {
        ConnectGitHubDialogContent(
            vm, onDismiss = onDismiss, why = why, onConnected = onConnected,
            repoFullName = repoFullName, dismissLabel = dismissLabel,
        )
    } else {
        ConnectGitHubSheet(vm, onConnected = onConnected, onDismiss = onDismiss, repoFullName = repoFullName, why = why, dismissLabel = dismissLabel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectGitHubSheet(
    vm: ConnectGitHubViewModel,
    onConnected: () -> Unit,
    onDismiss: () -> Unit,
    repoFullName: String?,
    why: String,
    dismissLabel: String,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val currentOnConnected by rememberUpdatedState(onConnected)
    LaunchedEffect(state.isConnected) { if (state.isConnected) { vm.reset(); currentOnConnected() } }
    if (state.isConnected) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dismiss = { vm.reset(); onDismiss() }
    // The sheet's default content insets include the keyboard (safeDrawing), so the pinned Connect stays visible.
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheetState, containerColor = BauColors.Paper) {
        Column(Modifier.fillMaxWidth()) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .padding(start = BauSpacing.screen, end = BauSpacing.screen, bottom = BauSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    "Connect GitHub", modifier = Modifier.semantics { heading() },
                    fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, color = BauColors.Ink,
                )
                ConnectGitHubBody(state, vm, why = why, repoFullName = repoFullName)
            }
            Column(
                Modifier.fillMaxWidth().padding(start = BauSpacing.screen, end = BauSpacing.screen, top = BauSpacing.sm, bottom = BauSpacing.xxl),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                BauPrimaryButton(
                    "Connect", onClick = vm::connect, enabled = state.canConnect, loading = state.connecting,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                )
                BauTextLink(
                    dismissLabel, onClick = dismiss, enabled = !state.connecting,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    color = BauColors.TextSecondary, fontSize = 16.sp, fontWeight = FontWeight.Normal,
                )
            }
        }
    }
}
