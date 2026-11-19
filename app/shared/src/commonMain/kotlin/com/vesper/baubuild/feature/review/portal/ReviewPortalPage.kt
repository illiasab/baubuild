package com.vesper.baubuild.feature.review.portal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.core.dataOrNull
import com.vesper.baubuild.core.model.ReviewStageState
import com.vesper.baubuild.core.model.ReviewStatus
import com.vesper.baubuild.designsystem.BauChip
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.DisplayFamily
import com.vesper.baubuild.designsystem.LoadStateContent
import com.vesper.baubuild.designsystem.MonoFamily
import com.vesper.baubuild.di.AppGraph
import com.vesper.baubuild.feature.review.AiNotes
import com.vesper.baubuild.feature.review.ReviewUiState
import com.vesper.baubuild.feature.review.ReviewViewModel
import com.vesper.baubuild.feature.review.StageTimeline
import com.vesper.baubuild.feature.review.TimelineStep
import com.vesper.baubuild.feature.review.label

/** Desktop page in the Web portal design. Hosted by the portal shell's content area (right of the sidebar). */
@Composable
fun ReviewPortalPage(modifier: Modifier = Modifier) {
    val vm = viewModel { ReviewViewModel(AppGraph.current.workRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    ReviewPortalContent(state, onRefresh = vm::refresh, onSelect = vm::open, modifier = modifier)
}

/**
 * The submission shown on the right. Unlike mobile (list → pushed detail) the portal always shows a detail:
 * the one picked in the list, otherwise the first submission.
 */
internal fun ReviewUiState.portalSelection(): ReviewStatus? =
    detail ?: reviews.dataOrNull?.firstOrNull()

/** Stage chip text for a list row: the active stage, else the last finished one. */
internal fun ReviewStatus.currentStageTitle(): String? =
    (stages.firstOrNull { it.state == ReviewStageState.Active } ?: stages.lastOrNull { it.state == ReviewStageState.Done })?.title

/** Below this content width the list stacks above the detail. */
private val TwoPaneMinWidth = 760.dp
private val ListPaneWidth = 360.dp

@Composable
internal fun ReviewPortalContent(
    state: ReviewUiState,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reviews = state.reviews.dataOrNull
    Column(modifier.fillMaxSize().background(BauColors.Paper)) {
        PortalPageHeader(
            "Review",
            subtitle = when {
                reviews == null -> null
                reviews.isEmpty() -> "Follow the AI review and the dev lead’s decision on your fixes."
                reviews.size == 1 -> "1 fix is being reviewed."
                else -> "${reviews.size} fixes are being reviewed."
            },
        ) {
            val refreshing = (state.reviews as? LoadState.Content)?.refreshing == true
            if (reviews != null) PortalButton(if (refreshing) "Refreshing…" else "Refresh", onClick = onRefresh, enabled = !refreshing)
        }
        LoadStateContent(state.reviews, onRetry = onRefresh, modifier = Modifier.fillMaxSize()) { list ->
            val selected = state.portalSelection()
            BoxWithConstraints(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(start = PortalTokens.PagePadding, end = PortalTokens.PagePadding, bottom = PortalTokens.PagePadding),
            ) {
                when {
                    list.isEmpty() || selected == null -> ReviewEmptyCard()
                    maxWidth >= TwoPaneMinWidth -> Row(
                        horizontalArrangement = Arrangement.spacedBy(PortalTokens.Gap),
                        verticalAlignment = Alignment.Top,
                    ) {
                        SubmissionList(list, selected.project.id, onSelect, Modifier.width(ListPaneWidth))
                        ReviewDetailPane(selected, Modifier.weight(1f))
                    }
                    else -> Column(verticalArrangement = Arrangement.spacedBy(PortalTokens.Gap)) {
                        SubmissionList(list, selected.project.id, onSelect, Modifier.fillMaxWidth())
                        ReviewDetailPane(selected, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewEmptyCard() {
    PortalCard(Modifier.fillMaxWidth().widthIn(max = 640.dp), gap = 8.dp) {
        PortalSectionTitle("Nothing in review yet")
        Text(
            "When you push a fix from My Work, you can follow the AI review and the dev lead’s decision here.",
            fontSize = 15.sp, lineHeight = 22.sp, color = BauColors.TextSecondary,
        )
    }
}

@Composable
private fun SubmissionList(
    reviews: List<ReviewStatus>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier,
) {
    PortalCard(modifier, padding = 0.dp) {
        PortalCardStrip("YOUR SUBMISSIONS")
        reviews.forEachIndexed { i, review ->
            SubmissionRow(review, selected = review.project.id == selectedId, onClick = { onSelect(review.project.id) })
            if (i < reviews.lastIndex) HorizontalDivider(color = PortalTokens.Divider, thickness = 1.dp)
        }
    }
}

@Composable
private fun SubmissionRow(review: ReviewStatus, selected: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val project = review.project
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min)
            .background(
                when {
                    selected -> BauColors.Paper
                    hovered -> PortalTokens.RowHover
                    else -> Color.Transparent
                },
            )
            .clickable(interactionSource = source, indication = null, role = Role.Tab, onClickLabel = "Open review", onClick = onClick)
            .pointerHand()
            .semantics(mergeDescendants = true) { this.selected = selected },
    ) {
        // Selected accent bar (yellow, like the active item in the portal sidebar).
        Box(Modifier.width(3.dp).fillMaxHeight().background(if (selected) BauColors.Yellow else Color.Transparent))
        Row(
            Modifier.weight(1f).padding(start = 17.dp, end = 20.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    project.title, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = BauColors.Ink,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text("${project.owner} · ${project.difficulty.label}", fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.TextSecondary)
                review.currentStageTitle()?.let {
                    Spacer(Modifier.height(4.dp))
                    BauChip(it)
                }
            }
            Text("\$${project.bountyUsd}", fontFamily = DisplayFamily, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = BauColors.Ink)
        }
    }
}

@Composable
private fun ReviewDetailPane(review: ReviewStatus, modifier: Modifier) {
    val uriHandler = LocalUriHandler.current
    val project = review.project
    Column(modifier, verticalArrangement = Arrangement.spacedBy(PortalTokens.Gap)) {
        PortalCard(Modifier.fillMaxWidth(), gap = 16.dp) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PortalSectionTitle(project.title)
                    Text("${project.owner} · ${project.difficulty.label}", fontSize = 15.sp, lineHeight = 22.sp, color = BauColors.TextSecondary)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Bounty", fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.TextSecondary)
                    Text("\$${project.bountyUsd}", fontFamily = DisplayFamily, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, color = BauColors.Ink)
                }
            }
            Text(review.headline, fontSize = 15.sp, lineHeight = 22.sp, color = BauColors.Ink)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    review.branch,
                    modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(6.dp)).background(BauColors.Paper)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    fontFamily = MonoFamily, fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.TextSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                review.diffUrl?.let { url ->
                    PortalButton("View diff on GitHub", onClick = { runCatching { uriHandler.openUri(url) } })
                }
            }
        }
        PortalCard(Modifier.fillMaxWidth(), padding = 0.dp) {
            PortalCardStrip("REVIEW STAGES")
            StageTimeline(
                review.stages.map { stage ->
                    TimelineStep(stage.title, stage.state) {
                        when {
                            stage.note != null -> AiNotes(passed = stage.detail, warning = stage.note)
                            stage.detail == review.branch -> Text(
                                stage.detail, fontSize = 13.sp, lineHeight = 18.sp,
                                fontFamily = MonoFamily, color = BauColors.TextSecondary,
                            )
                            stage.detail.isNotBlank() -> Text(
                                stage.detail, fontSize = 15.sp, lineHeight = 20.sp,
                                color = if (stage.state == ReviewStageState.Pending) BauColors.TextSecondary else BauColors.Ink,
                            )
                        }
                    }
                },
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}
