package com.vesper.baubuild.ios

import com.vesper.baubuild.di.AppGraph
import com.vesper.baubuild.core.session.SessionState
import com.vesper.baubuild.feature.staff.StaffNav
import com.vesper.baubuild.feature.staff.StaffNavEvent
import com.vesper.baubuild.feature.staff.StaffTab
import com.vesper.baubuild.feature.staff.tour.StaffTour
import com.vesper.baubuild.feature.staff.tour.StaffTourStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/*
 * Bridges for the native staff shell (StaffTabShell.swift): StaffNav events (screens asking the shell to open
 * New Inquiry / switch tabs) and the first-run StaffTour. Tabs cross as the strings "overview" / "inquiries" /
 * "profile"; tour steps as their 1-based number (0 = no tour running).
 */

/** Handle returned by the observe functions here; call [close] to stop receiving updates. */
interface StaffObservation {
    fun close()
}

private fun observe(block: suspend CoroutineScope.() -> Unit): StaffObservation {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    scope.launch(block = block)
    return object : StaffObservation {
        override fun close() = scope.cancel()
    }
}

private val StaffTab.key: String get() = name.lowercase()

/** Main thread. [onOpenNewInquiry] for StaffNav.openNewInquiry(); [onSelectTab] with "overview"/"inquiries"/"profile". */
fun observeStaffNav(onOpenNewInquiry: () -> Unit, onSelectTab: (String) -> Unit): StaffObservation = observe {
    StaffNav.events.collect { event ->
        when (event) {
            StaffNavEvent.OpenNewInquiry -> onOpenNewInquiry()
            is StaffNavEvent.SelectTab -> onSelectTab(event.tab.key)
        }
    }
}

/** Main thread: the current tour step number immediately, then on every change (0 = none). */
fun observeStaffTour(onChange: (Int) -> Unit): StaffObservation = observe {
    StaffTour.step.collect { onChange(it?.number ?: 0) }
}

fun currentStaffTourStep(): Int = StaffTour.step.value?.number ?: 0
fun staffTourStepCount(): Int = StaffTourStep.count
fun staffTourMessage(step: Int): String = StaffTourStep.ofNumber(step)?.message ?: ""

/** Starts the tour for the signed-in staff user unless they've finished/skipped it before. */
fun startStaffTourIfFirstTime() {
    val user = (AppGraph.current.session.state.value as? SessionState.SignedIn)?.user ?: return
    StaffTour.startIfFirstTime(user.id)
}

/** The shell's "+" was tapped (tour step 1 → 2). */
fun staffTourPlusTapped() = StaffTour.advanceFrom(StaffTourStep.TapPlus)
fun staffTourNewInquiryDismissed() = StaffTour.onNewInquiryDismissed()
fun skipStaffTour() = StaffTour.skip()

/**
 * DEBUG (`-staffTour show|off`): "show" starts the tour even if it was already seen; "off" hides it for this run
 * (plain-shell screenshots). Call before the staff shell appears.
 */
fun debugStaffTour(mode: String) {
    StaffTour.debug(ignoreSeen = mode == "show", suppress = mode == "off")
}
