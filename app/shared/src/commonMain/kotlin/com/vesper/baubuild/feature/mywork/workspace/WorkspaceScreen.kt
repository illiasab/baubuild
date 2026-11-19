package com.vesper.baubuild.feature.mywork.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vesper.baubuild.core.model.ChangeStatus
import com.vesper.baubuild.core.model.CodeChange
import com.vesper.baubuild.core.model.Difficulty
import com.vesper.baubuild.core.model.ProjectTask
import com.vesper.baubuild.core.model.TaskStatus
import com.vesper.baubuild.core.model.Workspace
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.BauPill
import com.vesper.baubuild.designsystem.BauSpacing
import com.vesper.baubuild.designsystem.LoadStateContent
import com.vesper.baubuild.designsystem.MonoFamily
import com.vesper.baubuild.feature.mywork.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspaceScreen(
    viewModel: WorkspaceViewModel,
    onBack: () -> Unit,
    onOpenMentor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        // Top bar stays visible in every load state so the user can always go back.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = BauSpacing.screen).padding(top = BauSpacing.sm).height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BackCircleButton(contentDescription = "Back to My Work", onClick = onBack)
            GlassPillButton(text = "Mentor AI", onClick = onOpenMentor)
        }
        LoadStateContent(state.workspace, onRetry = viewModel::refresh, modifier = Modifier.fillMaxSize()) { workspace ->
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                WorkspaceContent(workspace, state.segment, onSelectSegment = viewModel::selectSegment)
            }
        }
    }
}

@Composable
private fun WorkspaceContent(
    workspace: Workspace,
    segment: WorkspaceSegment,
    onSelectSegment: (WorkspaceSegment) -> Unit,
) {
    val project = workspace.project
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = BauSpacing.screen)
            .padding(top = BauSpacing.lg)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(bottom = BauSpacing.xxl),
        verticalArrangement = Arrangement.spacedBy(BauSpacing.lg),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(BauSpacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BauSpacing.sm)) {
                Text(
                    "${project.owner} · ${project.difficulty.label}",
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp),
                    color = BauColors.BlueDeep,
                )
                ImInChip()
            }
            Text(
                project.title,
                modifier = Modifier.semantics { heading() },
                style = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp, letterSpacing = (-0.02).em),
                color = BauColors.Ink,
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BauSpacing.sm)) {
            WorkspaceSegment.entries.forEach { s ->
                BauPill(s.label, selected = s == segment, onClick = { onSelectSegment(s) }, modifier = Modifier.weight(1f))
            }
        }

        when (segment) {
            WorkspaceSegment.Tasks -> TasksSection(workspace.tasks.filter { it.assignedToMe })
            WorkspaceSegment.Changes -> ChangesSection(workspace.changes)
        }
    }
}

@Composable
private fun TasksSection(tasks: List<ProjectTask>) {
    if (tasks.isEmpty()) {
        EmptyMessage("No tasks yet", "When a task is assigned to you, it shows up here.")
        return
    }
    GroupedSection("Assigned to You") {
        tasks.forEachIndexed { i, task ->
            GroupedRow(
                title = task.title,
                subtitle = task.subtitle,
                showDivider = i < tasks.lastIndex,
            ) { TaskStatusChip(task.status) }
        }
    }
}

@Composable
private fun ChangesSection(changes: List<CodeChange>) {
    if (changes.isEmpty()) {
        EmptyMessage("Nothing pushed yet", "Push a branch to the project repo and it appears here.")
        return
    }
    GroupedSection("Code You Pushed") {
        changes.forEachIndexed { i, change ->
            // TODO(backend): CodeChange has no diff URL yet; the design links each row to its diff.
            GroupedRow(
                title = change.title,
                subtitle = change.branch,
                subtitleFontFamily = MonoFamily,
                showDivider = i < changes.lastIndex,
            ) { ChangeStatusChip(change.status) }
        }
    }
}

internal val Difficulty.label: String get() = when (this) {
    Difficulty.Easy -> "Easy"
    Difficulty.Hard -> "Hard"
}

/** "Due in 3 days" when a due date exists, otherwise the design's "Backlog" label. */
internal val ProjectTask.subtitle: String get() = when (val d = dueInDays) {
    null -> if (status == TaskStatus.Done) "Done" else "Backlog"
    0 -> "Due today"
    1 -> "Due tomorrow"
    else -> if (d < 0) "Overdue" else "Due in $d days"
}

internal val TaskStatus.label: String get() = when (this) {
    TaskStatus.Backlog, TaskStatus.ToDo -> "To Do"
    TaskStatus.InProgress -> "In Progress"
    TaskStatus.Done -> "Done"
}

internal val ChangeStatus.label: String get() = when (this) {
    ChangeStatus.Approved -> "Approved"
    ChangeStatus.WithDevLead -> "With Dev Lead"
    ChangeStatus.ChangesRequested -> "Changes Requested"
    ChangeStatus.Pushed -> "Pushed"
}

@Composable
private fun TaskStatusChip(status: TaskStatus) = when (status) {
    TaskStatus.InProgress -> WorkChip(status.label, BauColors.BlueDeep, BauColors.Surface)
    TaskStatus.Done -> WorkChip(status.label, BauColors.Ink, BauColors.Surface)
    TaskStatus.Backlog, TaskStatus.ToDo ->
        WorkChip(status.label, BauColors.Surface, BauColors.TextSecondary, border = WorkChipColors.ToDoBorder)
}

@Composable
private fun ChangeStatusChip(status: ChangeStatus) = when (status) {
    ChangeStatus.Approved -> WorkChip(status.label, BauColors.Ink, BauColors.Surface)
    ChangeStatus.WithDevLead -> WorkChip(status.label, BauColors.Yellow, BauColors.Ink)
    ChangeStatus.ChangesRequested -> WorkChip(status.label, BauColors.WarningSoft, BauColors.WarningInk)
    ChangeStatus.Pushed -> WorkChip(status.label, BauColors.BlueSoft, BauColors.Ink)
}
