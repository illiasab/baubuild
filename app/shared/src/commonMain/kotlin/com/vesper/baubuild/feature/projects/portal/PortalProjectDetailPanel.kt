package com.vesper.baubuild.feature.projects.portal

import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.clickable
import com.vesper.baubuild.feature.review.portal.pointerHand
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vesper.baubuild.core.dataOrNull
import com.vesper.baubuild.core.model.JoinPolicy
import com.vesper.baubuild.core.model.MembershipStatus
import com.vesper.baubuild.core.model.Project
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.DisplayFamily
import com.vesper.baubuild.designsystem.LoadStateContent
import com.vesper.baubuild.designsystem.MonoFamily
import com.vesper.baubuild.feature.projects.InlineError
import com.vesper.baubuild.feature.projects.JoinEligibility
import com.vesper.baubuild.feature.projects.JoinState
import com.vesper.baubuild.feature.projects.ProjectDetailUiState
import com.vesper.baubuild.feature.projects.ctaFootnote
import com.vesper.baubuild.feature.projects.dueLabel
import com.vesper.baubuild.feature.projects.joinCtaLabel
import com.vesper.baubuild.feature.projects.label
import com.vesper.baubuild.feature.projects.minScoreDetail
import com.vesper.baubuild.feature.projects.scoreChipLabel

/**
 * Right-hand project detail panel (portal style). Pure UI over [ProjectDetailUiState] from the shared
 * ProjectDetailViewModel: score gating, inline join progress and inline error; content stays visible on failure.
 */
@Composable
internal fun PortalProjectDetailPanel(
    state: ProjectDetailUiState,
    urgent: Boolean,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onJoin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(BauColors.Surface)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 28.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonoCapsLabel(
                listOfNotNull("Urgent".takeIf { urgent }, state.project.dataOrNull?.owner).joinToString(" · ").ifEmpty { "Project" },
                Modifier.weight(1f),
                color = if (urgent) BauColors.Warning else BauColors.TextSecondary,
            )
            PortalIconButton(PortalIcons.Close, "Close project details", onClose)
        }
        LoadStateContent(state.project, onRetry = onRetry, modifier = Modifier.weight(1f)) { project ->
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) { DetailBody(project) }
                JoinFooter(project, state, onJoin)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailBody(project: Project) {
    Text(
        project.title, Modifier.semantics { heading() },
        fontFamily = DisplayFamily, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 31.sp, color = BauColors.Ink,
    )
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$${project.bountyUsd}", fontFamily = DisplayFamily, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = BauColors.Ink)
        Text("bounty", fontSize = 14.sp, color = BauColors.TextSecondary, modifier = Modifier.padding(bottom = 6.dp))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MetaChip(project.difficulty.label)
        MetaChip(project.scoreChipLabel)
        MetaChip(portalDueChip(project.dueInDays))
    }
    Text(project.description.ifBlank { project.summary }, fontSize = 15.sp, lineHeight = 23.sp, color = BauColors.Ink)

    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(BauColors.Paper).border(1.dp, PortalColors.ChipBorder, shape)) {
        val rows = buildList {
            add("Minimum score" to project.minScoreDetail)
            add("Joining" to project.portalJoiningLabel)
            add("Reviewed by" to project.reviewer.label)
            add("Due" to project.dueLabel)
            if (project.taskCount > 0) add("Tasks" to project.taskCount.toString())
            (project.repoFullName ?: project.repoName)?.let { add("Repository" to if (project.repoConnected) "$it · connected" else "$it · not connected") }
            project.classCode?.let { add("Class" to it) }
            project.cloneUrl?.let { add("Clone" to it) }
        }
        rows.forEachIndexed { i, (label, value) ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(PortalColors.ChipBorder))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label, Modifier.weight(1f), fontSize = 14.sp, color = BauColors.TextSecondary)
                SelectionContainer {
                    Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = BauColors.Ink,
                        fontFamily = if (label == "Repository" || label == "Clone") MonoFamily else null)
                }
            }
        }
    }
    project.repoUrl?.let { url ->
        val uriHandler = LocalUriHandler.current
        Text(
            "Open in GitHub",
            modifier = Modifier.clickable { runCatching { uriHandler.openUri(url) } }.pointerHand(),
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = BauColors.Blue,
        )
    }
}

@Composable
private fun JoinFooter(project: Project, state: ProjectDetailUiState, onJoin: () -> Unit) {
    val eligibility = state.eligibility ?: JoinEligibility.Eligible
    Column(
        Modifier.fillMaxWidth().background(BauColors.Surface).padding(horizontal = 28.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(PortalColors.ChipBorder))
        val join = state.join
        if (join is JoinState.Failed) {
            InlineError(join.message, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        val existing = state.existingMembership
        PortalButton(
            text = when {
                existing == MembershipStatus.In -> "You’re in"
                existing != null -> "Request sent"
                eligibility is JoinEligibility.BelowMinScore -> "Needs score ${eligibility.required}+"
                join is JoinState.Failed -> "Try Again"
                else -> project.joinCtaLabel
            },
            onClick = onJoin,
            primary = existing == null && (project.joinPolicy == JoinPolicy.Open || join is JoinState.Failed),
            enabled = state.canJoin || join is JoinState.Joining,
            loading = join is JoinState.Joining,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        )
        Text(
            project.ctaFootnote(eligibility), fontSize = 13.sp, lineHeight = 18.sp,
            color = if (eligibility is JoinEligibility.BelowMinScore) BauColors.WarningInk else BauColors.TextSecondary,
        )
    }
}
