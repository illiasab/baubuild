package com.vesper.baubuild.feature.staff.portal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.BauTextLink
import com.vesper.baubuild.feature.review.portal.PortalButton
import com.vesper.baubuild.feature.review.portal.PortalButtonStyle
import com.vesper.baubuild.feature.review.portal.PortalCard
import com.vesper.baubuild.feature.review.portal.PortalPageHeader
import com.vesper.baubuild.feature.review.portal.PortalSectionTitle
import com.vesper.baubuild.feature.review.portal.PortalTokens
import com.vesper.baubuild.feature.review.portal.pointerHand
import com.vesper.baubuild.feature.staff.newinquiry.AiRefiningIndicator
import com.vesper.baubuild.feature.staff.newinquiry.InquiryDetailsFields
import com.vesper.baubuild.feature.staff.newinquiry.NewInquiryStep
import com.vesper.baubuild.feature.staff.newinquiry.NewInquiryUiState
import com.vesper.baubuild.feature.staff.newinquiry.NewInquiryViewModel
import com.vesper.baubuild.feature.staff.newinquiry.NotifyOnDoneNote
import com.vesper.baubuild.feature.staff.newinquiry.RefinedInquiryCard
import com.vesper.baubuild.feature.staff.newinquiry.RewardTimingFields
import com.vesper.baubuild.feature.staff.newinquiry.WhatAiChangedNote
import com.vesper.baubuild.feature.staff.newinquiry.WhatYouWroteCard
import com.vesper.baubuild.feature.staff.newinquiry.rememberNewInquiryViewModel
import com.vesper.baubuild.feature.staff.tour.StaffTour
import com.vesper.baubuild.feature.staff.tour.StaffTourStep
import com.vesper.baubuild.feature.staff.tour.staffTourTarget

/** What the portal page shows for a [NewInquiryUiState]. */
internal enum class NewInquiryPortalView { Form, Refining, Ready, Posted }

internal val NewInquiryUiState.portalView: NewInquiryPortalView
    get() = when {
        posted -> NewInquiryPortalView.Posted
        refining -> NewInquiryPortalView.Refining
        step == NewInquiryStep.Ready && refined != null -> NewInquiryPortalView.Ready
        else -> NewInquiryPortalView.Form
    }

/**
 * Keeps the shared [StaffTour] in step with the page: opening the page leaves step 1; reaching Ready to Post is
 * step 3; going back to the form from Ready to Post ("Edit") is step 2 again. Returns the steps to apply, in order.
 */
internal fun newInquiryPortalTourMoves(tour: StaffTourStep?, view: NewInquiryPortalView): List<() -> Unit> = when {
    tour == null -> emptyList()
    tour == StaffTourStep.TapPlus -> listOf { StaffTour.advanceFrom(StaffTourStep.TapPlus) }
    view == NewInquiryPortalView.Ready && (tour == StaffTourStep.DescribeWork || tour == StaffTourStep.RewardAndTiming) -> listOf(
        { StaffTour.advanceFrom(StaffTourStep.DescribeWork) },
        { StaffTour.advanceFrom(StaffTourStep.RewardAndTiming) },
    )
    view == NewInquiryPortalView.Form && tour == StaffTourStep.ReadyToPost -> listOf(
        { StaffTour.onNewInquiryDismissed() },
        { StaffTour.advanceFrom(StaffTourStep.TapPlus) },
    )
    else -> emptyList()
}

/** Below this content width the Details and Reward & timing cards (and the Ready columns) stack. */
private val TwoColumnMinWidth = 860.dp

/**
 * Desktop staff New Inquiry (docs__Portal_Staff_NewInquiry.html + docs__Portal_Staff_Refined.html): one page with the
 * Details and Reward & timing cards side by side, "Save draft" and "Refine with AI"; while the AI works the cards
 * give way to [AiRefiningIndicator] (Cancel / Esc); then "Ready to post" puts What you wrote next to the AI's version
 * with Edit / Refine again / Post project.
 *
 * The form lives in the [NewInquiryViewModel] (kept across section switches), so leaving the page never loses what
 * was typed. Posting sends the shell to Inquiries (the ViewModel calls `StaffNav.selectTab`) and the page starts
 * a fresh inquiry; so does saving a draft.
 *
 * Keyboard: Tab / Shift+Tab move between fields (also out of the multi-line ones); Ctrl/⌘+Enter refines (form) or
 * posts (Ready to post).
 */
@Composable
fun NewInquiryPortalPage(modifier: Modifier = Modifier) {
    // A new instance per inquiry: bumped after posting or saving a draft.
    var instance by rememberSaveable { mutableIntStateOf(0) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    val vm = rememberNewInquiryViewModel("portal-$instance")
    val state by vm.state.collectAsStateWithLifecycle()
    val view = state.portalView

    LaunchedEffect(state.posted, state.closed) {
        when {
            state.posted -> {
                notice = null
                instance++
            }
            state.closed -> {
                notice = "Draft saved. You can start a new inquiry."
                instance++
            }
        }
    }

    val tourStep by StaffTour.step.collectAsStateWithLifecycle()
    LaunchedEffect(tourStep, view) { newInquiryPortalTourMoves(tourStep, view).forEach { it() } }

    val focusManager = LocalFocusManager.current
    LaunchedEffect(view) { if (view != NewInquiryPortalView.Form) focusManager.clearFocus() }

    Column(
        modifier.fillMaxSize().background(BauColors.Paper).onPreviewKeyEvent { e ->
            val mod = e.isCtrlPressed || e.isMetaPressed
            when {
                e.key == Key.Tab && !mod && !e.isAltPressed -> {
                    if (e.type == KeyEventType.KeyDown) {
                        focusManager.moveFocus(if (e.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
                    }
                    true
                }
                (e.key == Key.Enter || e.key == Key.NumPadEnter) && mod && e.type == KeyEventType.KeyDown -> when (view) {
                    NewInquiryPortalView.Form -> { vm.refine(); true }
                    NewInquiryPortalView.Ready -> { vm.post(); true }
                    else -> false
                }
                else -> false
            }
        },
    ) {
        PortalPageHeader(
            if (view == NewInquiryPortalView.Ready) "Ready to post" else "New inquiry",
            subtitle = if (view == NewInquiryPortalView.Ready) state.readySubtitle else null,
        )
        BoxWithConstraints(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = PortalTokens.PagePadding, end = PortalTokens.PagePadding, bottom = PortalTokens.PagePadding),
        ) {
            val wide = maxWidth >= TwoColumnMinWidth
            when (view) {
                NewInquiryPortalView.Form -> FormView(state, vm, wide, notice, onDismissNotice = { notice = null })
                NewInquiryPortalView.Refining -> RefiningView(onCancel = vm::cancelRefine)
                NewInquiryPortalView.Ready -> ReadyView(state, vm, wide)
                // Only for the moment before the page resets (the shell has already moved to Inquiries).
                NewInquiryPortalView.Posted -> Spacer(Modifier.height(1.dp))
            }
        }
    }
}

@Composable
private fun FormView(
    state: NewInquiryUiState,
    vm: NewInquiryViewModel,
    wide: Boolean,
    notice: String?,
    onDismissNotice: () -> Unit,
) {
    // Any edit makes the "Draft saved" note stale.
    LaunchedEffect(state.isDirty) { if (state.isDirty) onDismissNotice() }
    Column(verticalArrangement = Arrangement.spacedBy(PortalTokens.Gap)) {
        val details: @Composable (Modifier) -> Unit = { m ->
            PortalCard(m, gap = 18.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PortalSectionTitle("Details")
                    Text(
                        "Describe the work in your own words. AI tidies the wording before it is posted.",
                        fontSize = 14.sp, lineHeight = 21.sp, color = BauColors.TextSecondary,
                    )
                }
                InquiryDetailsFields(state, vm)
            }
        }
        val reward: @Composable (Modifier) -> Unit = { m ->
            PortalCard(m, gap = 18.dp) {
                PortalSectionTitle("Reward & timing")
                RewardTimingFields(state, vm)
            }
        }
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(PortalTokens.Gap), verticalAlignment = Alignment.Top) {
                details(Modifier.weight(1.3f))
                reward(Modifier.weight(1f))
            }
        } else {
            details(Modifier.fillMaxWidth())
            reward(Modifier.fillMaxWidth())
        }

        state.refineError?.let { InlineError(it, "Retry", vm::refine) }
        state.saveDraftError?.let { InlineError(it, "Retry", vm::saveDraftAndClose) }

        Footer(
            note = notice ?: "You check the AI’s version before anything is posted.",
        ) {
            PortalButton(
                if (state.savingDraft) "Saving…" else "Save draft",
                onClick = vm::saveDraftAndClose,
                enabled = state.isDirty && !state.busy,
            )
            PortalButton(
                "Refine with AI",
                onClick = vm::refine,
                style = PortalButtonStyle.Primary,
                enabled = !state.busy,
                modifier = Modifier.staffTourTarget(StaffTourStep.DescribeWork, cornerRadius = PortalTokens.ButtonRadius)
                    .staffTourTarget(StaffTourStep.RewardAndTiming, cornerRadius = PortalTokens.ButtonRadius),
            )
        }
    }
}

@Composable
private fun RefiningView(onCancel: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    // Focus Cancel so Enter / Esc work without reaching for the mouse.
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    PortalCard(Modifier.fillMaxWidth().heightIn(min = 360.dp), padding = 32.dp) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AiRefiningIndicator(Modifier.widthIn(max = 460.dp).fillMaxWidth())
            BauTextLink(
                "Cancel", onClick = onCancel, color = BauColors.TextSecondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .focusRequester(cancelFocus)
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                            onCancel()
                            true
                        } else false
                    }
                    .pointerHand(),
            )
        }
    }
}

@Composable
private fun ReadyView(state: NewInquiryUiState, vm: NewInquiryViewModel, wide: Boolean) {
    val refined = state.refined ?: return
    val draft = state.refinedFor ?: state.draft
    Column(verticalArrangement = Arrangement.spacedBy(PortalTokens.Gap)) {
        val wrote: @Composable (Modifier) -> Unit = { m -> WhatYouWroteCard(draft, m, collapsible = false) }
        val aiVersion: @Composable (Modifier) -> Unit = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RefinedInquiryCard(refined, draft)
                WhatAiChangedNote(refined.whatAiChanged)
            }
        }
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(PortalTokens.Gap), verticalAlignment = Alignment.Top) {
                wrote(Modifier.weight(1f))
                aiVersion(Modifier.weight(1.35f))
            }
        } else {
            aiVersion(Modifier.fillMaxWidth())
            wrote(Modifier.fillMaxWidth())
        }

        state.postError?.let { InlineError(it, "Retry", vm::post) }
        state.refineError?.let { InlineError(it, "Retry", vm::refineAgain) }

        Footer(note = null, leading = { state.notifiesOnDone?.let { NotifyOnDoneNote(it, Modifier.weight(1f)) } }) {
            PortalButton("Edit", onClick = vm::editFromReady, enabled = !state.busy)
            PortalButton("Refine again", onClick = vm::refineAgain, enabled = !state.busy)
            PortalButton(
                if (state.posting) "Posting…" else "Post project",
                onClick = vm::post,
                style = PortalButtonStyle.Primary,
                enabled = !state.busy,
                modifier = Modifier.staffTourTarget(StaffTourStep.ReadyToPost, cornerRadius = PortalTokens.ButtonRadius),
            )
        }
    }
}

/** Note on the left, actions on the right (design: wraps, 12dp gaps). */
@Composable
private fun Footer(
    note: String?,
    leading: @Composable RowScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (note != null) {
            Text(note, Modifier.weight(1f), fontSize = 14.sp, lineHeight = 20.sp, color = BauColors.TextSecondary)
        } else {
            leading()
            Spacer(Modifier.weight(0.001f))
        }
        actions()
    }
}

@Composable
private fun InlineError(message: String, actionLabel: String, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(message, Modifier.weight(1f), fontSize = 14.sp, lineHeight = 20.sp, color = BauColors.Danger)
        BauTextLink(actionLabel, onClick = onAction, color = BauColors.BlueDeep, fontSize = 15.sp, modifier = Modifier.pointerHand())
    }
}
