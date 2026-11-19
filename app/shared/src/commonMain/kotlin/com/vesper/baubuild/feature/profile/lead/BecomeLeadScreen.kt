package com.vesper.baubuild.feature.profile.lead

import com.vesper.baubuild.designsystem.tabBarContentPadding
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vesper.baubuild.core.model.ReviewStageState
import com.vesper.baubuild.designsystem.BauCard
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.BauPrimaryButton
import com.vesper.baubuild.designsystem.BauSpacing
import com.vesper.baubuild.feature.review.StageTimeline
import com.vesper.baubuild.feature.review.TimelineStep
import com.vesper.baubuild.feature.review.cardShadow

/** "Become a Lead" — pushed from Profile. Back is handled by the Profile root (BackHandler + close button). */
@Composable
fun BecomeLeadScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val vm = viewModel { BecomeLeadViewModel() }
    val state by vm.state.collectAsStateWithLifecycle()
    BecomeLeadContent(state, onClose = onClose, onStartExam = vm::startExam, modifier = modifier)
}

@Composable
internal fun BecomeLeadContent(
    state: BecomeLeadUiState,
    onClose: () -> Unit,
    onStartExam: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().background(BauColors.Paper)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = BauSpacing.screen, end = BauSpacing.screen, top = 12.dp, bottom = BauSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            CloseButton(onClose)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Become a Lead", modifier = Modifier.semantics { heading() },
                    fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.68).sp, color = BauColors.Ink,
                )
                Text(
                    "Leads review student code and decide what gets approved. Two stages stand between you and the role.",
                    fontSize = 17.sp, lineHeight = 24.sp, color = BauColors.TextSecondary,
                )
            }
            BauCard(Modifier.fillMaxWidth().cardShadow()) {
                StageTimeline(state.stages.map { stage ->
                    TimelineStep(stage.title, stage.state, dimTitleWhenPending = false) {
                        Text(
                            stage.detail, fontSize = 15.sp, lineHeight = 20.sp,
                            color = if (stage.state == ReviewStageState.Pending) BauColors.TextSecondary else BauColors.Ink,
                        )
                    }
                })
            }
        }
        // Pinned CTA on Paper, 24dp above the tab bar.
        Column(
            Modifier.fillMaxWidth().background(BauColors.Paper)
                .padding(start = BauSpacing.screen, end = BauSpacing.screen, top = BauSpacing.md)
                .tabBarContentPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BauPrimaryButton("Start Proctored Exam", onClick = onStartExam, modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp))
            Text(
                "You stay a student builder until both stages are passed.",
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.TextSecondary,
            )
            state.notice?.let {
                Text(
                    it, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                    fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.BlueDeep,
                )
            }
        }
    }
}

@Composable
private fun CloseButton(onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.55f))
            .border(1.dp, Color.White.copy(alpha = 0.85f), CircleShape)
            .clickable(onClickLabel = "Close", role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Close" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val s = size.width / 20f
            val w = 2.2f * s
            drawLine(BauColors.Ink, Offset(5f * s, 5f * s), Offset(15f * s, 15f * s), w, StrokeCap.Round)
            drawLine(BauColors.Ink, Offset(15f * s, 5f * s), Offset(5f * s, 15f * s), w, StrokeCap.Round)
        }
    }
}
