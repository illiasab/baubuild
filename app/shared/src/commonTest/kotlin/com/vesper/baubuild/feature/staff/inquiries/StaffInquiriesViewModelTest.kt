package com.vesper.baubuild.feature.staff.inquiries

import com.vesper.baubuild.core.DataChanges
import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.core.model.InquiryStatus
import com.vesper.baubuild.core.model.ProjectType
import com.vesper.baubuild.data.fake.FakeStaffData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StaffInquiriesViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun allGroupsByStatusInDesignOrder() = runTest(dispatcher) {
        val vm = StaffInquiriesViewModel(StaffTestRepository())
        advanceUntilIdle()
        val sections = vm.state.value.sections
        assertEquals(listOf("AI Refining", "Posted", "Closed"), sections.map { it.title })
        assertEquals(listOf("si_shuttle", "si_course_search"), sections[1].inquiries.map { it.id })
        assertEquals(listOf("si_room_booking", "si_attendance"), sections[2].inquiries.map { it.id })
    }

    @Test fun openAndClosedSegmentsFilter() = runTest(dispatcher) {
        val vm = StaffInquiriesViewModel(StaffTestRepository())
        advanceUntilIdle()
        vm.selectFilter(InquiryFilter.Open)
        advanceUntilIdle()
        assertTrue(vm.state.value.sections.flatMap { it.inquiries }.all { it.status.isOpen })
        assertEquals(3, vm.state.value.sections.sumOf { it.inquiries.size })
        vm.selectFilter(InquiryFilter.Closed)
        advanceUntilIdle()
        assertEquals(listOf("Closed"), vm.state.value.sections.map { it.title })
        assertEquals(2, vm.state.value.sections.single().inquiries.size)
    }

    @Test fun emptySegmentHasNoSectionsButKnowsUserHasInquiries() = runTest(dispatcher) {
        val repo = StaffTestRepository(listOf(FakeStaffData.shuttle))
        val vm = StaffInquiriesViewModel(repo)
        advanceUntilIdle()
        vm.selectFilter(InquiryFilter.Closed)
        advanceUntilIdle()
        assertTrue(vm.state.value.sections.isEmpty())
        assertTrue(vm.state.value.hasAny)
    }

    @Test fun newUserHasNothing() = runTest(dispatcher) {
        val vm = StaffInquiriesViewModel(StaffTestRepository(emptyList()))
        advanceUntilIdle()
        assertTrue(vm.state.value.sections.isEmpty())
        assertFalse(vm.state.value.hasAny)
    }

    @Test fun failedRefreshKeepsList() = runTest(dispatcher) {
        val repo = StaffTestRepository()
        val vm = StaffInquiriesViewModel(repo)
        advanceUntilIdle()
        repo.failReads = true
        vm.refresh()
        advanceUntilIdle()
        val content = assertIs<LoadState.Content<*>>(vm.state.value.inquiries)
        assertTrue(content.stale)
        assertEquals(5, vm.state.value.sections.sumOf { it.inquiries.size })
    }

    @Test fun reloadsOnInquiriesChanged() = runTest(dispatcher) {
        val repo = StaffTestRepository(emptyList())
        val vm = StaffInquiriesViewModel(repo)
        advanceUntilIdle()
        repo.inquiries = listOf(FakeStaffData.courseSearch)
        DataChanges.inquiriesChanged()
        advanceUntilIdle()
        assertEquals(listOf("si_course_search"), vm.state.value.sections.flatMap { it.inquiries }.map { it.id })
    }

    @Test fun formatting() {
        assertEquals("$4,820", formatUsd(4820))
        assertEquals("$310", formatUsd(310))
        assertEquals("$1,000,000", formatUsd(1_000_000))
        assertEquals("iOS + Android", typesSummary(setOf(ProjectType.IosApp, ProjectType.AndroidApp)))
        assertEquals("App + Website", typesSummary(setOf(ProjectType.IosApp, ProjectType.AndroidApp, ProjectType.Website)))
        assertEquals("Website", typesSummary(setOf(ProjectType.Website)))
        assertEquals("BAU Project · iOS + Android", FakeStaffData.labBooking.copy(draft = FakeStaffData.labBooking.draft.copy(projectFor = com.vesper.baubuild.core.model.ProjectFor.BauProject)).overviewSubtitle)
        assertEquals("Ilias & Arian notified", FakeStaffData.roomBooking.overviewSubtitle)
        assertEquals("BAU Project · Urgent · $35", FakeStaffData.shuttle.detailEyebrow)
        assertEquals("Due Nov 22 · 3 students · dev lead reviews", FakeStaffData.shuttle.detailMeta)
        assertEquals("Nov 22, 2026", formatLongDate("2026-11-22"))
        assertNull(formatShortDate("2026-02-30"))
        val d = IsoDate.parse("2026-11-22")!!
        assertEquals(d, IsoDate.fromEpochDay(d.epochDay))
        assertEquals(0L, IsoDate.parse("1970-01-01")!!.epochDay)
        assertEquals(InquiryGroup.Posted, InquiryGroup.of(InquiryStatus.InProgress))
    }

    @Test fun greeting() {
        val user = com.vesper.baubuild.core.model.User(
            "u", "Nora Hale", "nora.hale@bau.edu",
            com.vesper.baubuild.core.model.AccountKind.BauStaff, com.vesper.baubuild.core.model.Role.Staff,
        )
        assertEquals("Hello, Dr. Hale", staffGreeting(user, FakeStaffData.noraProfile))
        assertEquals("Hello, Nora", staffGreeting(user, FakeStaffData.noraProfile.copy(title = "Administrator")))
        assertEquals("Hello, Nora", staffGreeting(user, null))
        assertEquals("Hello, Dr. Hale", staffGreeting(user.copy(displayName = "Dr. Nora Hale"), FakeStaffData.noraProfile))
        assertEquals("Hello, Dr. Hale", staffGreeting(user.copy(displayName = ""), FakeStaffData.noraProfile))
        assertNull(staffGreeting(null, FakeStaffData.noraProfile))
    }
}
