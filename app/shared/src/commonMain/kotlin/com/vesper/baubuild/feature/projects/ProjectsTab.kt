package com.vesper.baubuild.feature.projects

import androidx.compose.animation.AnimatedContent
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
    val navigator = remember(backStack) {
        ProjectsNavigator(
            push = backStack::push,
            pop = { backStack.pop() },
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
    }
}

/** Navigation intents screens may trigger. */
internal class ProjectsNavigator(
    val push: (ProjectsRoute) -> Unit,
    val pop: () -> Unit,
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
    val vm = viewModel { ProjectsHomeViewModel(graph.projectRepository, graph.inquiryRepository, graph.session.state) }
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
                ProjectDetailViewModel(route.projectId, graph.projectRepository, graph.session.state, seeds[route.projectId])
            }
            val state by vm.state.collectAsStateWithLifecycle()
            ProjectDetailScreen(state, onBack = navigator.pop, onRetry = vm::refresh, onJoin = vm::join)
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
    }
}
