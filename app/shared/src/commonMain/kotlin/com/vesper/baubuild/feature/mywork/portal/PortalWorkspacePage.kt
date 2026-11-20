package com.vesper.baubuild.feature.mywork.portal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vesper.baubuild.core.dataOrNull
import com.vesper.baubuild.core.model.CodeChange
import com.vesper.baubuild.core.model.ProjectTask
import com.vesper.baubuild.core.model.ReviewStageState
import com.vesper.baubuild.core.model.ReviewStatus
import com.vesper.baubuild.core.model.Workspace
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.DisplayFamily
import com.vesper.baubuild.designsystem.LoadStateContent
import com.vesper.baubuild.designsystem.MonoFamily
import com.vesper.baubuild.di.AppGraph
import com.vesper.baubuild.feature.mywork.mentor.MentorChatViewModel
import com.vesper.baubuild.feature.mywork.components.EmptyMessage
import com.vesper.baubuild.feature.mywork.workspace.WorkspaceSegment
import com.vesper.baubuild.feature.mywork.workspace.WorkspaceViewModel
import com.vesper.baubuild.feature.mywork.workspace.subtitle
import com.vesper.baubuild.feature.review.CheckGlyph
import com.vesper.baubuild.feature.review.ReviewViewModel

/** Left column basis + gap + Mentor column basis from the design's flex-wrap layout. */
private val TwoColumnMinWidth = 460.dp + 20.dp + 320.dp
private val MentorColumnWidth = 340.dp

/**
 * Project workspace in the portal design. Must be called with a [androidx.lifecycle.ViewModelStoreOwner] scoped
 * to this destination (see [MyWorkPortalPage]).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Suppress("DEPRECATION") // ui-backhandler BackHandler, same as the other features.
@Composable
internal fun PortalWorkspacePage(
    projectId: String,
    ownerHint: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val repo = AppGraph.current.workRepository
    val workspaceVm = viewModel { WorkspaceViewModel(repo, projectId) }
    val reviewVm = viewModel { ReviewViewModel(repo) }
    val mentorVm = viewModel { MentorChatViewModel(AppGraph.current.mentorRepository, projectId) }

    val state by workspaceVm.state.collectAsStateWithLifecycle()
    val reviewState by reviewVm.state.collectAsStateWithLifecycle()
    val review = reviewState.reviews.dataOrNull?.forProject(projectId)
    val loaded = state.workspace.dataOrNull

    // The task panel's ViewModel is scoped to the panel; status changes reach workspaceVm via TaskUpdateBus.shared.
    var openTaskId by rememberSaveable(projectId) { mutableStateOf<String?>(null) }
    val closeTask = { openTaskId = null }
    // Code workspace for a task ("Open Code" in the task panel). Back returns to the task panel.
    var codeTaskId by rememberSaveable(projectId) { mutableStateOf<String?>(null) }
    val codeTask = codeTaskId
    if (codeTask != null) {
        val taskId: String = codeTask
        val leaveCode = { codeTaskId = null; openTaskId = taskId }
        BackHandler(onBack = leaveCode)
        PortalCodePage(
            projectId = projectId,
            taskId = taskId,
            ownerHint = loaded?.project?.owner ?: ownerHint,
            onBackToMyWork = onBack,
            onBackToProject = leaveCode,
            modifier = modifier,
        )
        return
    }
    val pageScroll = rememberScrollState()
    val mentorInput = remember { FocusRequester() }
    val mentorBringIntoView = remember { BringIntoViewRequester() }
    var mentorFocusRequests by remember { mutableIntStateOf(0) }
    LaunchedEffect(mentorFocusRequests) {
        if (mentorFocusRequests == 0) return@LaunchedEffect
        withFrameNanos { } // let the panel's dismissal settle before moving focus
        mentorBringIntoView.bringIntoView()
        runCatching { mentorInput.requestFocus() }
    }

    Box(
        modifier
            .fillMaxSize()
            // Esc closes the task panel first; consumed here so MyWorkPortalPage doesn't also leave the workspace.
            .onKeyEvent { e ->
                if (openTaskId == null || e.key != Key.Escape) return@onKeyEvent false
                if (e.type == KeyEventType.KeyUp) closeTask()
                true
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            PortalPageTitle("Project")
            // Breadcrumb stays visible in every load state so the user can always go back.
            Breadcrumb(current = loaded?.project?.owner ?: ownerHint, onBack = onBack, modifier = Modifier.padding(horizontal = 32.dp))
            Spacer(Modifier.height(10.dp))
            LoadStateContent(state.workspace, onRetry = workspaceVm::refresh, modifier = Modifier.fillMaxSize()) { workspace ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(pageScroll).padding(start = 32.dp, end = 32.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    ProjectHeading(workspace)
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val main: @Composable (Modifier) -> Unit = { m ->
                            MainColumn(workspace, state.segment, workspaceVm::selectSegment, review, onOpenTask = { openTaskId = it }, m)
                        }
                        val mentor: @Composable (Modifier) -> Unit = { m ->
                            MentorPanel(mentorVm, m.bringIntoViewRequester(mentorBringIntoView), inputFocusRequester = mentorInput)
                        }
                        if (maxWidth >= TwoColumnMinWidth) {
                            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                                main(Modifier.weight(1f))
                                mentor(Modifier.width(MentorColumnWidth))
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                main(Modifier.fillMaxWidth())
                                mentor(Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }

        PortalTaskOverlay(
            projectId = projectId,
            taskId = openTaskId,
            onClose = closeTask,
            onAskMentor = {
                openTaskId?.let { id -> mentorVm.setTaskContext(id, loaded?.tasks?.firstOrNull { it.id == id }?.title) }
                closeTask()
                mentorFocusRequests++
            },
            onOpenCode = { taskId ->
                closeTask()
                codeTaskId = taskId
            },
        )
    }
}

@Composable
private fun Breadcrumb(current: String?, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.semantics { contentDescription = "Breadcrumb" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PortalLink("My Work", onClick = onBack, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
        if (current != null) {
            Text("/", style = TextStyle(fontSize = 14.sp), color = BauColors.TextSecondary)
            Text(current, style = TextStyle(fontSize = 14.sp), color = BauColors.TextSecondary)
        }
    }
}

@Composable
private fun ProjectHeading(workspace: Workspace) {
    val project = workspace.project
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                project.title,
                modifier = Modifier.weight(1f, fill = false).semantics { heading() },
                style = TextStyle(fontFamily = DisplayFamily, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.015).em, lineHeight = 32.sp),
                color = BauColors.Ink,
            )
            PortalImInChip()
        }
        Text(project.workspaceMeta, style = TextStyle(fontFamily = MonoFamily, fontSize = 13.sp), color = BauColors.TextSecondary)
    }
}

@Composable
private fun MainColumn(
    workspace: Workspace,
    segment: WorkspaceSegment,
    onSelectSegment: (WorkspaceSegment) -> Unit,
    review: ReviewStatus?,
    onOpenTask: (taskId: String) -> Unit,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SegmentedControl(segment, onSelectSegment)
        when (segment) {
            WorkspaceSegment.Tasks -> TasksCard(workspace.tasks.filter { it.assignedToMe }, onOpenTask)
            // Each change links to its own commit/compare URL; older servers fall back to the review's branch diff.
            WorkspaceSegment.Changes -> ChangesCard(workspace.changes, diffUrl = review?.diffUrl)
        }
        if (review != null) ReviewStatusPanel(review)
    }
}

@Composable
private fun SegmentedControl(selected: WorkspaceSegment, onSelect: (WorkspaceSegment) -> Unit) {
    val outer = RoundedCornerShape(9.dp)
    val inner = RoundedCornerShape(6.dp)
    Row(
        Modifier.clip(outer).background(BauColors.Surface).border(1.dp, PortalColors.ControlBorder, outer).padding(3.dp),
    ) {
        WorkspaceSegment.entries.forEach { s ->
            val isSelected = s == selected
            Box(
                Modifier
                    .heightIn(min = 44.dp)
                    .clip(inner)
                    .background(if (isSelected) BauColors.Ink else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(s) }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .semantics { this.selected = isSelected }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    s.label,
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    color = if (isSelected) Color.White else BauColors.Ink,
                )
            }
        }
    }
}

@Composable
private fun ListCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().portalCard()) {
        Text(
            title,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp).semantics { heading() },
            style = PortalText.SectionLabel,
            color = BauColors.TextSecondary,
        )
        content()
    }
}

@Composable
private fun ListRow(
    title: String,
    subtitle: String,
    mono: Boolean,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit,
) {
    HorizontalDivider(thickness = 1.dp, color = PortalColors.RowLine)
    HoverRow(onClick = onClick, onClickLabel = onClick?.let { "Open $title" }) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = PortalText.RowTitle, color = BauColors.Ink)
                Text(
                    subtitle,
                    style = PortalText.RowSubtitle.copy(fontFamily = if (mono) MonoFamily else null),
                    color = BauColors.TextSecondary,
                )
            }
            trailing()
        }
    }
}

@Composable
private fun TasksCard(tasks: List<ProjectTask>, onOpenTask: (taskId: String) -> Unit) {
    ListCard("BACKLOG ASSIGNED TO YOU") {
        if (tasks.isEmpty()) {
            HorizontalDivider(thickness = 1.dp, color = PortalColors.RowLine)
            EmptyMessage("No tasks yet", "When a task is assigned to you, it shows up here.")
        }
        tasks.forEach { task ->
            ListRow(task.title, task.subtitle, mono = false, onClick = { onOpenTask(task.id) }) { PortalTaskChip(task.status) }
        }
    }
}

@Composable
private fun ChangesCard(changes: List<CodeChange>, diffUrl: String?) {
    val uriHandler = LocalUriHandler.current
    ListCard("CODE YOU PUSHED TO THIS PROJECT") {
        if (changes.isEmpty()) {
            HorizontalDivider(thickness = 1.dp, color = PortalColors.RowLine)
            EmptyMessage("Nothing pushed yet", "Push a branch to the project repo and it appears here.")
        }
        changes.forEach { change ->
            val subtitle = listOfNotNull(change.branch, change.sha?.take(7)?.takeIf { it != change.branch }).joinToString(" · ")
            ListRow(change.title, subtitle, mono = true) {
                PortalChangeChip(change.status)
                val link = change.url ?: diffUrl
                if (link != null) {
                    PortalLink(
                        "View diff",
                        onClick = { runCatching { uriHandler.openUri(link) } },
                        modifier = Modifier.semantics { contentDescription = "View diff for ${change.title}" },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReviewStatusPanel(review: ReviewStatus) {
    Column(Modifier.fillMaxWidth().portalCard().padding(20.dp)) {
        Text("REVIEW STATUS", modifier = Modifier.semantics { heading() }, style = PortalText.SectionLabel, color = BauColors.TextSecondary)
        Spacer(Modifier.height(16.dp))
        review.stages.forEachIndexed { i, stage ->
            val isLast = i == review.stages.lastIndex
            val stateLabel = when (stage.state) {
                ReviewStageState.Done -> "done"
                ReviewStageState.Active -> "in progress"
                ReviewStageState.Pending -> "not started"
            }
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min).semantics(mergeDescendants = true) {
                    contentDescription = "${stage.title}, $stateLabel"
                },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.width(22.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    SmallStageMarker(stage.state)
                    if (!isLast) Box(Modifier.weight(1f).width(2.dp).background(PortalColors.RowLine))
                }
                Column(
                    Modifier.weight(1f).padding(bottom = if (isLast) 0.dp else 14.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(stage.title, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp), color = BauColors.Ink)
                    val detail = stage.portalDetail
                    if (detail.isNotEmpty()) {
                        Text(detail, style = TextStyle(fontSize = 13.sp, lineHeight = 1.45.em), color = BauColors.TextSecondary)
                    }
                }
            }
        }
    }
}

/** 22dp variant of the review stage marker used in the portal panel. */
@Composable
private fun SmallStageMarker(state: ReviewStageState) {
    val m = Modifier.size(22.dp).clearAndSetSemantics { }
    when (state) {
        ReviewStageState.Done -> Box(m.background(BauColors.BlueDeep, CircleShape), contentAlignment = Alignment.Center) {
            CheckGlyph(Modifier.size(12.dp), BauColors.Surface)
        }
        ReviewStageState.Active -> Box(m.background(BauColors.Ink, CircleShape).border(6.dp, BauColors.Yellow, CircleShape))
        ReviewStageState.Pending -> Box(m.border(2.dp, BauColors.TextTertiary, CircleShape))
    }
}
