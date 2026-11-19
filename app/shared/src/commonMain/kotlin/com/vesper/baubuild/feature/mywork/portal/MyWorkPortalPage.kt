package com.vesper.baubuild.feature.mywork.portal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vesper.baubuild.core.dataOrNull
import com.vesper.baubuild.core.model.Membership
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.DisplayFamily
import com.vesper.baubuild.designsystem.LoadStateContent
import com.vesper.baubuild.designsystem.MonoFamily
import com.vesper.baubuild.di.AppGraph
import com.vesper.baubuild.feature.mywork.MyWorkDestinationStores
import com.vesper.baubuild.feature.mywork.MyWorkSections
import com.vesper.baubuild.feature.mywork.MyWorkViewModel
import com.vesper.baubuild.feature.mywork.components.EmptyMessage
import com.vesper.baubuild.feature.mywork.isActive

/**
 * Desktop page in the Web portal design. Hosted by the portal shell's content area (right of the sidebar).
 *
 * My Work table → project workspace (with Mentor AI). The open workspace survives recomposition/restoration;
 * the breadcrumb, Esc and system back return to the table.
 *
 * @param onOpenProject opens a pending project's detail in the Projects feature. The design's "Open" on a
 *  waiting row links there. When null (no cross-feature route yet), pending rows are not clickable.
 *  TODO(nav): wire from the portal shell once it exposes a cross-page route.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Suppress("DEPRECATION") // ui-backhandler BackHandler, same as the other features.
@Composable
fun MyWorkPortalPage(
    modifier: Modifier = Modifier,
    onOpenProject: ((projectId: String) -> Unit)? = null,
) {
    var openProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    val listViewModel = viewModel { MyWorkViewModel(AppGraph.current.workRepository) }
    val stores = viewModel(key = "mywork-portal-stores") { MyWorkDestinationStores() }
    val destinationKey = openProjectId?.let { "portal-workspace/$it" }
    LaunchedEffect(destinationKey) { stores.retainOnly(setOfNotNull(destinationKey)) }

    val close = { openProjectId = null }
    BackHandler(enabled = openProjectId != null, onBack = close)

    Box(
        modifier
            .fillMaxSize()
            // Esc also works when focus is inside the page (BackHandler covers the unfocused case). Idempotent.
            .onKeyEvent { e ->
                if (openProjectId != null && e.key == Key.Escape && e.type == KeyEventType.KeyUp) { close(); true } else false
            },
    ) {
        val projectId = openProjectId
        if (projectId == null) {
            MyWorkTablePage(
                viewModel = listViewModel,
                onOpenWorkspace = { openProjectId = it },
                onOpenProject = onOpenProject,
            )
        } else {
            key(projectId) {
                val owner = remember(projectId) { stores.ownerFor("portal-workspace/$projectId") }
                val ownerFallback = listViewModel.state.value.work.dataOrNull
                    ?.active?.firstOrNull { it.project.id == projectId }?.project?.owner
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    PortalWorkspacePage(projectId = projectId, ownerHint = ownerFallback, onBack = close)
                }
            }
        }
    }
}

@Composable
private fun MyWorkTablePage(
    viewModel: MyWorkViewModel,
    onOpenWorkspace: (String) -> Unit,
    onOpenProject: ((String) -> Unit)?,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        PortalPageTitle("My Work")
        LoadStateContent(state.work, onRetry = viewModel::refresh, modifier = Modifier.fillMaxSize()) { sections ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 32.dp, end = 32.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                SummaryCards(MyWorkSummary.from(sections))
                MembershipTable(sections, onOpenWorkspace, onOpenProject)
                Text(
                    "Most projects let you in straight away. A few need a professor or dev lead to approve you first.",
                    style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
                    color = BauColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun SummaryCards(summary: MyWorkSummary) {
    val cards = listOf(
        "I’m in" to projectCountLabel(summary.activeProjects),
        "Waiting for approval" to projectCountLabel(summary.waitingProjects),
        "Tasks assigned to me" to summary.tasksAssigned.toString(),
    )
    // CSS grid `repeat(auto-fit, minmax(200px, 1fr))` with a 16px gap.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = ((maxWidth + 16.dp) / (200.dp + 16.dp)).toInt().coerceIn(1, cards.size)
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            cards.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { (label, value) -> StatCard(label, value, Modifier.weight(1f).fillMaxHeight()) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier) {
    Column(
        modifier.portalCard().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp), color = BauColors.TextSecondary)
        Text(value, style = TextStyle(fontFamily = DisplayFamily, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp), color = BauColors.Ink)
    }
}

/** Column weights for PROJECT / FROM / STATUS / WHERE IT STANDS / BOUNTY, plus a fixed "Open" column. */
private val ColumnWeights = floatArrayOf(2.6f, 1.3f, 1.1f, 2.0f, 0.8f)
private val OpenColumnWidth = 72.dp
private val TableMinWidth = 760.dp
private val CellPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)

@Composable
private fun MembershipTable(
    sections: MyWorkSections,
    onOpenWorkspace: (String) -> Unit,
    onOpenProject: ((String) -> Unit)?,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().portalCard()) {
        val needsScroll = maxWidth < TableMinWidth
        val tableWidth: Dp = if (needsScroll) TableMinWidth else maxWidth
        Column(
            Modifier
                .then(if (needsScroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
                .width(tableWidth),
        ) {
            HeaderRow()
            val rows = sections.active + sections.waiting
            if (rows.isEmpty()) {
                HorizontalDivider(thickness = 1.dp, color = PortalColors.RowLine)
                EmptyMessage("No projects yet", "Join a project from Projects and it shows up here.")
            }
            rows.forEach { membership ->
                HorizontalDivider(thickness = 1.dp, color = PortalColors.RowLine)
                val open: (() -> Unit)? = when {
                    membership.status.isActive -> { { onOpenWorkspace(membership.project.id) } }
                    onOpenProject != null -> { { onOpenProject(membership.project.id) } }
                    else -> null
                }
                MembershipTableRow(membership, open)
            }
        }
    }
}

@Composable
private fun HeaderRow() {
    val labels = listOf("PROJECT", "FROM", "STATUS", "WHERE IT STANDS", "BOUNTY")
    Row(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { i, label ->
            Text(
                label,
                modifier = Modifier.weight(ColumnWeights[i]).padding(horizontal = 20.dp, vertical = 12.dp),
                style = PortalText.SectionLabel,
                color = BauColors.TextSecondary,
            )
        }
        Spacer(Modifier.width(OpenColumnWidth))
    }
}

@Composable
private fun MembershipTableRow(membership: Membership, onOpen: (() -> Unit)?) {
    val project = membership.project
    HoverRow(onClick = onOpen, onClickLabel = "Open ${project.title}") {
        Text(
            project.title,
            modifier = Modifier.weight(ColumnWeights[0]).padding(CellPadding),
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
            color = BauColors.Ink,
        )
        Text(
            project.owner,
            modifier = Modifier.weight(ColumnWeights[1]).padding(CellPadding),
            style = TextStyle(fontFamily = MonoFamily, fontSize = 13.sp, lineHeight = 18.sp),
            color = BauColors.Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(Modifier.weight(ColumnWeights[2]).padding(CellPadding)) {
            if (membership.status.isActive) PortalImInChip()
            else PortalChip("In Review", BauColors.BlueSoft, BauColors.Ink)
        }
        Text(
            membership.whereItStands,
            modifier = Modifier.weight(ColumnWeights[3]).padding(CellPadding),
            style = TextStyle(fontSize = 15.sp, lineHeight = 20.sp),
            color = BauColors.TextSecondary,
        )
        Text(
            "$${project.bountyUsd}",
            modifier = Modifier.weight(ColumnWeights[4]).padding(CellPadding),
            style = TextStyle(fontFamily = DisplayFamily, fontSize = 15.sp, fontWeight = FontWeight.Bold),
            color = BauColors.Ink,
        )
        Box(Modifier.width(OpenColumnWidth).padding(end = 20.dp), contentAlignment = androidx.compose.ui.Alignment.CenterEnd) {
            // The whole row is the click target; this label mirrors the design's "Open" link.
            if (onOpen != null) Text("Open", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = BauColors.BlueDeep)
        }
    }
}
