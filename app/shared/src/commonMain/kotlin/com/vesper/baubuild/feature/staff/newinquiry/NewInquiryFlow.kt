package com.vesper.baubuild.feature.staff.newinquiry

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.BauSpacing
import com.vesper.baubuild.designsystem.BauTextLink
import com.vesper.baubuild.di.AppGraph
import com.vesper.baubuild.feature.auth.rememberPrefersReducedMotion
import kotlinx.coroutines.delay
import kotlin.random.Random

/** What the flow currently shows (the steps plus the two transient screens). */
private enum class Screen { Details, Reward, Refining, Ready, Posted }

private val NewInquiryUiState.screen: Screen
    get() = when {
        posted -> Screen.Posted
        refining -> Screen.Refining
        step == NewInquiryStep.Details -> Screen.Details
        step == NewInquiryStep.RewardTiming -> Screen.Reward
        else -> Screen.Ready
    }

/** How long "Posted!" stays up before the flow closes itself. */
private const val POSTED_CONFIRMATION_MS = 1_600L

/**
 * Staff mobile New Inquiry flow, presented full-screen / modally by the staff shells from the yellow "+".
 * Step 1 (details) → step 2 (reward & timing) → AI refining → Ready to Post → Posted!. Owns its step navigation and
 * system back. [onClose] is called after discarding / saving a draft / posting (after a short confirmation).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NewInquiryRoot(onClose: () -> Unit, modifier: Modifier = Modifier) {
    // A fresh form per presentation; survives rotation (same key → same ViewModel) and process death (snapshot).
    val instanceKey = rememberSaveable { Random.nextLong().toString() }
    var snapshot by rememberSaveable { mutableStateOf<String?>(null) }
    val vm = rememberNewInquiryViewModel(instanceKey, restored = snapshot)
    LaunchedEffect(vm) { vm.snapshots.collect { snapshot = it } }

    val state by vm.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current

    BackHandler(enabled = !state.closed && !state.posted) {
        when {
            state.refining -> vm.cancelRefine()
            state.step == NewInquiryStep.Ready -> vm.backFromReady()
            state.step == NewInquiryStep.RewardTiming -> vm.backToDetails()
            else -> vm.requestClose()
        }
    }
    LaunchedEffect(state.closed) { if (state.closed) onClose() }
    LaunchedEffect(state.posted) {
        if (state.posted) {
            delay(POSTED_CONFIRMATION_MS)
            onClose()
        }
    }
    LaunchedEffect(state.refining) { if (state.refining) focus.clearFocus() }

    val reduced = rememberPrefersReducedMotion()
    Box(modifier.fillMaxSize().background(BauColors.Paper)) {
        AnimatedContent(
            targetState = state.screen,
            transitionSpec = { screenTransition(initialState, targetState, reduced) },
            label = "newInquiryScreen",
        ) { screen ->
            when (screen) {
                Screen.Details -> DetailsStep(state, vm)
                Screen.Reward -> RewardStep(state, vm)
                Screen.Refining -> RefiningScreen(onCancel = vm::cancelRefine)
                Screen.Ready -> ReadyStep(state, vm)
                Screen.Posted -> PostedScreen()
            }
        }
    }

    if (state.showDiscardConfirm) {
        DiscardInquiryDialog(
            saving = state.savingDraft, error = state.saveDraftError,
            onSaveDraft = vm::saveDraftAndClose, onDiscard = vm::discard, onKeepEditing = vm::keepEditing,
        )
    }
}

/** The flow's ViewModel for [key] (one per presentation). The desktop portal page uses the same one. */
@Composable
fun rememberNewInquiryViewModel(key: Any, restored: String? = null): NewInquiryViewModel =
    viewModel(key = "NewInquiry#$key") {
        NewInquiryViewModel(AppGraph.current.staffRepository, restored?.let(NewInquirySnapshot::decode))
    }

private fun screenTransition(from: Screen, to: Screen, reduced: Boolean): ContentTransform {
    if (reduced) return fadeIn() togetherWith fadeOut()
    val steps = listOf(Screen.Details, Screen.Reward, Screen.Ready)
    if (from in steps && to in steps) {
        val forward = steps.indexOf(to) > steps.indexOf(from)
        return (slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn()) togetherWith
            (slideOutHorizontally { if (forward) -it / 3 else it / 3 } + fadeOut())
    }
    val enter: EnterTransition = if (to == Screen.Posted) fadeIn() + scaleIn(initialScale = 0.96f) else fadeIn()
    val exit: ExitTransition = fadeOut()
    return enter togetherWith exit
}

// ---------------------------------------------------------------- screens

@Composable
private fun DetailsStep(state: NewInquiryUiState, vm: NewInquiryViewModel) {
    StepScaffold(
        leading = { GlassIconButton(CLOSE, "Close", onClick = vm::requestClose, enabled = !state.busy) },
        trailing = { StepIndicator(1) },
        title = "New Inquiry",
        subtitle = "Describe the work. AI tidies the wording before it is posted.",
        bottom = {
            FlowPrimaryButton(
                "Continue", onClick = { vm.continueToReward() }, enabled = state.isDetailsValid,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        InquiryDetailsFields(state, vm)
    }
}

@Composable
private fun RewardStep(state: NewInquiryUiState, vm: NewInquiryViewModel) {
    StepScaffold(
        leading = { GlassIconButton(BACK, "Back to step 1", onClick = vm::backToDetails, enabled = !state.busy) },
        trailing = { StepIndicator(2) },
        title = "Reward & Timing",
        bottom = {
            state.refineError?.let { InlineError(it, actionLabel = "Retry", onAction = vm::refine) }
            FlowPrimaryButton(
                "Refine with AI", onClick = vm::refine, sparkle = true, loading = state.refining,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        RewardTimingFields(state, vm)
    }
}

@Composable
private fun ReadyStep(state: NewInquiryUiState, vm: NewInquiryViewModel) {
    val refined = state.refined ?: return
    val draft = state.refinedFor ?: state.draft
    StepScaffold(
        leading = { GlassIconButton(BACK, "Back to step 2", onClick = vm::backFromReady, enabled = !state.busy) },
        trailing = { GlassPillButton("Edit", onClick = vm::editFromReady, enabled = !state.busy) },
        title = "Ready to Post",
        subtitle = state.readySubtitle,
        bottom = {
            state.postError?.let { InlineError(it, actionLabel = "Retry", onAction = vm::post) }
            state.refineError?.let { InlineError(it, actionLabel = "Retry", onAction = vm::refineAgain) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowSecondaryButton("Refine Again", onClick = vm::refineAgain, enabled = !state.busy, modifier = Modifier.weight(1f))
                FlowPrimaryButton("Post Project", onClick = vm::post, loading = state.posting, modifier = Modifier.weight(1.15f))
            }
        },
    ) {
        RefinedInquiryCard(refined, draft)
        WhatAiChangedNote(refined.whatAiChanged)
        WhatYouWroteCard(draft)
        state.notifiesOnDone?.let { NotifyOnDoneNote(it) }
    }
}

@Composable
private fun RefiningScreen(onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = BauSpacing.screen), contentAlignment = Alignment.Center) {
            AiRefiningIndicator(Modifier.fillMaxWidth())
        }
        Box(Modifier.fillMaxWidth().padding(bottom = modalBottomPadding()), contentAlignment = Alignment.Center) {
            BauTextLink(
                "Cancel", onClick = onCancel, color = BauColors.TextSecondary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
private fun PostedScreen() {
    Column(
        Modifier.fillMaxSize().padding(horizontal = BauSpacing.screen).semantics { liveRegion = LiveRegionMode.Assertive },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(96.dp).clip(CircleShape).background(BauColors.Yellow), contentAlignment = Alignment.Center) {
            PathIcon(CHECK_BIG, 48.dp, BauColors.Ink, 2.2f)
        }
        Text(
            "Posted!", Modifier.semantics { heading() }, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold,
            color = BauColors.Ink, textAlign = TextAlign.Center,
        )
        Text("Students can join now.", fontSize = 17.sp, lineHeight = 22.sp, color = BauColors.TextSecondary, textAlign = TextAlign.Center)
    }
}

// ---------------------------------------------------------------- scaffolding

/**
 * Header (leading button + trailing indicator), large title, scrolling content and a pinned bottom action area.
 * In a modal there is no tab bar: the pinned area ends 24dp above the safe area — or above the keyboard while
 * typing (the keyboard is part of safeDrawing, so no separate imePadding).
 */
@Composable
private fun StepScaffold(
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    title: String,
    subtitle: String? = null,
    bottom: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = BauSpacing.screen, end = BauSpacing.screen, top = 10.dp, bottom = BauSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                leading()
                Spacer(Modifier.weight(1f))
                trailing()
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    title, Modifier.semantics { heading() },
                    fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.68).sp, color = BauColors.Ink,
                )
                subtitle?.let { Text(it, fontSize = 15.sp, lineHeight = 20.sp, color = BauColors.TextSecondary) }
            }
            content()
        }
        Column(
            Modifier.fillMaxWidth().background(BauColors.Paper)
                .padding(start = BauSpacing.screen, end = BauSpacing.screen, top = BauSpacing.md, bottom = modalBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = bottom,
        )
    }
}

/** Safe-area bottom (or keyboard) + 24dp — the modal equivalent of `bottomContentPadding()` (no tab bar here). */
@Composable
private fun modalBottomPadding(extra: Dp = 24.dp): Dp {
    val density = LocalDensity.current
    return with(density) { WindowInsets.safeDrawing.getBottom(density).toDp() } + extra
}

@Composable
private fun InlineError(message: String, actionLabel: String, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(message, Modifier.weight(1f), fontSize = 14.sp, lineHeight = 19.sp, color = BauColors.Danger)
        BauTextLink(actionLabel, onClick = onAction, color = BauColors.BlueDeep, fontSize = 15.sp)
    }
}

@Composable
private fun DiscardInquiryDialog(
    saving: Boolean,
    error: String?,
    onSaveDraft: () -> Unit,
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onKeepEditing() },
        containerColor = BauColors.Surface,
        title = { Text("Discard this inquiry?", fontWeight = FontWeight.Bold, color = BauColors.Ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Save it as a draft to finish later, or discard what you wrote.", color = BauColors.TextSecondary)
                error?.let { Text(it, color = BauColors.Danger, fontSize = 14.sp) }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BauTextLink("Discard", onClick = onDiscard, enabled = !saving, color = BauColors.Danger, fontWeight = FontWeight.Medium)
                if (saving) {
                    Box(Modifier.heightIn(min = 44.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = BauColors.Ink, strokeWidth = 2.dp)
                    }
                } else {
                    BauTextLink("Save Draft", onClick = onSaveDraft, color = BauColors.BlueDeep)
                }
            }
        },
        dismissButton = {
            BauTextLink("Keep Editing", onClick = onKeepEditing, enabled = !saving, color = BauColors.Ink, fontWeight = FontWeight.Medium)
        },
    )
}
