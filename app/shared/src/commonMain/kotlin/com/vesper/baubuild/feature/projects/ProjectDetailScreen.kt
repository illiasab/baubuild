package com.vesper.baubuild.feature.projects

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
    Box(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        when (val load = state.project) {
            is LoadState.Content -> {
                LoadStateContent(load, onRetry = onRetry, modifier = Modifier.fillMaxSize()) { project ->
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(start = 20.dp, end = 20.dp, top = insets.calculateTopPadding() + 10.dp, bottom = 200.dp),
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
                        .padding(bottom = insets.calculateBottomPadding()),
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
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(BauColors.BlueDeep), contentAlignment = Alignment.Center) {
            Icon(ProjectsIcons.Branch, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text("GitHub Repo", fontSize = 17.sp, lineHeight = 22.sp, color = BauColors.Ink)
            Text(
                project.repoName ?: "Not set up yet",
                fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.TextSecondary,
                fontFamily = if (project.repoName != null) MonoFamily else null,
            )
        }
        Text(
            if (project.repoConnected) "Connected" else "Not connected",
            fontSize = 15.sp, color = BauColors.TextSecondary,
        )
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
            .background(Brush.verticalGradient(listOf(BauColors.Paper.copy(alpha = 0f), BauColors.Paper, BauColors.Paper)))
            .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (val join = state.join) {
            is JoinState.Joined -> JoinedCard(join.status)
            else -> {
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

@Composable
private fun JoinedCard(status: MembershipStatus) {
    val waiting = status == MembershipStatus.WaitingForApproval
    Row(
        Modifier.fillMaxWidth()
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
