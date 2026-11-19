package com.vesper.baubuild.feature.staff.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vesper.baubuild.core.model.ProjectType
import com.vesper.baubuild.core.model.StaffInquiry
import com.vesper.baubuild.core.model.StaffOverview
import com.vesper.baubuild.core.model.WorldProject
import com.vesper.baubuild.core.session.SessionState
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.BauPrimaryButton
import com.vesper.baubuild.designsystem.BauSpacing
import com.vesper.baubuild.designsystem.BauTextLink
import com.vesper.baubuild.designsystem.LoadStateContent
import com.vesper.baubuild.designsystem.tabBarContentPadding
import com.vesper.baubuild.di.AppGraph
import com.vesper.baubuild.feature.staff.StaffNav
import com.vesper.baubuild.feature.staff.StaffTab
import com.vesper.baubuild.feature.staff.inquiries.StaffRoute
import com.vesper.baubuild.feature.staff.inquiries.StaffTabHost
import com.vesper.baubuild.feature.staff.inquiries.components.InquiryRow
import com.vesper.baubuild.feature.staff.inquiries.components.StaffColors
import com.vesper.baubuild.feature.staff.inquiries.components.StaffIcons
import com.vesper.baubuild.feature.staff.inquiries.components.StaffLargeTitle
import com.vesper.baubuild.feature.staff.inquiries.components.StaffSectionTitle
import com.vesper.baubuild.feature.staff.inquiries.components.StaffTag
import com.vesper.baubuild.feature.staff.inquiries.components.staffCard
import com.vesper.baubuild.feature.staff.inquiries.formatUsd
import com.vesper.baubuild.feature.staff.inquiries.overviewSubtitle
import com.vesper.baubuild.feature.staff.inquiries.rememberStaffBackStack

/**
 * Staff mobile entry point. Hosted by the staff tab shells (native iOS TabView / Compose on Android).
 * Overview → inquiry detail (own back stack, separate from the Inquiries tab's).
 */
@Composable
fun StaffOverviewTabRoot(modifier: Modifier = Modifier) {
    val backStack = rememberStaffBackStack()
    val overviewViewModel = viewModel {
        val graph = AppGraph.current
        StaffOverviewViewModel(graph.staffRepository) { (graph.session.state.value as? SessionState.SignedIn)?.user }
    }
    StaffTabHost(hostKey = "overview", backStack = backStack, backLabel = "Back to Overview", modifier = modifier) {
        StaffOverviewScreen(
            viewModel = overviewViewModel,
            onOpenInquiry = { backStack.push(StaffRoute.InquiryDetail(it)) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StaffOverviewScreen(
    viewModel: StaffOverviewViewModel,
    onOpenInquiry: (inquiryId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LoadStateContent(state.content, onRetry = { viewModel.refresh() }, modifier = modifier.fillMaxSize()) { content ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(horizontal = BauSpacing.screen)
                    .padding(top = BauSpacing.md)
                    .tabBarContentPadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StaffLargeTitle("Overview", eyebrow = content.greeting, modifier = Modifier.padding(top = BauSpacing.md))
                SemesterCard(content.overview)
                content.overview.needsAttention?.let { NeedsAttentionCard(it, onOpen = { onOpenInquiry(it.id) }) }
                if (content.hasInquiries) {
                    RecentInquiries(content.recent, onOpenInquiry)
                } else {
                    FirstInquiryCard()
                    if (content.worldProjects.isNotEmpty()) WorldProjects(content.worldProjects)
                }
            }
        }
    }
}

private val RoundedNumber = TextStyle(fontWeight = FontWeight.Bold)

@Composable
private fun SemesterCard(overview: StaffOverview) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        Modifier.fillMaxWidth()
            .shadow(12.dp, shape, ambientColor = BauColors.Ink.copy(alpha = 0.22f), spotColor = BauColors.Ink.copy(alpha = 0.22f))
            .clip(shape).background(BauColors.Ink)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(overview.periodLabel, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp), color = BauColors.Yellow)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Money("Raised Money", formatUsd(overview.raisedUsd), Color.White, Modifier.weight(1f))
            Box(Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.18f)))
            Money("Wasted Budget", formatUsd(overview.wastedUsd), StaffColors.WastedOnDark, Modifier.weight(1f).padding(start = 16.dp))
        }
        HorizontalDivider(thickness = 1.dp, color = Color.White.copy(alpha = 0.18f))
        Row(Modifier.fillMaxWidth()) {
            Count(overview.openCount, "Open inquiries", Modifier.weight(1f))
            Count(overview.inProgressCount, "In progress", Modifier.weight(1f))
            Count(overview.deliveredCount, "Delivered", Modifier.weight(1f))
        }
    }
}

@Composable
private fun Money(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp), color = StaffColors.OnDarkSecondary)
        Text(value, style = RoundedNumber.copy(fontSize = 32.sp, lineHeight = 38.sp), color = color, maxLines = 1)
    }
}

@Composable
private fun Count(value: Int, label: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value.toString(), style = RoundedNumber.copy(fontSize = 20.sp, lineHeight = 24.sp), color = Color.White)
        Text(label, style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp), color = StaffColors.OnDarkSecondary)
    }
}

@Composable
private fun NeedsAttentionCard(inquiry: StaffInquiry, onOpen: () -> Unit) {
    val blocker = inquiry.progress?.blocker
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(BauColors.WarningSoft)
            .clickable(role = Role.Button, onClickLabel = "Open ${inquiry.title}", onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(StaffIcons.Warning, null, tint = BauColors.WarningInk, modifier = Modifier.size(18.dp))
            Text(
                "Needs your attention", modifier = Modifier.weight(1f).semantics { heading() },
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, lineHeight = 18.sp), color = BauColors.WarningInk,
            )
            Icon(StaffIcons.Chevron, null, tint = BauColors.WarningInk, modifier = Modifier.size(width = 8.dp, height = 14.dp))
        }
        Text(
            inquiry.title,
            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp, letterSpacing = (-0.02).em),
            color = StaffColors.BlockerInk,
        )
        if (blocker != null) {
            Text(blocker.text, style = TextStyle(fontSize = 15.sp, lineHeight = 20.sp), color = StaffColors.BlockerInk, maxLines = 3)
            Text("Raised by ${blocker.raisedBy} · ${blocker.raisedAt}", style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp), color = BauColors.WarningInk)
        }
    }
}

@Composable
private fun RecentInquiries(recent: List<StaffInquiry>, onOpenInquiry: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StaffSectionTitle("Recent Inquiries") {
            BauTextLink("See All", onClick = { StaffNav.selectTab(StaffTab.Inquiries) }, color = BauColors.BlueDeep, fontWeight = FontWeight.Medium)
        }
        Column(Modifier.fillMaxWidth().staffCard()) {
            recent.forEachIndexed { i, inquiry ->
                InquiryRow(
                    inquiry = inquiry,
                    showDivider = i < recent.lastIndex,
                    onClick = { onOpenInquiry(inquiry.id) },
                    subtitle = inquiry.overviewSubtitle,
                    detail = null,
                )
            }
        }
    }
}

@Composable
private fun FirstInquiryCard() {
    Column(
        Modifier.fillMaxWidth().staffCard().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Post your first inquiry",
            modifier = Modifier.semantics { heading() },
            style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 25.sp, letterSpacing = (-0.02).em),
            color = BauColors.Ink,
        )
        Text(
            "Describe something your department needs in plain words. AI turns it into a clear project brief, " +
                "and BAU students build it while you follow every step here.",
            style = TextStyle(fontSize = 15.sp, lineHeight = 20.sp), color = BauColors.TextSecondary,
        )
        BauPrimaryButton(
            "New Inquiry",
            onClick = StaffNav::openNewInquiry,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

@Composable
private fun WorldProjects(projects: List<WorldProject>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            StaffSectionTitle("Projects universities are building")
            Text(
                "Real-world ideas to get you started.",
                modifier = Modifier.padding(horizontal = 4.dp),
                style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp), color = BauColors.TextSecondary,
            )
        }
        projects.forEach { WorldProjectCard(it) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorldProjectCard(project: WorldProject) {
    val uriHandler = LocalUriHandler.current
    val url = project.url
    Column(
        Modifier.fillMaxWidth().staffCard()
            .then(
                if (url != null) Modifier.clickable(role = Role.Button, onClickLabel = "Open ${project.title}") {
                    runCatching { uriHandler.openUri(url) }
                } else Modifier,
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(project.organization, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp), color = BauColors.BlueDeep)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                project.title, modifier = Modifier.weight(1f),
                style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp, letterSpacing = (-0.02).em),
                color = BauColors.Ink,
            )
            if (url != null) Icon(StaffIcons.Chevron, null, tint = BauColors.TextTertiary, modifier = Modifier.size(width = 8.dp, height = 14.dp))
        }
        Text(project.summary, style = TextStyle(fontSize = 15.sp, lineHeight = 20.sp), color = BauColors.TextSecondary)
        if (project.types.isNotEmpty()) {
            FlowRow(
                Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) { ProjectType.entries.filter { it in project.types }.forEach { StaffTag(it.label) } }
        }
    }
}
