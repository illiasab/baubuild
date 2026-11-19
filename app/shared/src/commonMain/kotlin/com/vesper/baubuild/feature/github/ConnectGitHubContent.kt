package com.vesper.baubuild.feature.github

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vesper.baubuild.data.github.GitHubAccount
import com.vesper.baubuild.data.github.GitHubOrgName
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.BauRadius
import com.vesper.baubuild.designsystem.BauTextLink
import com.vesper.baubuild.designsystem.MonoFamily
import com.vesper.baubuild.di.AppGraph

/** Where students create a fine-grained personal access token. */
const val GitHubNewTokenUrl = "https://github.com/settings/personal-access-tokens/new"

/** Recommended token lifetime; the app asks again when GitHub starts rejecting the expired token. */
internal const val GitHubTokenExpiryDays = 90

/**
 * [GitHubNewTokenUrl] pre-filled for the BAU BUILD org (name, resource owner, expiry, permissions). GitHub ignores
 * parameters it doesn't support, so the written steps stay the source of truth.
 */
fun gitHubNewTokenUrl(): String =
    "$GitHubNewTokenUrl?name=BAU+BUILD&target_name=$GitHubOrgName&expires_in=$GitHubTokenExpiryDays" +
        "&contents=write&pull_requests=write&metadata=read"

/** `https://github.com/<owner>/<repo>.git` for "Prefer your own editor?". */
fun gitHubCloneUrl(repoFullName: String): String = "https://github.com/${repoFullName.trim().trim('/')}.git"

internal const val GitHubWhy = "Code and commit to your project's repository right from BAU BUILD."
internal const val GitHubPrivacyNote =
    "Your token is kept in this device's Keychain / Keystore. It's sent only to GitHub and, over HTTPS, to " +
        "BAU BUILD so the project agent can check your pushes. Disconnect removes it from both."
internal const val GitHubOwnEditorNote =
    "Prefer your own editor? Clone the repo and push to your task branch — the agent checks every push either way."

/** One Connect GitHub ViewModel per [key] in the current ViewModel store ("screen", "prompt", "portal"…). */
@Composable
fun rememberConnectGitHubViewModel(key: String): ConnectGitHubViewModel =
    viewModel(key = "ConnectGitHub#$key") { ConnectGitHubViewModel(AppGraph.current.gitHubRepository, AppGraph.current.session) }

/**
 * Everything below the title, shared by the mobile screen, the mobile prompt sheet and the desktop dialog:
 * not connected → why + steps + token field + privacy note; connected → account card + Disconnect.
 * The primary "Connect" button is not included (callers pin it).
 */
@Composable
internal fun ConnectGitHubBody(
    state: ConnectGitHubUiState,
    vm: ConnectGitHubViewModel,
    modifier: Modifier = Modifier,
    why: String = GitHubWhy,
    tokenFocus: FocusRequester? = null,
    /** "owner/name" of the project the prompt was opened for; the guide names it. Null = generic wording. */
    repoFullName: String? = null,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(why, fontSize = 17.sp, lineHeight = 24.sp, color = BauColors.TextSecondary)
        val account = state.account
        if (account != null) {
            ConnectedAccountCard(account, state, onDisconnect = vm::requestDisconnect)
        } else {
            TokenSteps(repoFullName)
            TokenField(state, vm, tokenFocus)
            PrivacyNote()
            OwnEditorNote(repoFullName)
        }
    }
    if (state.showDisconnectConfirm) {
        DisconnectGitHubDialog(login = state.account?.login, onConfirm = vm::confirmDisconnect, onCancel = vm::cancelDisconnect)
    }
}

@Composable
private fun TokenSteps(repoFullName: String?) {
    val uriHandler = LocalUriHandler.current
    val shape = RoundedCornerShape(BauRadius.lg)
    val repo = repoFullName?.trim()?.takeIf { it.isNotEmpty() }
    Column(
        Modifier.fillMaxWidth().clip(shape).background(BauColors.Surface).border(1.dp, BauColors.Line, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Create a fine-grained token", fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold, color = BauColors.Ink)
        Step(1, "On GitHub open ", "Settings → Developer settings → Personal access tokens → Fine-grained tokens", " and choose Generate new token.")
        Step(2, "Name it “BAU BUILD”. Set ", "Resource owner: $GitHubOrgName", " (the BAU BUILD organization, not your personal account).")
        Step(
            3, "Repository access: ", "Only select repositories → ${repo ?: "your project's repository ($GitHubOrgName/…)"}",
            if (repo != null) "." else ". Add more repos later by editing the token.",
        )
        Step(4, "Under Repository permissions set ", "Contents: Read and write, Pull requests: Read and write, Metadata: Read-only", ".")
        Step(
            5, "Expiration: ", "$GitHubTokenExpiryDays days",
            " is a good choice. When it expires, BAU BUILD will ask you to connect again with a new token.",
        )
        Step(6, "Generate the token, copy it, and paste it below.", "", "")
        BauTextLink(
            "Open GitHub token settings",
            onClick = { runCatching { uriHandler.openUri(gitHubNewTokenUrl()) } },
            modifier = Modifier.semantics { contentDescription = "Open GitHub token settings in your browser" },
            color = BauColors.BlueDeep, fontSize = 15.sp,
        )
        PendingApprovalNote()
    }
}

/** Org-owned tokens may need an admin's approval before they can reach the repo. */
@Composable
private fun PendingApprovalNote() {
    val shape = RoundedCornerShape(BauRadius.md)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(BauColors.BlueSoft).padding(12.dp).semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Token says “pending approval”?", fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, color = BauColors.Ink)
        Text(
            "The $GitHubOrgName organization reviews new tokens. Paste it here anyway — you'll be connected, and the " +
                "repository opens up as soon as an org admin approves it (ask your project lead if it takes more than a day). " +
                "If GitHub rejected the request, create a new token and try again.",
            fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.TextSecondary,
        )
    }
}

@Composable
private fun OwnEditorNote(repoFullName: String?) {
    val repo = repoFullName?.trim()?.takeIf { it.isNotEmpty() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(GitHubOwnEditorNote, fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.TextSecondary)
        SelectionContainer {
            Text(
                if (repo != null) gitHubCloneUrl(repo) else "https://github.com/$GitHubOrgName/<project-repo>.git",
                fontFamily = MonoFamily, fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.Ink,
            )
        }
    }
}

@Composable
private fun Step(number: Int, before: String, bold: String, after: String) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(BauColors.BlueSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text(number.toString(), fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, color = BauColors.BlueDeep)
        }
        Text(
            buildAnnotatedString {
                append(before)
                if (bold.isNotEmpty()) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = BauColors.Ink)) { append(bold) }
                append(after)
            },
            modifier = Modifier.weight(1f),
            fontSize = 15.sp, lineHeight = 21.sp, color = BauColors.TextSecondary,
        )
    }
}

@Composable
private fun TokenField(state: ConnectGitHubUiState, vm: ConnectGitHubViewModel, focus: FocusRequester?) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Personal access token", fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = BauColors.Ink)
        OutlinedTextField(
            value = state.token,
            onValueChange = vm::onTokenChange,
            enabled = !state.connecting,
            modifier = Modifier.fillMaxWidth().then(if (focus != null) Modifier.focusRequester(focus) else Modifier),
            singleLine = true,
            isError = state.error != null,
            placeholder = { Text("github_pat_…", color = BauColors.TextTertiary, fontFamily = MonoFamily) },
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = MonoFamily, fontSize = 15.sp),
            visualTransformation = if (state.tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                BauTextLink(
                    if (state.tokenVisible) "Hide" else "Show",
                    onClick = vm::toggleTokenVisible,
                    modifier = Modifier.padding(end = 8.dp).semantics {
                        contentDescription = if (state.tokenVisible) "Hide token" else "Show token"
                    },
                    color = BauColors.BlueDeep, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                )
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password, imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { vm.connect() }),
            shape = RoundedCornerShape(BauRadius.md),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = BauColors.Blue, unfocusedBorderColor = BauColors.Line, disabledBorderColor = BauColors.Line,
                focusedContainerColor = BauColors.Surface, unfocusedContainerColor = BauColors.Surface,
                disabledContainerColor = BauColors.Surface, errorContainerColor = BauColors.Surface,
                focusedTextColor = BauColors.Ink, unfocusedTextColor = BauColors.Ink, disabledTextColor = BauColors.TextSecondary,
                cursorColor = BauColors.Blue, errorBorderColor = BauColors.Danger,
            ),
        )
        state.error?.let { Text(it, fontSize = 14.sp, lineHeight = 19.sp, color = BauColors.Danger) }
    }
}

@Composable
private fun PrivacyNote() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(BauColors.BlueDeep))
        Text(GitHubPrivacyNote, fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.TextSecondary)
    }
}

@Composable
private fun ConnectedAccountCard(account: GitHubAccount, state: ConnectGitHubUiState, onDisconnect: () -> Unit) {
    val shape = RoundedCornerShape(BauRadius.lg)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(BauColors.Surface).border(1.dp, BauColors.Line, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.semantics(mergeDescendants = true) { },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            GitHubAvatar(account, 52.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    state.connectedMessage.orEmpty(), fontSize = 17.sp, lineHeight = 22.sp,
                    fontWeight = FontWeight.Bold, color = BauColors.Ink,
                )
                account.name?.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 15.sp, lineHeight = 20.sp, color = BauColors.TextSecondary)
                }
            }
        }
        Text(
            "You can open tasks in the code workspace, commit and open pull requests.",
            fontSize = 14.sp, lineHeight = 19.sp, color = BauColors.TextSecondary,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BauTextLink(
                "Disconnect", onClick = onDisconnect, enabled = !state.disconnecting,
                color = BauColors.Danger, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
            if (state.disconnecting) CircularProgressIndicator(Modifier.size(16.dp), color = BauColors.Danger, strokeWidth = 2.dp)
        }
        state.backendLinkNote?.let { Text(it, fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.TextSecondary) }
        state.disconnectError?.let { Text(it, fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.Danger) }
    }
}

/** Round avatar with the login's first letter (remote GitHub avatars need an image loader; see UserAvatar TODO). */
@Composable
internal fun GitHubAvatar(account: GitHubAccount, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(BauColors.Ink).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            account.login.take(1).uppercase(), color = BauColors.Surface,
            fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.Bold, maxLines = 1,
        )
    }
}

@Composable
internal fun DisconnectGitHubDialog(login: String?, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = BauColors.Surface,
        title = { Text("Disconnect GitHub?", fontWeight = FontWeight.Bold, color = BauColors.Ink) },
        text = {
            Text(
                "The token${login?.let { " for @$it" }.orEmpty()} will be removed from this device. " +
                    "You’ll need to connect again to code and commit in BAU BUILD.",
                color = BauColors.TextSecondary,
            )
        },
        confirmButton = { BauTextLink("Disconnect", onClick = onConfirm, color = BauColors.Danger) },
        dismissButton = { BauTextLink("Cancel", onClick = onCancel, color = BauColors.Ink, fontWeight = FontWeight.Medium) },
    )
}
