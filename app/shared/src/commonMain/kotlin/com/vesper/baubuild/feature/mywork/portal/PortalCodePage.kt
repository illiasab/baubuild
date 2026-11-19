package com.vesper.baubuild.feature.mywork.portal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.data.github.RepoEntryType
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.BauPrimaryButton
import com.vesper.baubuild.designsystem.MonoFamily
import com.vesper.baubuild.di.AppGraph
import com.vesper.baubuild.feature.code.ActiveFileBody
import com.vesper.baubuild.feature.code.CodeLabelStyle
import com.vesper.baubuild.feature.code.CodeSessionViewModel
import com.vesper.baubuild.feature.code.CodeUiState
import com.vesper.baubuild.feature.code.CommitBanner
import com.vesper.baubuild.feature.code.CommitChanges
import com.vesper.baubuild.feature.code.CommitMessageField
import com.vesper.baubuild.feature.code.DiscardChangesDialog
import com.vesper.baubuild.feature.code.FileTabs
import com.vesper.baubuild.feature.code.FileTree
import com.vesper.baubuild.feature.code.InlineError
import com.vesper.baubuild.feature.code.PullRequestSection
import com.vesper.baubuild.feature.code.SetupFailed
import com.vesper.baubuild.feature.code.editor.LanguageSpec
import com.vesper.baubuild.feature.github.ConnectGitHubPrompt

private val TreeWidth = 260.dp
private val SideWidth = 340.dp

/**
 * Desktop code workspace (IDE-like) for one task, inside My Work › project. Left: file tree. Center: tabs + editor
 * (or diff). Right: changes with include/exclude, commit box, commits/PR, and the task's acceptance criteria.
 * ⌘/Ctrl+S marks the file saved locally (no commit); ⌘/Ctrl+Enter commits. Esc stays in the editor.
 *
 * Uses the destination's ViewModel store (the project workspace), so edits survive leaving and re-entering; they
 * also live in [com.vesper.baubuild.feature.code.CodeSessionStore] for the whole app session.
 */
@Composable
internal fun PortalCodePage(
    projectId: String,
    taskId: String,
    ownerHint: String?,
    onBackToMyWork: () -> Unit,
    onBackToProject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = viewModel(key = "code/$projectId/$taskId") {
        CodeSessionViewModel(AppGraph.current.workRepository, AppGraph.current.gitHubRepository, projectId, taskId)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val openUrl: (String) -> Unit = { runCatching { uriHandler.openUri(it) } }

    Column(
        modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                val mod = e.isMetaPressed || e.isCtrlPressed
                when {
                    // Keep Esc inside the code page (it would otherwise close the whole project).
                    e.key == Key.Escape -> true
                    mod && e.key == Key.S -> { if (e.type == KeyEventType.KeyDown) vm.markSaved(); true }
                    mod && (e.key == Key.Enter || e.key == Key.NumPadEnter) -> { if (e.type == KeyEventType.KeyDown) vm.commit(); true }
                    else -> false
                }
            },
    ) {
        PortalPageTitle("Code")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PortalLink("My Work", onClick = onBackToMyWork, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
            Text("/", style = TextStyle(fontSize = 14.sp), color = BauColors.TextSecondary)
            PortalLink(
                state.context?.project?.owner ?: ownerHint ?: "Project",
                onClick = onBackToProject,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            )
            Text("/", style = TextStyle(fontSize = 14.sp), color = BauColors.TextSecondary)
            Text(
                state.context?.task?.title ?: "Task",
                modifier = Modifier.weight(1f, fill = false),
                style = TextStyle(fontSize = 14.sp),
                color = BauColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            state.context?.branch?.name?.let { BranchChip(it) }
        }
        Spacer(Modifier.height(12.dp))

        when (val setup = state.setup) {
            LoadState.Loading -> Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(28.dp), color = BauColors.Ink, strokeWidth = 2.5.dp)
                Spacer(Modifier.height(12.dp))
                Text(state.setupStep ?: "Opening the code workspace…", style = TextStyle(fontFamily = MonoFamily, fontSize = 13.sp), color = BauColors.TextSecondary)
            }
            is LoadState.Failed -> SetupFailed(setup.message, state.needsGitHub, onRetry = vm::start, modifier = Modifier.weight(1f).fillMaxWidth())
            is LoadState.Content -> Row(
                Modifier.weight(1f).fillMaxWidth().padding(start = 32.dp, end = 32.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TreeColumn(state, vm, Modifier.width(TreeWidth).fillMaxHeight())
                EditorColumn(state, vm, openUrl, Modifier.weight(1f).fillMaxHeight())
                SideColumn(state, vm, openUrl, Modifier.width(SideWidth).fillMaxHeight())
            }
        }
    }

    state.pendingDiscard?.let { DiscardChangesDialog(it, vm::confirmDiscard, vm::cancelDiscard) }
    if (state.needsGitHub) ConnectGitHubPrompt(onConnected = vm::onGitHubConnected, onDismiss = vm::dismissGitHubPrompt)
}

@Composable
private fun BranchChip(name: String) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier.clip(shape).background(BauColors.Surface).border(1.dp, PortalColors.ControlBorder, shape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Branch $name" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("⎇", style = TextStyle(fontSize = 13.sp), color = BauColors.TextSecondary)
        Text(name, style = TextStyle(fontFamily = MonoFamily, fontSize = 12.sp), color = BauColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TreeColumn(state: CodeUiState, vm: CodeSessionViewModel, modifier: Modifier) {
    Column(modifier.portalCard()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("FILES", modifier = Modifier.semantics { heading() }, style = PortalText.SectionLabel, color = BauColors.TextSecondary)
            state.context?.repoFullName?.let {
                Text(it, style = TextStyle(fontFamily = MonoFamily, fontSize = 12.sp), color = BauColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        HorizontalDivider(thickness = 1.dp, color = PortalColors.RowLine)
        when (val tree = state.tree) {
            LoadState.Loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), color = BauColors.Ink, strokeWidth = 2.dp)
            }
            is LoadState.Failed -> Column(Modifier.padding(16.dp)) {
                InlineError(tree.message)
                PortalLink("Try again", onClick = vm::loadTree)
            }
            is LoadState.Content -> FileTree(
                rows = remember(state.tree, state.expandedDirs) { state.visibleTree },
                expanded = state.expandedDirs,
                activePath = state.activePath,
                changedPaths = state.changedFiles.map { it.path }.toSet(),
                onOpen = { e -> if (e.type == RepoEntryType.Directory) vm.toggleDir(e.path) else vm.openFile(e.path) },
                modifier = Modifier.fillMaxWidth().weight(1f),
                rowHeight = 32.dp,
            )
        }
    }
}

@Composable
private fun EditorColumn(state: CodeUiState, vm: CodeSessionViewModel, openUrl: (String) -> Unit, modifier: Modifier) {
    Column(modifier.portalCard()) {
        Row(
            Modifier.fillMaxWidth().background(BauColors.Paper).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileTabs(state.session, vm::selectTab, vm::closeTab, Modifier.weight(1f), compact = true)
            val active = state.activeFile
            if (active?.isChanged == true) {
                ToolbarButton(if (active.path in state.diffPaths) "Edit" else "Diff", onClick = { vm.toggleDiff(active.path) })
                ToolbarButton("Discard", onClick = { vm.requestDiscard(active.path) }, danger = true)
            }
        }
        HorizontalDivider(thickness = 1.dp, color = PortalColors.RowLine)
        state.lastCommit?.let { commit ->
            CommitBanner(
                commit,
                onView = { openUrl(commit.url) },
                onDismiss = vm::dismissLastCommit,
                modifier = Modifier.fillMaxWidth().padding(8.dp).heightIn(min = 40.dp),
            )
        }
        ActiveFileBody(
            state = state,
            onEdit = vm::edit,
            onRetry = vm::retryFile,
            emptyHint = "Pick a file on the left to start coding.",
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        HorizontalDivider(thickness = 1.dp, color = PortalColors.RowLine)
        StatusBar(state)
    }
}

@Composable
private fun StatusBar(state: CodeUiState) {
    val file = state.activeFile
    Row(
        Modifier.fillMaxWidth().background(BauColors.Paper).padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val style = TextStyle(fontFamily = MonoFamily, fontSize = 11.sp)
        if (file != null) {
            Text(LanguageSpec.forPath(file.path).name, style = style, color = BauColors.TextSecondary)
            Text("${file.text.count { it == '\n' } + 1} lines", style = style, color = BauColors.TextSecondary)
            Text(
                when {
                    file.isUnsaved -> "● Unsaved"
                    file.isChanged -> "Saved locally · not committed"
                    else -> "No changes"
                },
                style = style,
                color = if (file.isUnsaved) BauColors.Warning else BauColors.TextSecondary,
            )
        }
        Spacer(Modifier.weight(1f))
        Text("⌘S save · ⌘↵ commit", style = style, color = BauColors.TextTertiary)
    }
}

@Composable
private fun ToolbarButton(text: String, onClick: () -> Unit, danger: Boolean = false) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .padding(start = 6.dp)
            .heightIn(min = 32.dp)
            .clip(shape)
            .background(BauColors.Surface)
            .border(1.dp, PortalColors.ControlBorder, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .pointerHoverIconHand()
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = if (danger) BauColors.Danger else BauColors.Ink)
    }
}

@Composable
private fun SideColumn(state: CodeUiState, vm: CodeSessionViewModel, openUrl: (String) -> Unit, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.fillMaxWidth().portalCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CommitChanges(state, vm, onOpen = vm::openFile)
            CommitMessageField(state.commitMessage, vm::setCommitMessage, enabled = !state.isCommitting)
            state.commitError?.let { InlineError(it) }
            BauPrimaryButton(
                text = "Commit & Push",
                onClick = vm::commit,
                enabled = state.canCommit,
                loading = state.isCommitting,
                modifier = Modifier.fillMaxWidth().pointerHoverIconHand(),
            )
            Text("⌘/Ctrl + Enter", style = TextStyle(fontFamily = MonoFamily, fontSize = 11.sp), color = BauColors.TextTertiary)
        }
        if (state.session.commits.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().portalCard().padding(16.dp)) {
                PullRequestSection(state, vm::openPullRequest, openUrl)
            }
        }
        val criteria = state.context?.task?.acceptanceCriteria.orEmpty()
        if (criteria.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().portalCard().padding(vertical = 12.dp)) {
                val done = state.session.checkedCriteria.count { it in criteria.indices }
                Text(
                    "DONE WHEN · $done OF ${criteria.size}",
                    modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
                    style = CodeLabelStyle,
                    color = BauColors.TextSecondary,
                )
                criteria.forEachIndexed { i, c ->
                    val checked = i in state.session.checkedCriteria
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp)
                            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { vm.toggleCriterion(i) })
                            .pointerHoverIconHand()
                            .padding(end = 16.dp, start = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null,
                            modifier = Modifier.size(40.dp),
                            colors = CheckboxDefaults.colors(checkedColor = BauColors.Ink, uncheckedColor = BauColors.TextTertiary, checkmarkColor = BauColors.Yellow),
                        )
                        Text(
                            c,
                            style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, textDecoration = if (checked) TextDecoration.LineThrough else null),
                            color = if (checked) BauColors.TextSecondary else BauColors.Ink,
                        )
                    }
                }
            }
        }
        Text(
            "Commits go to your task branch only. Mark the task as done from its panel to open a pull request.",
            style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
            color = BauColors.TextSecondary,
        )
    }
}

private fun Modifier.pointerHoverIconHand(): Modifier = pointerHoverIcon(PointerIcon.Hand)
