package com.vesper.baubuild.feature.staff.portal

import androidx.compose.ui.input.key.Key
import com.vesper.baubuild.core.model.Difficulty
import com.vesper.baubuild.core.model.RefinedInquiry
import com.vesper.baubuild.core.prefs.InMemoryFlagStore
import com.vesper.baubuild.feature.staff.StaffNavEvent
import com.vesper.baubuild.feature.staff.StaffTab
import com.vesper.baubuild.feature.staff.newinquiry.NewInquiryStep
import com.vesper.baubuild.feature.staff.newinquiry.NewInquiryUiState
import com.vesper.baubuild.feature.staff.tour.StaffTour
import com.vesper.baubuild.feature.staff.tour.StaffTourStep
import com.vesper.baubuild.shell.portal.StaffPortalSection
import com.vesper.baubuild.shell.portal.StaffPortalTourBanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StaffPortalTest {
    @Test
    fun navEventsAndShortcutsPickTheRightSection() {
        assertEquals(StaffPortalSection.NewInquiry, StaffPortalSection.of(StaffNavEvent.OpenNewInquiry))
        assertEquals(StaffPortalSection.Inquiries, StaffPortalSection.of(StaffNavEvent.SelectTab(StaffTab.Inquiries)))
        assertEquals(StaffPortalSection.Overview, StaffPortalSection.of(StaffNavEvent.SelectTab(StaffTab.Overview)))
        assertEquals(StaffPortalSection.Profile, StaffPortalSection.of(StaffNavEvent.SelectTab(StaffTab.Profile)))
        assertEquals(
            listOf(StaffPortalSection.Overview, StaffPortalSection.Inquiries, StaffPortalSection.NewInquiry, StaffPortalSection.Profile),
            listOf(Key.One, Key.Two, Key.Three, Key.Four).map(StaffPortalSection::ofShortcut),
        )
        assertNull(StaffPortalSection.ofShortcut(Key.Five))
    }

    @Test
    fun tourBannerFollowsThePortalDesign() {
        assertEquals(1, StaffPortalTourBanner.of(StaffTourStep.TapPlus).number)
        assertEquals("Post work for students from New Inquiry.", StaffPortalTourBanner.of(StaffTourStep.TapPlus).message)
        assertEquals(2, StaffPortalTourBanner.of(StaffTourStep.DescribeWork).number)
        assertEquals(2, StaffPortalTourBanner.of(StaffTourStep.RewardAndTiming).number)
        assertEquals(3, StaffPortalTourBanner.of(StaffTourStep.ReadyToPost).number)
        assertEquals(4, StaffPortalTourBanner.of(StaffTourStep.OpenInquiry).number)
        assertEquals(5, StaffPortalTourBanner.of(StaffTourStep.Details).number)
        assertTrue(StaffTourStep.entries.all { StaffPortalTourBanner.of(it).number <= StaffPortalTourBanner.COUNT })
    }

    private val refined = RefinedInquiry(
        title = "T", brief = "B", doneLooksLike = emptyList(), types = emptySet(), difficulty = Difficulty.Easy,
        minScore = 0, rewardSummary = "$1", deadline = null, whatAiChanged = "x", notifiesOnDone = null,
    )

    @Test
    fun pageViewFollowsTheViewModelState() {
        val form = NewInquiryUiState()
        assertEquals(NewInquiryPortalView.Form, form.portalView)
        assertEquals(NewInquiryPortalView.Form, form.copy(step = NewInquiryStep.RewardTiming).portalView)
        assertEquals(NewInquiryPortalView.Refining, form.copy(refining = true).portalView)
        assertEquals(NewInquiryPortalView.Ready, form.copy(step = NewInquiryStep.Ready, refined = refined).portalView)
        assertEquals(NewInquiryPortalView.Refining, form.copy(step = NewInquiryStep.Ready, refined = refined, refining = true).portalView)
        assertEquals(NewInquiryPortalView.Posted, form.copy(step = NewInquiryStep.Ready, refined = refined, posted = true).portalView)
    }

    private fun apply(view: NewInquiryPortalView) = newInquiryPortalTourMoves(StaffTour.step.value, view).forEach { it() }

    @Test
    fun pageMovesTheSharedTourAlong() {
        StaffTour.useStore(InMemoryFlagStore())
        StaffTour.debug(ignoreSeen = false, suppress = false)
        StaffTour.reset()
        StaffTour.startIfFirstTime("portal-user")
        assertEquals(StaffTourStep.TapPlus, StaffTour.step.value)

        apply(NewInquiryPortalView.Form) // page opened
        assertEquals(StaffTourStep.DescribeWork, StaffTour.step.value)
        apply(NewInquiryPortalView.Refining)
        assertEquals(StaffTourStep.DescribeWork, StaffTour.step.value)
        apply(NewInquiryPortalView.Ready)
        assertEquals(StaffTourStep.ReadyToPost, StaffTour.step.value)
        apply(NewInquiryPortalView.Form) // "Edit"
        assertEquals(StaffTourStep.DescribeWork, StaffTour.step.value)
        apply(NewInquiryPortalView.Ready)
        assertEquals(StaffTourStep.ReadyToPost, StaffTour.step.value)
        StaffTour.advanceFrom(StaffTourStep.ReadyToPost) // shell, on the post's "go to Inquiries"
        apply(NewInquiryPortalView.Form)
        assertEquals(StaffTourStep.OpenInquiry, StaffTour.step.value)
        StaffTour.reset()
    }
}
