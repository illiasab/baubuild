package com.vesper.baubuild.feature.projects

import com.vesper.baubuild.designsystem.bottomContentPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.core.model.BauClass
import com.vesper.baubuild.core.model.Project
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.LoadStateContent

/**
 * Shared scaffold for pushed list screens: back button + big title scroll with the list, pull-to-refresh,
 * and the zero-downtime [LoadStateContent] (full-screen states only before the first successful load).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> PushedListScaffold(
    title: String,
    subtitle: String?,
    load: LoadState<T>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: LazyListScope.(T) -> Unit,
) {
    val insets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical).asPaddingValues()
    val header: LazyListScope.() -> Unit = {
        item(key = "top") { ProjectsTopBar(onBack, backLabel = "Back to Projects", modifier = Modifier.padding(horizontal = 20.dp)) }
        item(key = "title") { ProjectsScreenHeader(title, subtitle, Modifier.padding(horizontal = 20.dp)) }
    }
    val padding = PaddingValues(top = insets.calculateTopPadding() + 10.dp, bottom = bottomContentPadding())
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        when (load) {
            is LoadState.Content -> LoadStateContent(load, onRetry = onRefresh, modifier = Modifier.fillMaxSize()) { data ->
                LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    header()
                    content(data)
                }
            }
            // Before the first load: keep the back button and title, show the spinner / retry below them.
            else -> Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ProjectsTopBar(onBack, backLabel = "Back to Projects", modifier = Modifier.padding(horizontal = 20.dp))
                ProjectsScreenHeader(title, subtitle, Modifier.padding(horizontal = 20.dp))
                Box(Modifier.weight(1f)) { LoadStateContent(load, onRetry = onRefresh) {} }
            }
        }
    }
}

@Composable
internal fun ProjectListScreen(
    state: ProjectListUiState,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onOpenProject: (Project) -> Unit,
    modifier: Modifier = Modifier,
) {
    PushedListScaffold(state.title, state.subtitle, state.projects, state.isRefreshing, onRefresh, onBack, modifier) { projects ->
        if (projects.isEmpty()) {
            item(key = "empty") { EmptyNote("Nothing open here right now. Pull down to check again.", Modifier.padding(horizontal = 20.dp)) }
        }
        items(projects, key = { it.id }) { project ->
            ProjectRow(project, state.viewer.score, onOpen = { onOpenProject(project) }, modifier = Modifier.padding(horizontal = 20.dp))
        }
    }
}

@Composable
internal fun MyClassesScreen(
    state: LoadState<List<BauClass>>,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onOpenClass: (BauClass) -> Unit,
    modifier: Modifier = Modifier,
) {
    val refreshing = (state as? LoadState.Content)?.refreshing == true
    PushedListScaffold("My Classes", "Projects from the classes you’re enrolled in.", state, refreshing, onRefresh, onBack, modifier) { classes ->
        if (classes.isEmpty()) {
            item(key = "empty") {
                EmptyNote("You’re not enrolled in any classes with projects yet.", Modifier.padding(horizontal = 20.dp))
            }
        }
        items(classes, key = { it.code }) { bauClass ->
            ClassRow(bauClass, onOpen = { onOpenClass(bauClass) }, modifier = Modifier.padding(horizontal = 20.dp))
        }
    }
}

@Composable
private fun ClassRow(bauClass: BauClass, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().projectsCard(onOpen, "Open ${bauClass.code} projects").padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(bauClass.code, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = BauColors.BlueDeep)
            Text(bauClass.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp, color = BauColors.Ink)
            Text("${bauClass.professor} · ${bauClass.term}", fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.TextSecondary)
        }
        MutedChip(
            when (bauClass.openProjectCount) {
                0 -> "No open projects"
                1 -> "1 open"
                else -> "${bauClass.openProjectCount} open"
            },
        )
        Icon(ProjectsIcons.ChevronRight, contentDescription = null, tint = BauColors.TextTertiary, modifier = Modifier.size(8.dp, 14.dp))
    }
}
