package com.vesper.baubuild.feature.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.core.model.Difficulty
import com.vesper.baubuild.core.model.Project
import com.vesper.baubuild.core.model.ReviewStageState
import com.vesper.baubuild.core.model.ReviewStatus
import com.vesper.baubuild.designsystem.*
import com.vesper.baubuild.di.AppGraph
import androidx.compose.ui.backhandler.BackHandler

/** Space kept free under scrolling content so the floating (Liquid Glass) tab bar never covers it. */
internal val TabBarClearance = 120.dp

/** Entry point for this feature. Hosted by the Compose tab shell and by the native iOS TabView. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ReviewTabRoot(modifier: Modifier = Modifier) {
    val vm = viewModel { ReviewViewModel(AppGraph.current.workRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.canGoBack) { vm.back() }
    ReviewScreen(state, onRefresh = vm::refresh, onOpen = vm::open, onBack = vm::back, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReviewScreen(
    state: ReviewUiState,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(BauColors.Paper)) {
        LoadStateContent(state.reviews, onRetry = onRefresh, modifier = Modifier.fillMaxSize()) { reviews ->
            PullToRefreshBox(
                isRefreshing = (state.reviews as? LoadState.Content)?.refreshing == true,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                val detail = state.detail
                when {
                    reviews.isEmpty() -> ReviewEmpty()
                    detail != null -> ReviewDetail(detail, showBack = state.canGoBack, onBack = onBack)
                    else -> ReviewList(reviews, onOpen)
                }
            }
        }
    }
}

@Composable
private fun ReviewScaffoldColumn(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = BauSpacing.screen, end = BauSpacing.screen, top = 12.dp, bottom = TabBarClearance),
        verticalArrangement = Arrangement.spacedBy(BauSpacing.lg),
        content = content,
    )
}

@Composable
private fun ReviewEmpty() {
    ReviewScaffoldColumn {
        item { ScreenTitle("Review", modifier = Modifier.semantics { heading() }) }
        item {
            BauCard(Modifier.fillMaxWidth()) {
                Text("Nothing in review yet", style = MaterialTheme.typography.titleMedium, color = BauColors.Ink)
                Spacer(Modifier.height(BauSpacing.xs))
                Text(
                    "When you push a fix from My Work, you can follow the AI review and the dev lead’s decision here.",
                    style = MaterialTheme.typography.bodyMedium, color = BauColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun ReviewList(reviews: List<ReviewStatus>, onOpen: (String) -> Unit) {
    ReviewScaffoldColumn {
        item {
            ScreenTitle(
                "Review",
                subtitle = "${reviews.size} fixes are being reviewed.",
                modifier = Modifier.semantics { heading() },
            )
        }
        items(reviews, key = { it.project.id }) { review ->
            val current = review.stages.firstOrNull { it.state == ReviewStageState.Active }
                ?: review.stages.lastOrNull { it.state == ReviewStageState.Done }
            ProjectSummaryCard(
                review.project,
                caption = current?.title,
                onClick = { onOpen(review.project.id) },
            )
        }
    }
}

@Composable
private fun ReviewDetail(review: ReviewStatus, showBack: Boolean, onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    ReviewScaffoldColumn {
        if (showBack) item { BackCircleButton(onBack) }
        item { ScreenTitle("Review", subtitle = review.headline, modifier = Modifier.semantics { heading() }) }
        item { ProjectSummaryCard(review.project, caption = null, onClick = null) }
        item {
            BauCard(Modifier.fillMaxWidth().cardShadow()) {
                StageTimeline(review.stages.map { stage ->
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
                })
            }
        }
        val diffUrl = review.diffUrl
        if (diffUrl != null) {
            item {
                BauSecondaryButton(
                    "View Diff on GitHub",
                    onClick = { runCatching { uriHandler.openUri(diffUrl) } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                )
            }
        }
    }
}

/** "AI Review Complete" findings box: what passed (blue check) and what is worth a look (orange mark). */
@Composable
private fun AiNotes(passed: String, warning: String) {
    Column(
        Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(BauRadius.lg))
            .background(BauColors.Paper).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (passed.isNotBlank()) NoteLine(passed) { CheckGlyph(Modifier.padding(top = 2.dp).size(16.dp), BauColors.BlueDeep) }
        NoteLine(warning) { WarningGlyph(Modifier.padding(top = 2.dp).size(16.dp), BauColors.Warning) }
    }
}

@Composable
private fun NoteLine(text: String, icon: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        icon()
        Text(text, fontSize = 15.sp, lineHeight = 20.sp, color = BauColors.Ink)
    }
}

/** Project row: title, "Owner · Difficulty", bounty. Optional caption (current stage) for the list. */
@Composable
internal fun ProjectSummaryCard(project: Project, caption: String?, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    BauCard(modifier.fillMaxWidth().heightIn(min = 64.dp).cardShadow(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(project.title, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = BauColors.Ink)
                Text(
                    "${project.owner} · ${project.difficulty.label}",
                    fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.TextSecondary,
                )
                if (caption != null) {
                    Spacer(Modifier.height(4.dp))
                    BauChip(caption)
                }
            }
            Text("\$${project.bountyUsd}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = BauColors.Ink)
            if (onClick != null) ChevronGlyph()
        }
    }
}

internal val Difficulty.label: String get() = when (this) { Difficulty.Easy -> "Easy"; Difficulty.Hard -> "Hard" }

internal fun Modifier.cardShadow(): Modifier =
    shadow(6.dp, RoundedCornerShape(BauRadius.lg), ambientColor = BauColors.Ink.copy(alpha = 0.06f), spotColor = BauColors.Ink.copy(alpha = 0.06f))

/** 44dp glass circle with a back chevron (design's top-left nav button). */
@Composable
internal fun BackCircleButton(onClick: () -> Unit, modifier: Modifier = Modifier, label: String = "Back") {
    Box(
        modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.55f))
            .border(1.dp, Color.White.copy(alpha = 0.85f), CircleShape)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        ChevronGlyph(Modifier.size(width = 8.dp, height = 14.dp).rotate(180f), BauColors.Ink)
    }
}
