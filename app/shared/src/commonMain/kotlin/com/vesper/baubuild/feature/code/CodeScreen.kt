package com.vesper.baubuild.feature.code

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.BauPrimaryButton
import com.vesper.baubuild.designsystem.BauSecondaryButton
import com.vesper.baubuild.designsystem.BauSpacing
import com.vesper.baubuild.designsystem.BauTextLink
import com.vesper.baubuild.designsystem.MonoFamily
import com.vesper.baubuild.designsystem.aboveTabBar
import com.vesper.baubuild.designsystem.bottomContentPadding
import com.vesper.baubuild.designsystem.tabBarContentPadding
import com.vesper.baubuild.feature.code.editor.CodeColors
import com.vesper.baubuild.feature.code.editor.CodeEditorState
import com.vesper.baubuild.feature.code.editor.EditorEditing
import com.vesper.baubuild.feature.github.ConnectGitHubPrompt
import com.vesper.baubuild.feature.mywork.components.BackCircleButton

/** Keys of the accessory row above the keyboard. "⇥" inserts two spaces. */
private val SymbolKeys = listOf("⇥", "{", "}", "(", ")", "[", "]", ";", ":", "=", "\"", "'", "<", ">", "/", ".", ",", "_", "!", "&", "|")

/**
 * Mobile code workspace, pushed on the My Work back stack.
 *
 * Presented full-screen inside the tab (the tab bar stays): the editor ends 24dp above the tab bar via
 * [bottomContentPadding]; while typing, the keyboard replaces that gap and the symbol row sits right on top of it.
 * Files open from a left drawer; Commit (with the changed-file count) opens a bottom sheet.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Suppress("DEPRECATION") // ui-backhandler BackHandler, same as the other features.
@Composable
fun CodeScreen(viewModel: CodeSessionViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    var editorFocused by remember { mutableStateOf(false) }
    var editorState by remember { mutableStateOf<CodeEditorState?>(null) }
    val ready = state.setup is LoadState.Content
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0

    // First visit: nothing open yet → show the files.
    LaunchedEffect(ready) { if (ready && state.session.tabs.isEmpty()) drawerOpen = true }
    BackHandler(enabled = drawerOpen) { drawerOpen = false }

    Box(modifier.fillMaxSize().background(BauColors.Paper)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            CodeTopBar(
                state = state,
                onBack = onBack,
                onFiles = { drawerOpen = true },
                onCommit = viewModel::openCommitSheet,
            )
            when (val setup = state.setup) {
                LoadState.Loading -> SetupProgress(state.setupStep, Modifier.weight(1f).fillMaxWidth())
                is LoadState.Failed -> SetupFailed(
                    message = setup.message,
                    needsGitHub = state.needsGitHub,
                    onRetry = viewModel::start,
                    modifier = Modifier.weight(1f).fillMaxWidth().tabBarContentPadding(),
                )
                is LoadState.Content -> {
                    if (state.session.tabs.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = BauSpacing.md, end = BauSpacing.sm, bottom = BauSpacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FileTabs(state.session, viewModel::selectTab, viewModel::closeTab, Modifier.weight(1f))
                            val active = state.activeFile
                            if (active?.isChanged == true) {
                                DiffToggle(showing = active.path in state.diffPaths, onToggle = { viewModel.toggleDiff(active.path) })
                            }
                        }
                    }
                    state.lastCommit?.let { commit ->
                        CommitBanner(
                            commit,
                            onView = { runCatching { uriHandler.openUri(commit.url) } },
                            onDismiss = viewModel::dismissLastCommit,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = BauSpacing.md, vertical = BauSpacing.xs).heightIn(min = 44.dp),
                        )
                    }
                    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    ActiveFileBody(
                        state = state,
                        onEdit = viewModel::edit,
                        onRetry = viewModel::retryFile,
                        emptyHint = "Open a file from Files to start coding.",
                        onEditorFocusChanged = { editorFocused = it },
                        onEditorState = { editorState = it },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = BauSpacing.sm)
                            .clip(shape)
                            .border(1.dp, BauColors.Line, shape),
                    )
                    val editor = editorState
                    if (editorFocused && editor != null) {
                        SymbolBar(
                            onKey = { key ->
                                editor.apply(
                                    { v -> if (key == "⇥") EditorEditing.insertTab(v) else EditorEditing.insert(v, key) },
                                    { text -> state.activePath?.let { viewModel.edit(it, text) } },
                                )
                            },
                            // With the keyboard up (it covers the tab bar) sit directly on it; otherwise 24dp above the tab bar.
                            modifier = Modifier.fillMaxWidth().padding(bottom = bottomContentPadding(extra = if (imeVisible) 0.dp else BauSpacing.aboveTabBar)),
                        )
                    } else {
                        Spacer(Modifier.height(bottomContentPadding()))
                    }
                }
            }
        }

        FileDrawer(
            open = drawerOpen,
            state = state,
            onClose = { drawerOpen = false },
            onOpen = { entry ->
                viewModel.openFile(entry.path)
                if (entry.type != com.vesper.baubuild.data.github.RepoEntryType.Directory) drawerOpen = false
            },
            onRetry = viewModel::loadTree,
        )
    }

    if (state.commitSheetVisible) CommitSheet(state, viewModel, onView = { runCatching { uriHandler.openUri(it) } })
    state.pendingDiscard?.let { DiscardChangesDialog(it, viewModel::confirmDiscard, viewModel::cancelDiscard) }
    if (state.needsGitHub) ConnectGitHubPrompt(onConnected = viewModel::onGitHubConnected, onDismiss = viewModel::dismissGitHubPrompt)
}

@Composable
private fun CodeTopBar(state: CodeUiState, onBack: () -> Unit, onFiles: () -> Unit, onCommit: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = BauSpacing.md).padding(top = BauSpacing.sm, bottom = BauSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BauSpacing.sm),
    ) {
        BackCircleButton(contentDescription = "Back to task", onClick = onBack)
        Column(Modifier.weight(1f)) {
            Text(
                state.activePath?.substringAfterLast('/') ?: state.context?.task?.title ?: "Code",
                modifier = Modifier.semantics { heading() },
                style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp),
                color = BauColors.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            state.context?.branch?.name?.let {
                Text(it, style = TextStyle(fontFamily = MonoFamily, fontSize = 11.sp), color = BauColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (state.setup is LoadState.Content) {
            PillButton("Files", onClick = onFiles, dark = false)
            PillButton(
                "Commit",
                onClick = onCommit,
                dark = true,
                badge = state.changedCount.takeIf { it > 0 },
                description = "Commit, ${state.changedCount} changed files",
            )
        }
    }
}

@Composable
private fun PillButton(text: String, onClick: () -> Unit, dark: Boolean, badge: Int? = null, description: String = text) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(if (dark) BauColors.Ink else BauColors.Surface)
            .border(1.dp, if (dark) BauColors.Ink else BauColors.Line, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = if (dark) Color.White else BauColors.Ink)
        if (badge != null) {
            Box(Modifier.defaultMinSize(20.dp, 20.dp).background(BauColors.Yellow, CircleShape).padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
                Text(badge.toString(), style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold), color = BauColors.Ink)
            }
        }
    }
}

@Composable
private fun DiffToggle(showing: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .padding(start = 4.dp)
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(if (showing) BauColors.Ink else BauColors.Surface)
            .border(1.dp, if (showing) BauColors.Ink else BauColors.Line, shape)
            .clickable(role = Role.Switch, onClick = onToggle)
            .semantics { stateDescription = if (showing) "Showing diff" else "Showing code" }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("Diff", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = if (showing) Color.White else BauColors.Ink)
    }
}

@Composable
private fun SymbolBar(onKey: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(BauColors.Paper)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = BauSpacing.sm, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SymbolKeys.forEach { key ->
            val shape = RoundedCornerShape(8.dp)
            Box(
                Modifier
                    .size(width = 44.dp, height = 44.dp)
                    .clip(shape)
                    .background(BauColors.Surface)
                    .border(1.dp, BauColors.Line, shape)
                    .clickable(role = Role.Button, onClickLabel = if (key == "⇥") "Insert indent" else "Insert $key") { onKey(key) },
                contentAlignment = Alignment.Center,
            ) {
                Text(key, style = TextStyle(fontFamily = MonoFamily, fontSize = 17.sp, fontWeight = FontWeight.SemiBold), color = BauColors.Ink)
            }
        }
    }
}

@Composable
private fun SetupProgress(step: String?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(Modifier.size(28.dp), color = BauColors.Ink, strokeWidth = 2.5.dp)
        Spacer(Modifier.height(BauSpacing.md))
        Text(
            step ?: "Opening the code workspace…",
            modifier = Modifier.padding(horizontal = BauSpacing.screen),
            style = TextStyle(fontFamily = if (step != null) MonoFamily else null, fontSize = 13.sp),
            color = BauColors.TextSecondary,
        )
    }
}

@Composable
internal fun SetupFailed(message: String, needsGitHub: Boolean, onRetry: () -> Unit, modifier: Modifier) {
    Column(modifier.padding(BauSpacing.screen), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, style = TextStyle(fontSize = 15.sp, lineHeight = 21.sp), color = BauColors.TextSecondary)
        Spacer(Modifier.height(BauSpacing.md))
        if (!needsGitHub) BauSecondaryButton("Try Again", onClick = onRetry)
    }
}

@Composable
private fun FileDrawer(
    open: Boolean,
    state: CodeUiState,
    onClose: () -> Unit,
    onOpen: (com.vesper.baubuild.data.github.RepoEntry) -> Unit,
    onRetry: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(BauColors.Ink.copy(alpha = 0.32f))
                    .clickable(interactionSource = null, indication = null, onClickLabel = "Close files", onClick = onClose),
            )
        }
        AnimatedVisibility(
            open,
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut(),
        ) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.84f)
                    .widthIn(max = 360.dp)
                    .background(BauColors.Surface)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Start)),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = BauSpacing.lg, end = BauSpacing.sm, top = BauSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Files", modifier = Modifier.semantics { heading() }, style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold), color = BauColors.Ink)
                        state.context?.repoFullName?.let {
                            Text(it, style = TextStyle(fontFamily = MonoFamily, fontSize = 12.sp), color = BauColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    BauTextLink("Done", onClick = onClose)
                }
                HorizontalDivider(Modifier.padding(top = BauSpacing.sm), color = BauColors.Line)
                when (val tree = state.tree) {
                    LoadState.Loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = BauColors.Ink, strokeWidth = 2.dp)
                    }
                    is LoadState.Failed -> Column(Modifier.fillMaxWidth().weight(1f).padding(BauSpacing.lg)) {
                        InlineError(tree.message)
                        BauTextLink("Try again", onClick = onRetry)
                    }
                    is LoadState.Content -> FileTree(
                        rows = remember(state.tree, state.expandedDirs) { state.visibleTree },
                        expanded = state.expandedDirs,
                        activePath = state.activePath,
                        changedPaths = state.changedFiles.map { it.path }.toSet(),
                        onOpen = onOpen,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(top = BauSpacing.sm, bottom = bottomContentPadding()),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommitSheet(state: CodeUiState, viewModel: CodeSessionViewModel, onView: (String) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = viewModel::dismissCommitSheet, sheetState = sheetState, containerColor = BauColors.Paper) {
        Column(Modifier.fillMaxWidth()) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = BauSpacing.screen),
                verticalArrangement = Arrangement.spacedBy(BauSpacing.md),
            ) {
                Text("Commit & Push", modifier = Modifier.semantics { heading() }, style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold), color = BauColors.Ink)
                state.context?.branch?.name?.let {
                    Text("to $it", style = TextStyle(fontFamily = MonoFamily, fontSize = 12.sp), color = BauColors.TextSecondary)
                }
                CommitMessageField(state.commitMessage, viewModel::setCommitMessage, enabled = !state.isCommitting)
                CommitChanges(state, viewModel, onOpen = { path -> viewModel.openFile(path); viewModel.dismissCommitSheet() })
                PullRequestSection(state, viewModel::openPullRequest, onView)
            }
            Column(
                Modifier.fillMaxWidth().padding(start = BauSpacing.screen, end = BauSpacing.screen, top = BauSpacing.md, bottom = BauSpacing.xxl),
                verticalArrangement = Arrangement.spacedBy(BauSpacing.sm),
            ) {
                state.commitError?.let { InlineError(it, Modifier.fillMaxWidth()) }
                BauPrimaryButton(
                    text = if (state.filesToCommit.size <= 1) "Commit & Push" else "Commit & Push ${state.filesToCommit.size} files",
                    onClick = viewModel::commit,
                    enabled = state.canCommit,
                    loading = state.isCommitting,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
internal fun CommitMessageField(message: String, onChange: (String) -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = message,
        onValueChange = onChange,
        enabled = enabled,
        label = { Text("Commit message") },
        modifier = modifier.fillMaxWidth(),
        minLines = 2,
        maxLines = 5,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = BauColors.Ink, unfocusedBorderColor = BauColors.Line,
            focusedContainerColor = BauColors.Surface, unfocusedContainerColor = BauColors.Surface,
            focusedLabelColor = BauColors.Ink, cursorColor = BauColors.BlueDeep,
        ),
    )
}

/** "CHANGES · n" with include/exclude checkboxes, or an empty note. */
@Composable
internal fun CommitChanges(state: CodeUiState, viewModel: CodeSessionViewModel, onOpen: ((String) -> Unit)?) {
    Column(Modifier.fillMaxWidth()) {
        Text("CHANGES · ${state.changedCount}", style = CodeLabelStyle, color = BauColors.TextSecondary)
        if (state.changedFiles.isEmpty()) {
            Text(
                if (state.session.commits.isEmpty()) "No changes yet. Edit a file and it shows up here." else "Everything is committed.",
                modifier = Modifier.padding(vertical = BauSpacing.sm),
                style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
                color = BauColors.TextSecondary,
            )
        }
        state.changedFiles.forEach { draft ->
            ChangedFileRow(
                draft = draft,
                included = draft.path !in state.excludedFromCommit,
                onToggle = { viewModel.toggleCommitFile(draft.path) },
                onOpen = onOpen?.let { { it(draft.path) } },
                onDiscard = { viewModel.requestDiscard(draft.path) },
            )
        }
    }
}

/** Commits made this session + Open Pull Request / "Pull request #N opened · View". */
@Composable
internal fun PullRequestSection(state: CodeUiState, onOpenPullRequest: () -> Unit, onView: (String) -> Unit) {
    val commits = state.session.commits
    if (commits.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("COMMITS THIS SESSION · ${commits.size}", style = CodeLabelStyle, color = BauColors.TextSecondary)
        commits.asReversed().forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(c.sha.take(7), style = TextStyle(fontFamily = MonoFamily, fontSize = 12.sp, fontWeight = FontWeight.Bold), color = BauColors.BlueDeep)
                Text(c.message.lineSequence().first(), modifier = Modifier.weight(1f), style = TextStyle(fontSize = 13.sp), color = BauColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (c.url.isNotBlank()) BauTextLink("View", onClick = { onView(c.url) }, fontSize = 13.sp, modifier = Modifier.handCursor())
            }
        }
        val pr = state.session.pullRequest
        when {
            pr != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (pr.number > 0) "Pull request #${pr.number} opened" else "Pull request opened",
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    color = CodeColors.AddedInk,
                )
                if (pr.url.isNotBlank()) {
                    Text(" · ", color = BauColors.TextSecondary)
                    BauTextLink("View", onClick = { onView(pr.url) }, fontSize = 14.sp, modifier = Modifier.handCursor())
                }
            }
            else -> {
                state.pullRequestError?.let { InlineError(it) }
                BauSecondaryButton(
                    if (state.isOpeningPullRequest) "Opening pull request…" else "Open Pull Request",
                    onClick = { if (state.canOpenPullRequest) onOpenPullRequest() },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}
