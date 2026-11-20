package com.vesper.baubuild.feature.projects

import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.clickable
import com.vesper.baubuild.designsystem.tabBarContentPadding
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.core.model.MembershipStatus
import com.vesper.baubuild.core.model.Project
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.BauPrimaryButton
import com.vesper.baubuild.designsystem.LoadStateContent
import com.vesper.baubuild.designsystem.MonoFamily

/** "Before joining" project screen. */
@Composable
internal fun ProjectDetailScreen(
    state: ProjectDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onJoin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val insets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical).asPaddingValues()
    val density = LocalDensity.current
    // The pinned footer overlays the bottom (with a fade); the scroll content reserves its measured height.
    var footerHeight by remember { mutableStateOf(200.dp) }
    Box(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        when (val load = state.project) {
            is LoadState.Content -> {
                LoadStateContent(load, onRetry = onRetry, modifier = Modifier.fillMaxSize()) { project ->
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(start = 20.dp, end = 20.dp, top = insets.calculateTopPadding() + 10.dp, bottom = footerHeight + 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        ProjectsTopBar(onBack, backLabel = "Back to Projects")
                        DetailBody(project)
                    }
                }
                JoinFooter(
                    project = load.data,
                    state = state,
                    onJoin = onJoin,
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .onSizeChanged { footerHeight = with(density) { it.height.toDp() } },
                )
            }
            else -> Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = insets.calculateTopPadding() + 10.dp)) {
                ProjectsTopBar(onBack, backLabel = "Back to Projects")
                Box(Modifier.weight(1f)) { LoadStateContent(load, onRetry = onRetry) {} }
            }
        }
    }
}

@Composable
private fun DetailBody(project: Project) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "${project.owner} · ${project.difficulty.label}",
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp, color = BauColors.BlueDeep,
        )
        Text(
            project.title, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp, letterSpacing = (-0.56).sp,
            color = BauColors.Ink, modifier = Modifier.semantics { heading() },
        )
    }
    StatsCard(project)
    Text(
        project.description, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.3).sp, color = BauColors.Ink,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    InfoCard(project)
}

@Composable
private fun StatsCard(project: Project) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).projectsCard()) {
        Stat("Bounty", "$${project.bountyUsd}", Modifier.weight(1f))
        VerticalDivider()
        Stat("Difficulty", project.difficulty.label, Modifier.weight(1f))
        VerticalDivider()
        Stat("Due in", project.dueLabel, Modifier.weight(1f))
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.TextSecondary)
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp, color = BauColors.Ink, maxLines = 1)
    }
}

@Composable
private fun VerticalDivider() = Box(Modifier.width(1.dp).fillMaxHeight().background(BauColors.Line))

@Composable
private fun HorizontalRule() = Box(Modifier.fillMaxWidth().height(1.dp).background(BauColors.Line))

@Composable
private fun InfoCard(project: Project) {
    Column(Modifier.fillMaxWidth().projectsCard()) {
        RepoRow(project)
        project.cloneUrl?.let {
            HorizontalRule()
            CloneRow(it)
        }
        project.classCode?.let {
            HorizontalRule()
            InfoRow("Class", it)
        }
        HorizontalRule()
        InfoRow("Min. Score", project.minScoreDetail)
        HorizontalRule()
        InfoRow("Joining", project.joiningDetail)
        HorizontalRule()
        InfoRow("Reviewed By", project.reviewer.label)
    }
}

@Composable
private fun RepoRow(project: Project) {
    val uriHandler = LocalUriHandler.current
    val url = project.repoUrl
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .then(if (url != null) Modifier.clickable(onClickLabel = "Open in GitHub") { runCatching { uriHandler.openUri(url) } } else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(BauColors.BlueDeep), contentAlignment = Alignment.Center) {
            Icon(ProjectsIcons.Branch, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text("GitHub Repo", fontSize = 17.sp, lineHeight = 22.sp, color = BauColors.Ink)
            val repo = project.repoFullName ?: project.repoName
            Text(
                repo ?: "Not set up yet",
                fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.TextSecondary,
                fontFamily = if (repo != null) MonoFamily else null,
            )
        }
        Text(
            when {
                url != null -> "Open in GitHub"
                project.repoConnected -> "Connected"
                else -> "Not connected"
            },
            fontSize = 15.sp, color = if (url != null) BauColors.Blue else BauColors.TextSecondary,
        )
    }
}

/** `git clone` URL, selectable so it can be copied. */
@Composable
private fun CloneRow(cloneUrl: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Clone", fontSize = 16.sp, lineHeight = 21.sp, color = BauColors.Ink)
        SelectionContainer {
            Text(cloneUrl, fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.TextSecondary, fontFamily = MonoFamily)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, fontSize = 16.sp, lineHeight = 21.sp, color = BauColors.Ink, modifier = Modifier.weight(1f))
        Text(value, fontSize = 16.sp, lineHeight = 21.sp, color = BauColors.TextSecondary, textAlign = TextAlign.End)
    }
}

/** Pinned bottom CTA: I'm In / Request, inline progress and error, or the membership result. */
@Composable
private fun JoinFooter(project: Project, state: ProjectDetailUiState, onJoin: () -> Unit, modifier: Modifier = Modifier) {
    val eligibility = state.eligibility ?: JoinEligibility.Eligible
    Column(
        modifier
            .fillMaxWidth()
            // Paper reaches the window bottom (behind the tab bar); the actions end 24dp above the bar's top edge.
            .background(Brush.verticalGradient(listOf(BauColors.Paper.copy(alpha = 0f), BauColors.Paper, BauColors.Paper)))
            .padding(start = 20.dp, end = 20.dp, top = 24.dp)
            .tabBarContentPadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val existing = state.existingMembership
        when (val join = state.join) {
            is JoinState.Joined -> JoinedCard(join.status)
            else -> if (existing != null) JoinedCard(existing) else {
                if (join is JoinState.Failed) {
                    InlineError(join.message, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                BauPrimaryButton(
                    text = when (eligibility) {
                        is JoinEligibility.BelowMinScore -> "Score ${eligibility.required}+ Needed"
                        JoinEligibility.Eligible -> if (join is JoinState.Failed) "Try Again" else project.joinCtaLabel
                    },
                    onClick = onJoin,
                    enabled = state.canJoin,
                    loading = join is JoinState.Joining,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
                )
                Text(
                    project.ctaFootnote(eligibility),
                    fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.TextSecondary,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** "You're in" / "Request sent" confirmation. Also used as the banner on Projects home after joining. */
@Composable
internal fun JoinedCard(status: MembershipStatus, modifier: Modifier = Modifier) {
    val waiting = status == MembershipStatus.WaitingForApproval
    Row(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(if (waiting) BauColors.WarningSoft else BauColors.BlueSoft)
            .padding(16.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (waiting) ProjectsIcons.Clock else ProjectsIcons.Check, contentDescription = null,
            tint = if (waiting) BauColors.WarningInk else BauColors.BlueDeep, modifier = Modifier.size(24.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                status.joinedHeadline(), fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                color = if (waiting) BauColors.WarningInk else BauColors.Ink,
            )
            Text(status.joinedDetail(), fontSize = 14.sp, lineHeight = 19.sp, color = BauColors.TextSecondary)
        }
    }
}
