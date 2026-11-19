package com.vesper.baubuild.feature.mywork

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vesper.baubuild.core.model.Membership
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.BauSpacing
import com.vesper.baubuild.designsystem.LoadStateContent
import com.vesper.baubuild.di.AppGraph
import com.vesper.baubuild.feature.mywork.components.EmptyMessage
import com.vesper.baubuild.feature.mywork.components.GroupedRow
import com.vesper.baubuild.feature.mywork.components.GroupedSection
import com.vesper.baubuild.feature.mywork.components.ImInChip
import com.vesper.baubuild.feature.mywork.components.WorkChip
import com.vesper.baubuild.feature.mywork.workspace.MentorAiScreen
import com.vesper.baubuild.feature.mywork.workspace.WorkspaceScreen
import com.vesper.baubuild.feature.mywork.workspace.WorkspaceViewModel

/**
 * Entry point for this feature. Hosted by the Compose tab shell and by the native iOS TabView.
 *
 * Navigation: My Work list → project workspace → Mentor AI (stub). The back stack is saveable and system
 * back (Android back, iOS edge-swipe, desktop Esc) pops it via [BackHandler].
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MyWorkTabRoot(modifier: Modifier = Modifier) {
    val backStack = rememberMyWorkBackStack()
    val stores = rememberDestinationStores(backStack)
    val listViewModel = viewModel { MyWorkViewModel(AppGraph.current.workRepository) }

    BackHandler(enabled = backStack.canGoBack) { backStack.pop() }

    Box(modifier.fillMaxSize()) {
        when (val route = backStack.current) {
            null -> MyWorkListScreen(
                viewModel = listViewModel,
                onOpenWorkspace = { backStack.push(MyWorkRoute.Workspace(it)) },
            )
            else -> key(route.key) {
                val owner = remember(route.key) { stores.ownerFor(route.key) }
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    when (route) {
                        is MyWorkRoute.Workspace -> WorkspaceScreen(
                            viewModel = viewModel(key = route.key) {
                                WorkspaceViewModel(AppGraph.current.workRepository, route.projectId)
                            },
                            onBack = { backStack.pop() },
                            onOpenMentor = { backStack.push(MyWorkRoute.MentorAi(route.projectId)) },
                        )
                        is MyWorkRoute.MentorAi -> MentorAiScreen(route.projectId, onBack = { backStack.pop() })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MyWorkListScreen(
    viewModel: MyWorkViewModel,
    onOpenWorkspace: (projectId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LoadStateContent(state.work, onRetry = viewModel::refresh, modifier = modifier.fillMaxSize()) { sections ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = BauSpacing.screen)
                    .padding(top = BauSpacing.md, bottom = BauSpacing.xxl),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                MyWorkHeader()
                if (sections.isEmpty) {
                    EmptyMessage(
                        title = "No projects yet",
                        body = "Join a project from the Projects tab and it shows up here.",
                    )
                }
                if (sections.active.isNotEmpty()) {
                    GroupedSection("I’m In") {
                        sections.active.forEachIndexed { i, m ->
                            MembershipRow(m, showDivider = i < sections.active.lastIndex, onClick = { onOpenWorkspace(m.project.id) })
                        }
                    }
                }
                if (sections.waiting.isNotEmpty()) {
                    GroupedSection("Waiting for Approval") {
                        sections.waiting.forEachIndexed { i, m ->
                            // Pending projects have no workspace yet. The design links them to the project detail,
                            // which lives in the Projects feature — TODO(nav): cross-tab deep link once the shell exposes one.
                            MembershipRow(m, showDivider = i < sections.waiting.lastIndex, onClick = null)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MyWorkHeader() {
    Column(Modifier.fillMaxWidth().padding(top = BauSpacing.md), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "My Work",
            modifier = Modifier.semantics { heading() },
            style = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 41.sp, letterSpacing = (-0.02).em),
            color = BauColors.Ink,
        )
        Text(
            "Projects you are in, and ones waiting on approval.",
            style = TextStyle(fontSize = 15.sp, lineHeight = 20.sp),
            color = BauColors.TextSecondary,
        )
    }
}

@Composable
private fun MembershipRow(membership: Membership, showDivider: Boolean, onClick: (() -> Unit)?) {
    GroupedRow(
        title = membership.project.title,
        subtitle = membership.subtitle,
        showDivider = showDivider,
        onClick = onClick,
    ) {
        if (membership.status.isActive) ImInChip()
        else WorkChip("In Review", BauColors.BlueSoft, BauColors.Ink)
    }
}

/** "Library · 3 tasks" for active projects, "Reviewing: dev lead" for pending ones. */
internal val Membership.subtitle: String
    get() = if (status.isActive) {
        val n = project.taskCount
        "${project.owner} · $n ${if (n == 1) "task" else "tasks"}"
    } else {
        "Reviewing: ${reviewingBy ?: "pending"}"
    }
