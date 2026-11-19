package com.vesper.baubuild.feature.staff.tour

import com.vesper.baubuild.core.prefs.FlagStore
import com.vesper.baubuild.core.prefs.platformFlagStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The six coach marks of the first-run staff tour (design: iOS · Staff screens, "N of 6 · … · Skip"). */
enum class StaffTourStep(val message: String) {
    /** Shell: spotlight on the yellow + button. */
    TapPlus("Tap + to post your first inquiry."),
    /** New Inquiry step 1 (spotlight: Continue). */
    DescribeWork("Describe the work, then tap Continue."),
    /** New Inquiry step 2 (spotlight: the AI tidy-up / Continue action). */
    RewardAndTiming("Pick a reward and timing, then let AI tidy it up."),
    /** New Inquiry "Ready to Post" (spotlight: Post Project). */
    ReadyToPost("Check the AI’s version, then tap Post Project."),
    /** Inquiries list (spotlight: the new inquiry row; the shell spotlights the Inquiries tab until it is open). */
    OpenInquiry("Tap an inquiry to follow its progress."),
    /** Inquiry detail (spotlight: the Details segment). */
    Details("Daily progress and blockers live here. Tap Details to edit.");

    /** 1-based, as shown in the "N of 6" pill. */
    val number: Int get() = ordinal + 1

    companion object {
        val count: Int get() = entries.size
        fun ofNumber(number: Int): StaffTourStep? = entries.getOrNull(number - 1)
    }
}

/**
 * First-run coach-mark tour for staff, shared by every staff shell and staff screen.
 *
 * Who does what:
 * - **Shells** call [startIfFirstTime] when the staff shell appears, render the banner ("N of 6", message, Skip) for
 *   whatever [step] is current, spotlight their own targets (TapPlus → the + button; OpenInquiry → the Inquiries tab
 *   while another tab is selected), call [advanceFrom] (TapPlus) when + is tapped and [onNewInquiryDismissed] when
 *   the New Inquiry flow closes.
 * - **Staff screens** mark their target with `Modifier.staffTourTarget(step)` (StaffTourUi.kt) — the hosting
 *   [StaffTourHost] dims everything else and rings the target — and call [advanceFrom] with their step when the
 *   user does the action (Continue → DescribeWork, AI tidy-up → RewardAndTiming, Post Project → ReadyToPost, row tap
 *   → OpenInquiry, Details tap → Details). [advanceFrom] ignores calls for a step that isn't current, so screens
 *   can call it unconditionally.
 *
 * "Seen" is remembered per user in a persisted [FlagStore] once the tour is finished or skipped.
 */
object StaffTour {
    private var storeOverride: FlagStore? = null
    private val platformStore: FlagStore by lazy { platformFlagStore() }

    private val _step = MutableStateFlow<StaffTourStep?>(null)
    /** The coach mark to show now, or null when the tour isn't running. */
    val step: StateFlow<StaffTourStep?> = _step.asStateFlow()

    private var userId: String? = null
    /** DEBUG: suppress the tour for this process (screenshots of the plain shell). */
    private var suppressed = false
    /** DEBUG: start even when already seen. */
    private var ignoreSeen = false

    /** Tests: use [store] instead of the platform store. Call before anything else touches the tour. */
    fun useStore(store: FlagStore) {
        storeOverride = store
    }

    private val flagStore: FlagStore get() = storeOverride ?: platformStore

    private fun seenKey(userId: String) = "staffTourSeen.$userId"

    /** Starts the tour at step 1 unless [userId] has finished or skipped it before. Idempotent per user. */
    fun startIfFirstTime(userId: String) {
        if (this.userId == userId) return
        this.userId = userId
        _step.value = if (suppressed || (!ignoreSeen && flagStore.get(seenKey(userId)))) null else StaffTourStep.TapPlus
    }

    /** Moves to the step after [from] (or finishes after the last). No-op unless [from] is the current step. */
    fun advanceFrom(from: StaffTourStep) {
        if (_step.value != from) return
        val next = StaffTourStep.entries.getOrNull(from.ordinal + 1)
        if (next == null) finish() else _step.value = next
    }

    /** The New Inquiry flow closed. If it closed before posting (steps 2–4), point at + again. */
    fun onNewInquiryDismissed() {
        val current = _step.value ?: return
        if (current.ordinal in StaffTourStep.DescribeWork.ordinal..StaffTourStep.ReadyToPost.ordinal) {
            _step.value = StaffTourStep.TapPlus
        }
    }

    /** "Skip": ends the tour for good for this user. */
    fun skip() = finish()

    /** Ends the tour and remembers it for this user. */
    fun finish() {
        userId?.let { flagStore.set(seenKey(it), true) }
        _step.value = null
    }

    /** Sign-out / user switch: stop showing anything (the next [startIfFirstTime] decides again). */
    fun reset() {
        userId = null
        _step.value = null
    }

    /** DEBUG hooks: [ignoreSeen] starts the tour even if it was seen; [suppress] never shows it this run. */
    fun debug(ignoreSeen: Boolean, suppress: Boolean) {
        this.ignoreSeen = ignoreSeen
        suppressed = suppress
        if (suppress) _step.value = null
    }
}
