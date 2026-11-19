package com.vesper.baubuild.feature.staff.overview

import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.core.model.AccountKind
import com.vesper.baubuild.core.model.Role
import com.vesper.baubuild.core.model.User
import com.vesper.baubuild.feature.staff.inquiries.StaffTestRepository
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StaffOverviewViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val nora = User("u1", "Nora Hale", "nora.hale@bau.edu", AccountKind.BauStaff, Role.Staff)

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun withInquiriesShowsStatsRecentAndAttention() = runTest(dispatcher) {
        val vm = StaffOverviewViewModel(StaffTestRepository()) { nora }
        advanceUntilIdle()
        val content = assertIs<LoadState.Content<StaffOverviewContent>>(vm.state.value.content).data
        assertEquals("Hello, Dr. Hale", content.greeting)
        assertTrue(content.hasInquiries)
        assertEquals(listOf("si_lab_booking", "si_shuttle", "si_course_search"), content.recent.map { it.id })
        assertEquals("si_shuttle", content.overview.needsAttention?.id)
        assertEquals(4_820, content.overview.raisedUsd)
        assertTrue(content.worldProjects.isEmpty())
    }

    @Test fun emptyUserGetsWorldProjects() = runTest(dispatcher) {
        val repo = StaffTestRepository(emptyList()).apply { profileTitle = "Administrator" }
        val vm = StaffOverviewViewModel(repo) { nora.copy(displayName = "Sam Smith", email = "sam.smith@bau.edu") }
        advanceUntilIdle()
        val content = assertIs<LoadState.Content<StaffOverviewContent>>(vm.state.value.content).data
        assertFalse(content.hasInquiries)
        assertTrue(content.recent.isEmpty())
        assertEquals(4, content.worldProjects.size)
        assertEquals("Hello, Sam", content.greeting)
    }

    @Test fun failedFirstLoadThenRetry() = runTest(dispatcher) {
        val repo = StaffTestRepository().apply { failReads = true }
        val vm = StaffOverviewViewModel(repo) { nora }
        advanceUntilIdle()
        assertIs<LoadState.Failed>(vm.state.value.content)
        repo.failReads = false
        vm.refresh()
        advanceUntilIdle()
        assertIs<LoadState.Content<*>>(vm.state.value.content)
    }

    @Test fun failedRefreshKeepsContentStale() = runTest(dispatcher) {
        val repo = StaffTestRepository()
        val vm = StaffOverviewViewModel(repo) { nora }
        advanceUntilIdle()
        repo.failReads = true
        vm.refresh()
        advanceUntilIdle()
        val content = assertIs<LoadState.Content<StaffOverviewContent>>(vm.state.value.content)
        assertTrue(content.stale)
        assertEquals(3, content.data.recent.size)
    }
}
