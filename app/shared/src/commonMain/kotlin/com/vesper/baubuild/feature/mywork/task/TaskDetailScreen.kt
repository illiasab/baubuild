package com.vesper.baubuild.feature.mywork.task

import com.vesper.baubuild.designsystem.tabBarContentPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import com.vesper.baubuild.designsystem.BauTextLink
import com.vesper.baubuild.designsystem.MonoFamily
import com.vesper.baubuild.feature.github.ConnectGitHubPrompt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vesper.baubuild.core.model.TaskPriority
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.BauPrimaryButton
import com.vesper.baubuild.designsystem.BauSecondaryButton
import com.vesper.baubuild.designsystem.BauSpacing
import com.vesper.baubuild.designsystem.LoadStateContent
import com.vesper.baubuild.feature.mywork.components.BackCircleButton
import com.vesper.baubuild.feature.mywork.components.GroupedRow
import com.vesper.baubuild.feature.mywork.components.GroupedSection
import com.vesper.baubuild.feature.mywork.components.WorkChip
import com.vesper.baubuild.feature.mywork.components.WorkChipColors
import com.vesper.baubuild.feature.mywork.workspace.TaskStatusChip
import com.vesper.baubuild.feature.mywork.workspace.label

/**
 * Mobile task detail (pushed from the workspace's "My Tasks" list). Scrollable body + action area pinned
 * above the floating tab bar (system bottom inset + 24dp, Paper background so content never shows under it).
 */
@Composable
internal fun TaskDetailScreen(
    viewModel: TaskDetailViewModel,
    onBack: () -> Unit,
    onAskMentor: () -> Unit,
    onOpenCode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TaskCodeEffects(state, viewModel, onOpenCode)
    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = BauSpacing.screen).padding(top = BauSpacing.sm).height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackCircleButton(contentDescription = "Back to project", onClick = onBack)
        }
        LoadStateContent(state.detail, onRetry = viewModel::refresh, modifier = Modifier.fillMaxSize()) { detail ->
            Column(Modifier.fillMaxSize()) {
                TaskDetailContent(
                    detail = detail,
                    checkedCriteria = state.checkedCriteria,
                    onToggleCriterion = viewModel::toggleCriterion,
                    branchName = state.branchName,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = BauSpacing.screen)
                        .padding(top = BauSpacing.lg, bottom = BauSpacing.lg),
                )
                TaskDetailActions(
                    state = state,
                    onAdvance = viewModel::advance,
                    onAskMentor = onAskMentor,
                    onOpenCode = onOpenCode,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BauColors.Paper)
                        .padding(horizontal = BauSpacing.screen)
                        .padding(top = BauSpacing.md)
                        .tabBarContentPadding(),
                )
            }
        }
    }
}

/**
 * The task's body: breadcrumb, title, chips, deadline, description, criteria checklist, resources.
 * Doesn't scroll or pad for insets itself, so the desktop portal can place it in a side panel or dialog.
 */
@Composable
internal fun TaskDetailContent(
    detail: TaskDetail,
    checkedCriteria: Set<Int>,
    onToggleCriterion: (Int) -> Unit,
    modifier: Modifier = Modifier,
    branchName: String? = null,
) {
    val task = detail.task
    val project = detail.project
    Column(modifier, verticalArrangement = Arrangement.spacedBy(BauSpacing.xl)) {
        Column(verticalArrangement = Arrangement.spacedBy(BauSpacing.sm)) {
            Text(
                "${project.owner} · ${project.difficulty.label}",
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp),
                color = BauColors.BlueDeep,
            )
            Text(
                task.title,
                modifier = Modifier.semantics { heading() },
                style = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp, letterSpacing = (-0.02).em),
                color = BauColors.Ink,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(BauSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                TaskStatusChip(task.status)
                TaskPriorityChip(task.priority)
            }
            if (branchName != null) BranchLabel(branchName)
        }

        DeadlineBlock(deadlineOf(task))

        if (task.description.isNotBlank()) {
            DetailSection("What to do") {
                Text(
                    task.description,
                    modifier = Modifier.padding(BauSpacing.lg),
                    style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
                    color = BauColors.Ink,
                )
            }
        }

        if (task.acceptanceCriteria.isNotEmpty()) {
            val done = checkedCriteria.count { it in task.acceptanceCriteria.indices }
            GroupedSection("Done when · $done of ${task.acceptanceCriteria.size}") {
                task.acceptanceCriteria.forEachIndexed { i, criterion ->
                    CriterionRow(
                        text = criterion,
                        checked = i in checkedCriteria,
                        onToggle = { onToggleCriterion(i) },
                        showDivider = i < task.acceptanceCriteria.lastIndex,
                    )
                }
            }
        }

        if (task.resources.isNotEmpty()) {
            val uriHandler = LocalUriHandler.current
            GroupedSection("Resources") {
                task.resources.forEachIndexed { i, resource ->
                    GroupedRow(
                        title = resource.label,
                        subtitle = resource.url.substringAfter("://").substringBefore('/'),
                        showDivider = i < task.resources.lastIndex,
                        onClick = { runCatching { uriHandler.openUri(resource.url) } },
                    )
                }
            }
        }
    }
}

/**
 * Wiring shared by the mobile screen and the desktop task panel: opens the code workspace once Start Task finished,
 * and shows the Connect GitHub prompt when Start Task needs it.
 */
@Composable
internal fun TaskCodeEffects(state: TaskDetailUiState, viewModel: TaskDetailViewModel, onOpenCode: () -> Unit) {
    val open by rememberUpdatedState(onOpenCode)
    LaunchedEffect(state.openCodeRequested) {
        if (state.openCodeRequested) {
            viewModel.onCodeOpened()
            open()
        }
    }
    if (state.showConnectGitHub) {
        ConnectGitHubPrompt(onConnected = viewModel::onGitHubConnected, onDismiss = viewModel::dismissGitHubPrompt)
    }
}

/** `task/t1-reject-past-dates…` in monospace, under the chips. */
@Composable
private fun BranchLabel(name: String) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(BauColors.Surface)
            .border(1.dp, BauColors.Line, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Branch $name" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("⎇", style = TextStyle(fontSize = 13.sp), color = BauColors.TextSecondary)
        Text(name, style = TextStyle(fontFamily = MonoFamily, fontSize = 12.sp, lineHeight = 16.sp), color = BauColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Task actions with inline progress + error.
 * - Not started: "Start Task" (creates the branch and opens the code when the project has a repo).
 * - In progress with a repo: "Open Code" (primary), then "Mark as Done" (opens the PR) and "Ask Mentor AI".
 * - Otherwise: the forward action and "Ask Mentor AI".
 */
@Composable
internal fun TaskDetailActions(
    state: TaskDetailUiState,
    onAdvance: () -> Unit,
    onAskMentor: () -> Unit,
    onOpenCode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(BauSpacing.sm)) {
        state.updateError?.let { error ->
            Text(
                error,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
                color = BauColors.WarningInk,
            )
        }
        state.progressLabel?.let { label ->
            Row(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BauSpacing.sm),
            ) {
                CircularProgressIndicator(Modifier.size(14.dp), color = BauColors.Ink, strokeWidth = 2.dp)
                Text(
                    label,
                    style = TextStyle(fontFamily = MonoFamily, fontSize = 12.sp, lineHeight = 16.sp),
                    color = BauColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        state.pullRequest?.let { pr ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (pr.number > 0) "Pull request #${pr.number} opened" else "Pull request opened",
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    color = BauColors.Ink,
                )
                if (pr.url.isNotBlank()) {
                    Text(" · ", style = TextStyle(fontSize = 14.sp), color = BauColors.TextSecondary)
                    BauTextLink("View", onClick = { runCatching { uriHandler.openUri(pr.url) } }, fontSize = 14.sp, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand))
                }
            }
        }
        if (state.canOpenCode) {
            BauPrimaryButton(text = "Open Code", onClick = onOpenCode, modifier = Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BauSpacing.sm)) {
                BauSecondaryButton(
                    if (state.isUpdating) "Working…" else TaskAction.MarkDone.label,
                    onClick = { if (!state.isUpdating) onAdvance() },
                    modifier = Modifier.weight(1f).pointerHoverIcon(PointerIcon.Hand),
                )
                BauSecondaryButton("Ask Mentor AI", onClick = onAskMentor, modifier = Modifier.weight(1f).pointerHoverIcon(PointerIcon.Hand))
            }
        } else {
            state.nextAction?.let { action ->
                BauPrimaryButton(
                    text = action.label,
                    onClick = onAdvance,
                    loading = state.isUpdating,
                    modifier = Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand),
                )
            }
            BauSecondaryButton("Ask Mentor AI", onClick = onAskMentor, modifier = Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand))
        }
    }
}

@Composable
private fun TaskPriorityChip(priority: TaskPriority) = when (priority) {
    TaskPriority.High -> WorkChip(priority.label, BauColors.WarningSoft, BauColors.WarningInk)
    TaskPriority.Medium -> WorkChip(priority.label, BauColors.BlueSoft, BauColors.Ink)
    TaskPriority.Low -> WorkChip(priority.label, BauColors.Surface, BauColors.TextSecondary, border = WorkChipColors.ToDoBorder)
}

@Composable
private fun DeadlineBlock(deadline: TaskDeadline) {
    val shape = RoundedCornerShape(24.dp)
    val overdue = deadline.isOverdue
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(6.dp, shape, ambientColor = BauColors.Ink.copy(alpha = 0.06f), spotColor = BauColors.Ink.copy(alpha = 0.06f))
            .clip(shape)
            .background(if (overdue) BauColors.WarningSoft else BauColors.Surface)
            .border(1.dp, if (overdue) BauColors.WarningSoft else BauColors.Line, shape)
            .padding(horizontal = BauSpacing.lg, vertical = 14.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "Deadline",
            style = MaterialTheme.typography.labelMedium,
            color = if (overdue) BauColors.WarningInk else BauColors.TextSecondary,
        )
        val headline = when {
            !deadline.hasDeadline -> "No deadline"
            deadline.dateLabel != null -> deadline.dateLabel
            else -> deadline.relativeLabel
        }
        Text(
            headline,
            style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp, letterSpacing = (-0.02).em),
            color = if (overdue) BauColors.WarningInk else BauColors.Ink,
        )
        if (deadline.dateLabel != null && deadline.relativeLabel.isNotEmpty()) {
            Text(
                deadline.relativeLabel,
                style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = if (overdue) FontWeight.SemiBold else FontWeight.Normal),
                color = if (overdue) BauColors.Warning else BauColors.TextSecondary,
            )
        }
    }
}

/** Same section chrome as [GroupedSection] for free-form content. */
@Composable
private fun DetailSection(title: String, content: @Composable ColumnScope.() -> Unit) = GroupedSection(title, content = content)

@Composable
private fun CriterionRow(text: String, checked: Boolean, onToggle: () -> Unit, showDivider: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
                .heightIn(min = 56.dp)
                .padding(start = BauSpacing.sm, end = BauSpacing.lg, top = BauSpacing.xs, bottom = BauSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = null,
                modifier = Modifier.size(48.dp),
                colors = CheckboxDefaults.colors(
                    checkedColor = BauColors.Ink,
                    uncheckedColor = WorkChipColors.ToDoBorder,
                    checkmarkColor = BauColors.Yellow,
                ),
            )
            Text(
                text,
                modifier = Modifier.weight(1f),
                style = TextStyle(
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    textDecoration = if (checked) TextDecoration.LineThrough else TextDecoration.None,
                ),
                color = if (checked) BauColors.TextSecondary else BauColors.Ink,
            )
        }
        if (showDivider) HorizontalDivider(thickness = 1.dp, color = BauColors.Line)
    }
}
