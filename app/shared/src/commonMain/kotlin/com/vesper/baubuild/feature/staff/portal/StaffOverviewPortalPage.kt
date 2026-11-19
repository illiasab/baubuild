package com.vesper.baubuild.feature.staff.portal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vesper.baubuild.core.dataOrNull
import com.vesper.baubuild.core.model.ProjectType
import com.vesper.baubuild.core.model.StaffInquiry
import com.vesper.baubuild.core.model.StaffOverview
import com.vesper.baubuild.core.model.WorldProject
import com.vesper.baubuild.core.session.SessionState
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.DisplayFamily
import com.vesper.baubuild.designsystem.LoadStateContent
import com.vesper.baubuild.di.AppGraph
import com.vesper.baubuild.feature.staff.StaffNav
import com.vesper.baubuild.feature.staff.StaffTab
import com.vesper.baubuild.feature.staff.inquiries.components.StaffColors
import com.vesper.baubuild.feature.staff.inquiries.components.StaffIcons
import com.vesper.baubuild.feature.staff.inquiries.components.StaffTag
import com.vesper.baubuild.feature.staff.inquiries.formatUsd
import com.vesper.baubuild.feature.staff.overview.StaffOverviewContent
import com.vesper.baubuild.feature.staff.overview.StaffOverviewViewModel

/**
 * Desktop staff Overview (Web portal design). Hosted by the staff portal shell's content area.
 *
 * Stat cards, "Needs your attention" and "Recent inquiries"; an account without inquiries gets a
 * "Post your first inquiry" card and real-world project ideas instead of the table. Opening an inquiry
 * shows its detail on the Inquiries page ([StaffPortalNav.openInquiry]); "See all" switches to Inquiries.
 */
@Composable
fun StaffOverviewPortalPage(modifier: Modifier = Modifier) {
    val viewModel = viewModel(key = "portal/staff/overview") {
        val graph = AppGraph.current
        StaffOverviewViewModel(graph.staffRepository) { (graph.session.state.value as? SessionState.SignedIn)?.user }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize().background(BauColors.Paper)) {
        StaffPortalHeader(
            title = "Overview",
            eyebrow = state.content.dataOrNull?.greeting,
            refreshing = state.isRefreshing,
            onRefresh = { viewModel.refresh() },
        )
        LoadStateContent(state.content, onRetry = { viewModel.refresh() }, modifier = Modifier.fillMaxWidth().weight(1f)) { content ->
            OverviewBody(content)
        }
    }
}

@Composable
private fun OverviewBody(content: StaffOverviewContent) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = StaffPortalGutter, end = StaffPortalGutter, top = 4.dp, bottom = StaffPortalGutter),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        StatCards(content.overview)
        content.overview.needsAttention?.let { AttentionBanner(it) }
        if (content.hasInquiries) {
            RecentInquiriesCard(content.recent)
        } else {
            FirstInquiryCard()
            if (content.worldProjects.isNotEmpty()) WorldProjects(content.worldProjects)
        }
    }
}

@Composable
private fun StatCards(overview: StaffOverview) {
    val cards = listOf(
        Triple("Raised money · this semester", formatUsd(overview.raisedUsd), BauColors.Ink),
        Triple("Wasted budget · this semester", formatUsd(overview.wastedUsd), if (overview.wastedUsd > 0) BauColors.Warning else BauColors.Ink),
        Triple("Open inquiries", overview.portalOpenCount.toString(), BauColors.Ink),
        Triple("Delivered", overview.deliveredCount.toString(), BauColors.Ink),
    )
    StaffPortalAutoGrid(cards, minCellWidth = 200.dp, gap = 16.dp) { (label, value, color), cellModifier ->
        StaffPortalStatCard(label, value, cellModifier, valueColor = color)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttentionBanner(inquiry: StaffInquiry) {
    val blocker = inquiry.progress?.blocker
    FlowRow(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(BauColors.WarningSoft)
            .padding(horizontal = 24.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(StaffIcons.Warning, null, tint = BauColors.WarningInk, modifier = Modifier.size(18.dp))
            Text(
                "Needs your attention", modifier = Modifier.semantics { heading() },
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp), color = BauColors.WarningInk,
            )
        }
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inquiry.title) }
                if (blocker != null) append(" is blocked: ${blocker.text.replaceFirstChar { it.lowercase() }}")
                else append(" needs a decision from you.")
            },
            modifier = Modifier.weight(1f).widthIn(min = 320.dp),
            style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
            color = StaffColors.BlockerInk,
        )
        StaffPortalButton("Open inquiry", onClick = { StaffPortalNav.openInquiry(inquiry.id) }, style = StaffPortalButtonStyle.Dark)
    }
}

private val RecentColumns = listOf(
    StaffInquiryColumn("INQUIRY", 2.4f) { StaffInquiryCells.InquiryTitle(it) },
    StaffInquiryColumn("PROJECT FOR", 1.6f) { StaffInquiryCells.Secondary(it.portalProjectFor) },
    StaffInquiryColumn("STATUS", 1.2f) { StaffInquiryCells.Status(it) },
    StaffInquiryColumn("TODAY", 2.0f) { StaffInquiryCells.Latest(it) },
)

@Composable
private fun RecentInquiriesCard(recent: List<StaffInquiry>) {
    Column(Modifier.fillMaxWidth().staffPortalCard()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Recent inquiries", modifier = Modifier.weight(1f).semantics { heading() },
                style = TextStyle(fontFamily = DisplayFamily, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp),
                color = BauColors.Ink,
            )
            StaffPortalLink("See all", onClick = { StaffNav.selectTab(StaffTab.Inquiries) })
        }
        StaffInquiryTable(
            inquiries = recent,
            columns = RecentColumns,
            minWidth = 760.dp,
            onOpen = { StaffPortalNav.openInquiry(it.id) },
        )
    }
}

@Composable
private fun FirstInquiryCard() {
    Column(
        Modifier.fillMaxWidth().staffPortalCard().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Post your first inquiry", modifier = Modifier.semantics { heading() },
            style = TextStyle(fontFamily = DisplayFamily, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp),
            color = BauColors.Ink,
        )
        Text(
            "Describe something your department needs in plain words. AI turns it into a clear project brief, " +
                "and BAU students build it while you follow every step here.",
            modifier = Modifier.widthIn(max = 640.dp),
            style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp), color = BauColors.TextSecondary,
        )
        Row(Modifier.padding(top = 8.dp)) {
            StaffPortalButton("New inquiry", onClick = StaffNav::openNewInquiry, style = StaffPortalButtonStyle.Primary)
        }
    }
}

@Composable
private fun WorldProjects(projects: List<WorldProject>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Projects universities are building", modifier = Modifier.semantics { heading() },
                style = TextStyle(fontFamily = DisplayFamily, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp),
                color = BauColors.Ink,
            )
            Text("Real-world ideas to get you started.", style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp), color = BauColors.TextSecondary)
        }
        StaffPortalAutoGrid(projects, minCellWidth = 280.dp, gap = 16.dp) { project, cellModifier ->
            WorldProjectCard(project, cellModifier)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorldProjectCard(project: WorldProject, modifier: Modifier) {
    val uriHandler = LocalUriHandler.current
    val url = project.url
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    Column(
        modifier.staffPortalCard()
            .then(
                if (url != null) Modifier
                    .then(if (hovered || focused) Modifier.border(1.dp, if (focused) BauColors.Blue else BauColors.TextTertiary, RoundedCornerShape(12.dp)) else Modifier)
                    .hoverable(source)
                    .clickable(source, indication = null, role = Role.Button, onClickLabel = "Open ${project.title}") {
                        runCatching { uriHandler.openUri(url) }
                    }
                    .pointerHoverIcon(PointerIcon.Hand)
                else Modifier,
            )
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(project.organization, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp), color = BauColors.BlueDeep)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                project.title, modifier = Modifier.weight(1f),
                style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp), color = BauColors.Ink,
            )
            if (url != null) Icon(StaffIcons.Chevron, null, tint = BauColors.TextTertiary, modifier = Modifier.size(width = 8.dp, height = 14.dp))
        }
        Text(project.summary, style = TextStyle(fontSize = 15.sp, lineHeight = 21.sp), color = BauColors.TextSecondary)
        if (project.types.isNotEmpty()) {
            FlowRow(
                Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) { ProjectType.entries.filter { it in project.types }.forEach { StaffTag(it.label) } }
        }
    }
}
