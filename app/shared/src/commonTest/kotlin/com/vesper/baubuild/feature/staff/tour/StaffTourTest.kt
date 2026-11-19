package com.vesper.baubuild.feature.staff.tour

import com.vesper.baubuild.core.prefs.InMemoryFlagStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StaffTourTest {
    private fun fresh(): InMemoryFlagStore {
        val store = InMemoryFlagStore()
        StaffTour.useStore(store)
        StaffTour.debug(ignoreSeen = false, suppress = false)
        StaffTour.reset()
        return store
    }

    @Test
    fun runsThroughAllStepsOnceThenRemembers() {
        fresh()
        StaffTour.startIfFirstTime("u1")
        StaffTourStep.entries.forEach { step ->
            assertEquals(step, StaffTour.step.value)
            StaffTour.advanceFrom(StaffTourStep.TapPlus.takeIf { it != step } ?: step) // wrong-step calls are ignored
            if (step != StaffTourStep.TapPlus) StaffTour.advanceFrom(step)
        }
        assertNull(StaffTour.step.value)
        StaffTour.reset()
        StaffTour.startIfFirstTime("u1")
        assertNull(StaffTour.step.value)
        StaffTour.reset()
        StaffTour.startIfFirstTime("u2")
        assertEquals(StaffTourStep.TapPlus, StaffTour.step.value)
    }

    @Test
    fun closingNewInquiryBeforePostingPointsAtPlusAgain() {
        fresh()
        StaffTour.startIfFirstTime("u3")
        StaffTour.advanceFrom(StaffTourStep.TapPlus)
        StaffTour.advanceFrom(StaffTourStep.DescribeWork)
        assertEquals(StaffTourStep.RewardAndTiming, StaffTour.step.value)
        StaffTour.onNewInquiryDismissed()
        assertEquals(StaffTourStep.TapPlus, StaffTour.step.value)
    }

    @Test
    fun skipIsRemembered() {
        fresh()
        StaffTour.startIfFirstTime("u4")
        StaffTour.skip()
        assertNull(StaffTour.step.value)
        StaffTour.reset()
        StaffTour.startIfFirstTime("u4")
        assertNull(StaffTour.step.value)
    }
}
