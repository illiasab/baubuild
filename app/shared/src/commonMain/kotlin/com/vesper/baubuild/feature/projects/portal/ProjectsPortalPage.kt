package com.vesper.baubuild.feature.projects.portal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.core.dataOrNull
import com.vesper.baubuild.core.model.MembershipStatus
import com.vesper.baubuild.core.model.Project
import com.vesper.baubuild.core.session.SessionState
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.LoadStateContent
import com.vesper.baubuild.designsystem.StaleBanner
import com.vesper.baubuild.di.AppGraph
import com.vesper.baubuild.feature.auth.rememberPrefersReducedMotion
import com.vesper.baubuild.feature.notifications.NotificationsScreen
import com.vesper.baubuild.feature.notifications.NotificationsViewModel
import com.vesper.baubuild.feature.projects.InquiryFormActions
import com.vesper.baubuild.feature.projects.InquiryFormScreen
import com.vesper.baubuild.feature.projects.InquiryFormViewModel
import com.vesper.baubuild.feature.projects.JoinState
import com.vesper.baubuild.feature.projects.JoinGitHubPromptDelayMs
import com.vesper.baubuild.feature.github.ConnectGitHubPrompt
import com.vesper.baubuild.feature.github.GitHubJoinPromptWhy
import com.vesper.baubuild.feature.projects.JoinedCard
import com.vesper.baubuild.feature.projects.MyClassesViewModel
import com.vesper.baubuild.feature.projects.ProjectDetailViewModel
import com.vesper.baubuild.feature.projects.ProjectsHomeUiState
import com.vesper.baubuild.feature.projects.ProjectsHomeViewModel
import kotlinx.coroutines.delay

private val PageGutter = 32.dp
private val DetailPanelWidth = 480.dp

/** Desktop page in the Web portal design. Hosted by the portal shell's content area (right of the sidebar). */
@Composable
fun ProjectsPortalPage(modifier: Modifier = Modifier) {
    val graph = AppGraph.current
    val home = viewModel(key = "portal/projects/home") {
        ProjectsHomeViewModel(graph.projectRepository, graph.inquiryRepository, graph.session.state, graph.notificationRepository)
    }
    val classesVm = viewModel(key = "portal/projects/classes") { MyClassesViewModel(graph.classRepository) }
    val state by home.state.collectAsStateWithLifecycle()
    val classes by classesVm.state.collectAsStateWithLifecycle()
    val session by graph.session.state.collectAsStateWithLifecycle()

    var filters by remember { mutableStateOf(PortalProjectFilters()) }
    var openProject by remember { mutableStateOf<Project?>(null) }
    var showInquiry by remember { mutableStateOf(false) }
    var showNotifications by remember { mutableStateOf(false) }
    var joinNotice by remember { mutableStateOf<MembershipStatus?>(null) }
    // "Next step: connect GitHub" after joining a project with a repo; shown once the detail panel has closed.
    var pendingGitHubRepo by remember { mutableStateOf<String?>(null) }
    var gitHubPromptRepo by remember { mutableStateOf<String?>(null) }

    val urgentInquiries = state.urgent.dataOrNull.orEmpty()
    val context = PortalFilterContext(
        score = state.viewer.score,
        myClassCodes = buildSet {
            (session as? SessionState.SignedIn)?.user?.classCodes?.let(::addAll)
            classes.dataOrNull?.forEach { add(it.code) }
        },
        urgentProjectIds = urgentProjectIdsOf(urgentInquiries),
    )

    Box(modifier.fillMaxSize().background(BauColors.Paper)) {
        Column(Modifier.fillMaxSize()) {
            PageHeader(
                query = filters.query,
                onQuery = { filters = filters.copy(query = it) },
                canRequestProject = state.viewer.canRequestProject,
                onRequestProject = { showInquiry = true },
                hasUnread = state.hasUnreadNotifications,
                showNotifications = showNotifications,
                onNotifications = { showNotifications = it; if (!it) home.refreshUnread() },
            )
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if (state.isRefreshing) LinearProgressIndicator(Modifier.fillMaxSize(), color = BauColors.Blue, trackColor = BauColors.Paper)
            }
            LoadStateContent(state.projects, onRetry = home::refresh, modifier = Modifier.fillMaxWidth().weight(1f)) { projects ->
                // LoadStateContent covers a stale project list; also flag a stale urgent list.
                if ((state.urgent as? LoadState.Content)?.stale == true && (state.projects as? LoadState.Content)?.stale != true) {
                    StaleBanner(onRetry = home::refresh)
                }
                ProjectsGrid(
                    layout = portalProjectsLayout(projects, urgentInquiries, filters, context),
                    state = state,
                    filters = filters,
                    onFilters = { filters = it },
                    onRetry = home::refresh,
                    onOpen = { openProject = it },
                )
            }
        }

        DetailOverlay(
            project = openProject,
            urgentIds = context.urgentProjectIds,
            onClose = { openProject = null },
            onJoined = { status, connectGitHubRepo ->
                openProject = null
                joinNotice = status
                pendingGitHubRepo = connectGitHubRepo
            },
        )

        JoinNotice(joinNotice, onDismiss = { joinNotice = null }, modifier = Modifier.align(Alignment.TopCenter))
    }

    if (showInquiry) {
        InquiryDialog(onClose = {
            showInquiry = false
            // A new urgent request should show up at the top of the page.
            home.refresh()
        })
    }

    LaunchedEffect(joinNotice) {
        if (joinNotice != null) {
            delay(3_000)
            joinNotice = null
        }
    }
    LaunchedEffect(pendingGitHubRepo) {
        val repo = pendingGitHubRepo ?: return@LaunchedEffect
        delay(JoinGitHubPromptDelayMs) // after the detail panel's exit animation
        gitHubPromptRepo = repo
        pendingGitHubRepo = null
    }
    gitHubPromptRepo?.let { repo ->
        ConnectGitHubPrompt(
            onConnected = { gitHubPromptRepo = null },
            onDismiss = { gitHubPromptRepo = null },
            repoFullName = repo,
            why = GitHubJoinPromptWhy,
            dismissLabel = "Later",
        )
    }
}

@Composable
private fun PageHeader(
    query: String,
    onQuery: (String) -> Unit,
    canRequestProject: Boolean,
    onRequestProject: () -> Unit,
    hasUnread: Boolean,
    showNotifications: Boolean,
    onNotifications: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PortalHeaderTitle()
        Spacer(Modifier.weight(1f))
        PortalSearchField(query, onQuery, Modifier.weight(1f, fill = false))
        if (canRequestProject) PortalButton("Request a Project", onClick = onRequestProject)
        Box {
            PortalIconButton(BellIcon, "Notifications", onClick = { onNotifications(!showNotifications) }, badge = hasUnread)
            if (showNotifications) {
                val y = with(LocalDensity.current) { 52.dp.roundToPx() }
                Popup(
                    alignment = Alignment.TopEnd,
                    offset = IntOffset(0, y),
                    onDismissRequest = { onNotifications(false) },
                    properties = PopupProperties(focusable = true),
                ) { NotificationsPopover(onClose = { onNotifications(false) }) }
            }
        }
    }
}

@Composable
private fun ProjectsGrid(
    layout: PortalProjectsLayout,
    state: ProjectsHomeUiState,
    filters: PortalProjectFilters,
    onFilters: (PortalProjectFilters) -> Unit,
    onRetry: () -> Unit,
    onOpen: (Project) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(290.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = PageGutter, end = PageGutter, bottom = PageGutter),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "filters", span = { GridItemSpan(maxLineSpan) }) {
            PortalFilterBar(filters, onFilters, Modifier.padding(bottom = 4.dp))
        }
        layout.hero?.let { hero ->
            item(key = "hero", span = { GridItemSpan(maxLineSpan) }) {
                UrgentHeroCard(hero, onOpen = { onOpen(hero.project) }, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
        if (layout.isEmpty) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                if (filters.urgent && state.urgent is LoadState.Failed) {
                    PortalEmptyState("Couldn’t load urgent requests right now.", onClear = onRetry, actionLabel = "Try Again")
                } else {
                    PortalEmptyState(
                        filters.emptyMessage(),
                        onClear = if (filters.isDefault) null else ({ onFilters(PortalProjectFilters()) }),
                    )
                }
            }
        }
        items(layout.grid, key = { it.id }) { project ->
            PortalProjectCard(project, state.viewer.score, onOpen = { onOpen(project) })
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Detail panel (slides in from the right over a scrim; Esc / scrim click / close button dismiss it)
// ---------------------------------------------------------------------------------------------------------------

@OptIn(ExperimentalComposeUiApi::class)
@Suppress("DEPRECATION") // ui-backhandler BackHandler, same as ProjectsTabRoot.
@Composable
private fun DetailOverlay(project: Project?, urgentIds: Set<String>, onClose: () -> Unit, onJoined: (MembershipStatus, String?) -> Unit) {
    val reducedMotion = rememberPrefersReducedMotion()
    // Keep the last project while the exit animation runs.
    var shown by remember { mutableStateOf<Project?>(null) }
    if (project != null) shown = project
    BackHandler(enabled = project != null) { onClose() }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(project != null, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(BauColors.Ink.copy(alpha = 0.32f))
                    .clickable(interactionSource = null, indication = null, onClickLabel = "Close project details", onClick = onClose),
            )
        }
        AnimatedVisibility(
            project != null,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            enter = if (reducedMotion) fadeIn() else slideInHorizontally { it } + fadeIn(),
            exit = if (reducedMotion) fadeOut() else slideOutHorizontally { it } + fadeOut(),
        ) {
            val p = shown ?: return@AnimatedVisibility
            key(p.id) {
                ScopedViewModels {
                    val graph = AppGraph.current
                    val vm = viewModel { ProjectDetailViewModel(
                            p.id, graph.projectRepository, graph.session.state, seed = p,
                            isGitHubConnected = { graph.gitHubRepository.account.value != null },
                        )
                    }
                    val state by vm.state.collectAsStateWithLifecycle()
                    val join = state.join
                    LaunchedEffect(join) { if (join is JoinState.Joined) onJoined(join.status, join.connectGitHubRepo) }
                    PortalProjectDetailPanel(
                        state = state,
                        urgent = p.id in urgentIds,
                        onClose = onClose,
                        onRetry = vm::refresh,
                        onJoin = vm::join,
                        modifier = Modifier.widthIn(max = DetailPanelWidth).fillMaxWidth().fillMaxHeight()
                            .shadow(24.dp, RoundedCornerShape(0.dp))
                            .border(1.dp, PortalColors.CardBorder),
                    )
                }
            }
        }
    }
}

@Composable
private fun JoinNotice(status: MembershipStatus?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = status != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier.padding(top = 20.dp, start = 16.dp, end = 16.dp),
    ) {
        var last by remember { mutableStateOf(MembershipStatus.In) }
        status?.let { last = it }
        JoinedCard(
            last,
            Modifier.widthIn(max = 520.dp).shadow(12.dp, RoundedCornerShape(24.dp)).clickable(onClick = onDismiss).handCursor(),
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun NotificationsPopover(onClose: () -> Unit) {
    ScopedViewModels {
        val graph = AppGraph.current
        val vm = viewModel { NotificationsViewModel(graph.notificationRepository) }
        val state by vm.state.collectAsStateWithLifecycle()
        val shape = RoundedCornerShape(14.dp)
        Box(
            Modifier.size(width = 400.dp, height = 560.dp).shadow(20.dp, shape).clip(shape)
                .background(BauColors.Paper).border(1.dp, PortalColors.CardBorder, shape),
        ) {
            NotificationsScreen(
                state, onRefresh = vm::refresh, onBack = onClose, onOpen = { vm.markRead(it.id) },
                backLabel = "Close notifications",
            )
        }
    }
}

@Composable
private fun InquiryDialog(onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        // Don't lose a half-written request to a stray click; Esc and the back button still close it.
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        ScopedViewModels {
            val graph = AppGraph.current
            val vm = viewModel { InquiryFormViewModel(graph.inquiryRepository, graph.session.state) }
            if (!vm.isAllowed) {
                LaunchedEffect(Unit) { onClose() }
            } else {
                val state by vm.state.collectAsStateWithLifecycle()
                val actions = remember(vm) {
                    InquiryFormActions(
                        onTitle = vm::onTitleChange,
                        onDescription = vm::onDescriptionChange,
                        onDepartment = vm::onDepartmentChange,
                        onUrgent = vm::onUrgentChange,
                        onBounty = vm::onBountyChange,
                        onSubmit = vm::submit,
                        onStartAnother = vm::startAnother,
                        onDone = onClose,
                    )
                }
                val shape = RoundedCornerShape(14.dp)
                Box(Modifier.widthIn(max = 600.dp).fillMaxWidth(0.9f).fillMaxHeight(0.9f).clip(shape).background(BauColors.Paper)) {
                    InquiryFormScreen(state, actions, onBack = onClose)
                }
            }
        }
    }
}

/** Fresh ViewModels for a transient surface (panel / dialog), cleared when it leaves composition. */
@Composable
private fun ScopedViewModels(content: @Composable () -> Unit) {
    val owner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}
