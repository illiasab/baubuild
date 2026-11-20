package com.vesper.baubuild.feature.projects

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.vesper.baubuild.core.model.MembershipStatus
import kotlinx.coroutines.delay
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vesper.baubuild.core.model.Project
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.di.AppGraph
import com.vesper.baubuild.feature.github.ConnectGitHubPrompt
import com.vesper.baubuild.feature.github.GitHubJoinPromptWhy
import com.vesper.baubuild.feature.notifications.NotificationsScreen
import com.vesper.baubuild.feature.notifications.NotificationsViewModel

private const val HOME_KEY = "home"

/** Entry point for this feature. Hosted by the Compose tab shell and by the native iOS TabView. */
@OptIn(ExperimentalComposeUiApi::class)
@Suppress("DEPRECATION") // ui-backhandler BackHandler; move to NavigationBackHandler with the other tabs.
@Composable
fun ProjectsTabRoot(modifier: Modifier = Modifier) {
    val backStack = rememberProjectsBackStack()
    val stores: ProjectsDestinationStores = viewModel { ProjectsDestinationStores() }
    val saveableStates = rememberSaveableStateHolder()
    // Row data handed to the detail screen so it renders instantly while it refreshes. Not saved: after process
    // death the detail simply loads.
    val seeds = remember { mutableMapOf<String, Project>() }
    // One-shot confirmation shown on Projects home after a successful join.
    var joinNotice by remember { mutableStateOf<MembershipStatus?>(null) }
    // "Next step: connect GitHub" after joining a project with a repo (set by the join, shown after the banner).
    var pendingGitHubRepo by remember { mutableStateOf<String?>(null) }
    var gitHubPromptRepo by remember { mutableStateOf<String?>(null) }
    val navigator = remember(backStack) {
        ProjectsNavigator(
            push = backStack::push,
            pop = { backStack.pop() },
            onJoined = { status, connectGitHubRepo ->
                joinNotice = status
                pendingGitHubRepo = connectGitHubRepo
                backStack.popToRoot()
            },
            openProject = { project ->
                seeds[project.id] = project
                backStack.push(ProjectsRoute.Detail(project.id))
            },
        )
    }

    // System back (Android), Esc / back on desktop, and the iOS edge-swipe gesture.
    BackHandler(enabled = backStack.canGoBack) { backStack.pop() }

    val current = backStack.current
    Box(modifier.fillMaxSize().background(BauColors.Paper)) {
        AnimatedContent(
            targetState = current,
            contentKey = { it?.key ?: HOME_KEY },
            transitionSpec = {
                val forward = depthOf(targetState, backStack) > depthOf(initialState, backStack)
                val enter = slideInHorizontally(tween(260)) { w -> if (forward) w / 4 else -w / 4 } + fadeIn(tween(200))
                val exit = slideOutHorizontally(tween(260)) { w -> if (forward) -w / 4 else w / 4 } + fadeOut(tween(160))
                enter togetherWith exit
            },
            label = "projects-nav",
        ) { route ->
            val entryKey = route?.key ?: HOME_KEY
            key(entryKey) {
                DisposableEffect(entryKey) {
                    onDispose {
                        // Only forget a destination once it has been popped (not on rotation or tab switches).
                        if (entryKey != HOME_KEY && backStack.routes.none { it.key == entryKey }) {
                            stores.retainOnly(backStack.routes.mapTo(mutableSetOf()) { it.key })
                            saveableStates.removeState(entryKey)
                        }
                    }
                }
                saveableStates.SaveableStateProvider(entryKey) {
                    Box(Modifier.fillMaxSize().background(BauColors.Paper)) {
                        if (route == null) {
                            HomeDestination(navigator)
                        } else {
                            CompositionLocalProvider(LocalViewModelStoreOwner provides stores.ownerFor(entryKey)) {
                                Destination(route, navigator, seeds)
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = joinNotice != null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // Keep showing the last status while the exit animation runs.
            var shown by remember { mutableStateOf(MembershipStatus.In) }
            joinNotice?.let { shown = it }
            JoinedCard(
                shown,
                Modifier.widthIn(max = 520.dp)
                    .shadow(12.dp, RoundedCornerShape(24.dp))
                    .clickable { joinNotice = null },
            )
        }
    }
    LaunchedEffect(joinNotice) {
        if (joinNotice != null) {
            delay(3_000)
            joinNotice = null
        }
    }
    LaunchedEffect(pendingGitHubRepo) {
        val repo = pendingGitHubRepo ?: return@LaunchedEffect
        delay(JoinGitHubPromptDelayMs) // let the pop-to-home and the join banner land first
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

/** Delay between the join confirmation and the "connect GitHub" prompt (mobile and desktop). */
internal const val JoinGitHubPromptDelayMs = 700L

/** Navigation intents screens may trigger. */
internal class ProjectsNavigator(
    val push: (ProjectsRoute) -> Unit,
    val pop: () -> Unit,
    /**
     * After "I'm In" / "Request" succeeds: return to Projects home so the user isn't left on the detail.
     * The second value is [JoinState.Joined.connectGitHubRepo] (non-null = prompt to connect GitHub next).
     */
    val onJoined: (MembershipStatus, String?) -> Unit,
    val openProject: (Project) -> Unit,
)

private fun depthOf(route: ProjectsRoute?, backStack: ProjectsBackStack): Int {
    if (route == null) return 0
    val index = backStack.routes.indexOfLast { it.key == route.key }
    // A route that is no longer on the stack was just popped, so it is "deeper" than whatever is shown now.
    return if (index < 0) Int.MAX_VALUE else index + 1
}

@Composable
private fun HomeDestination(navigator: ProjectsNavigator) {
    val graph = AppGraph.current
    val vm = viewModel {
        ProjectsHomeViewModel(graph.projectRepository, graph.inquiryRepository, graph.session.state, graph.notificationRepository)
    }
    // Home re-enters composition when the user pops back (e.g. from Notifications): re-check the bell badge.
    LaunchedEffect(vm) { vm.refreshUnread() }
    val state by vm.state.collectAsStateWithLifecycle()
    ProjectsHomeScreen(
        state = state,
        onRefresh = vm::refresh,
        onOpenProject = navigator.openProject,
        onOpenProjectId = { navigator.push(ProjectsRoute.Detail(it)) },
        onNavigate = navigator.push,
    )
}

@Composable
private fun Destination(route: ProjectsRoute, navigator: ProjectsNavigator, seeds: Map<String, Project>) {
    val graph = AppGraph.current
    when (route) {
        is ProjectsRoute.ProjectList -> {
            val vm = viewModel { ProjectListViewModel(route.source, graph.projectRepository, graph.session.state) }
            val state by vm.state.collectAsStateWithLifecycle()
            ProjectListScreen(state, onRefresh = vm::refresh, onBack = navigator.pop, onOpenProject = navigator.openProject)
        }
        ProjectsRoute.MyClasses -> {
            val vm = viewModel { MyClassesViewModel(graph.classRepository) }
            val state by vm.state.collectAsStateWithLifecycle()
            MyClassesScreen(
                state, onRefresh = vm::refresh, onBack = navigator.pop,
                onOpenClass = { navigator.push(ProjectsRoute.ProjectList(ProjectListSource.ForClass(it.code, it.name))) },
            )
        }
        is ProjectsRoute.Detail -> {
            val vm = viewModel {
                ProjectDetailViewModel(
                    route.projectId, graph.projectRepository, graph.session.state, seeds[route.projectId],
                    // No "connect GitHub" prompt while `features.github` is off.
                            isGitHubConnected = { !graph.gitHubEnabled || graph.gitHubRepository.account.value != null },
                )
            }
            val state by vm.state.collectAsStateWithLifecycle()
            ProjectDetailScreen(state, onBack = navigator.pop, onRetry = vm::refresh, onJoin = vm::join)
            val join = state.join
            LaunchedEffect(join) { if (join is JoinState.Joined) navigator.onJoined(join.status, join.connectGitHubRepo) }
        }
        ProjectsRoute.RequestProject -> {
            val vm = viewModel { InquiryFormViewModel(graph.inquiryRepository, graph.session.state) }
            // Students never see the entry point; if one lands here anyway (e.g. restored stack after a role
            // change), leave quietly.
            if (!vm.isAllowed) {
                LaunchedEffect(Unit) { navigator.pop() }
                return
            }
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
                    onDone = navigator.pop,
                )
            }
            InquiryFormScreen(state, actions, onBack = navigator.pop)
        }
        ProjectsRoute.Notifications -> {
            val vm = viewModel { NotificationsViewModel(graph.notificationRepository) }
            val state by vm.state.collectAsStateWithLifecycle()
            NotificationsScreen(state, onRefresh = vm::refresh, onBack = navigator.pop, onOpen = { vm.markRead(it.id) })
        }
    }
}
