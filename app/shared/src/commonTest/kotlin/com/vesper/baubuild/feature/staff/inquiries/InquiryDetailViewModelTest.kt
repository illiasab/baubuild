package com.vesper.baubuild.feature.staff.inquiries

import com.vesper.baubuild.core.model.InquiryPriority
import com.vesper.baubuild.core.model.InquiryStatus
import com.vesper.baubuild.core.model.ProjectType
import com.vesper.baubuild.core.model.RewardType
import com.vesper.baubuild.feature.staff.inquiries.detail.InquiryDetailTab
import com.vesper.baubuild.feature.staff.inquiries.detail.InquiryDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InquiryDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.loaded(repo: StaffTestRepository, id: String = "si_shuttle"): InquiryDetailViewModel {
        val vm = InquiryDetailViewModel(repo, id)
        advanceUntilIdle()
        return vm
    }

    @Test fun loadsWithFormFromDraft() = runTest(dispatcher) {
        val vm = loaded(StaffTestRepository())
        val s = vm.state.value
        assertEquals("Shuttle timetable widget", s.data?.title)
        assertEquals(InquiryDetailTab.Status, s.tab)
        assertEquals("35", s.form?.price)
        assertEquals("2026-11-22", s.form?.deadline)
        assertFalse(s.editor.isDirty)
        assertFalse(s.canSave)
        assertNotNull(s.blocker)
    }

    @Test fun resolveBlockerClearsIt() = runTest(dispatcher) {
        val repo = StaffTestRepository()
        val vm = loaded(repo)
        vm.resolveBlocker()
        advanceUntilIdle()
        assertEquals(1, repo.resolveCalls)
        assertNull(vm.state.value.blocker)
        assertFalse(vm.state.value.isResolving)
        assertEquals(true, vm.state.value.data?.progress?.blocker?.resolved)
    }

    @Test fun resolveBlockerFailureIsInline() = runTest(dispatcher) {
        val repo = StaffTestRepository().apply { failWrites = true }
        val vm = loaded(repo)
        vm.resolveBlocker()
        advanceUntilIdle()
        assertNotNull(vm.state.value.resolveError)
        assertNotNull(vm.state.value.blocker)
        assertNotNull(vm.state.value.data)
    }

    @Test fun editingMakesDirtyAndUndoingMakesClean() = runTest(dispatcher) {
        val vm = loaded(StaffTestRepository())
        vm.setTitle("Shuttle widget v2")
        advanceUntilIdle()
        assertTrue(vm.state.value.canSave)
        vm.setTitle("Shuttle timetable widget")
        advanceUntilIdle()
        assertFalse(vm.state.value.editor.isDirty)
    }

    @Test fun saveSendsDraftAndResetsDirty() = runTest(dispatcher) {
        val repo = StaffTestRepository()
        val vm = loaded(repo)
        vm.setTitle("Shuttle widget v2")
        vm.toggleType(ProjectType.Website)
        vm.setPriority(InquiryPriority.Normal)
        vm.setPrice("50")
        vm.setDeadline("2026-12-01")
        vm.save()
        advanceUntilIdle()
        val (id, draft) = repo.updates.single()
        assertEquals("si_shuttle", id)
        assertEquals("Shuttle widget v2", draft.title)
        assertEquals(setOf(ProjectType.IosApp, ProjectType.AndroidApp), draft.types)
        assertEquals(InquiryPriority.Normal, draft.priority)
        assertEquals(50, draft.priceUsd)
        assertTrue(RewardType.Price in draft.rewards)
        assertEquals("2026-12-01", draft.deadline)
        val s = vm.state.value
        assertFalse(s.editor.isDirty)
        assertFalse(s.editor.isSaving)
        assertNotNull(s.editor.savedMessage)
        assertEquals("Shuttle widget v2", s.data?.title)
    }

    @Test fun saveDoesNothingWhenClean() = runTest(dispatcher) {
        val repo = StaffTestRepository()
        val vm = loaded(repo)
        vm.save()
        advanceUntilIdle()
        assertTrue(repo.updates.isEmpty())
    }

    @Test fun validationBlocksSave() = runTest(dispatcher) {
        val repo = StaffTestRepository()
        val vm = loaded(repo)
        vm.setTitle("  ")
        vm.save()
        advanceUntilIdle()
        assertEquals("Add a project title.", vm.state.value.editor.saveError)
        assertTrue(repo.updates.isEmpty())

        vm.setTitle("Ok")
        listOf(ProjectType.IosApp, ProjectType.AndroidApp, ProjectType.Website).forEach(vm::toggleType)
        vm.save()
        advanceUntilIdle()
        assertEquals("Pick at least one project type.", vm.state.value.editor.saveError)

        vm.toggleType(ProjectType.Website)
        vm.setDeadline("2026-13-01")
        vm.save()
        advanceUntilIdle()
        assertNotNull(vm.state.value.editor.saveError)
        assertTrue(repo.updates.isEmpty())
    }

    @Test fun saveFailureKeepsInput() = runTest(dispatcher) {
        val repo = StaffTestRepository().apply { failWrites = true }
        val vm = loaded(repo)
        vm.setExplanation("New explanation")
        vm.save()
        advanceUntilIdle()
        val s = vm.state.value
        assertNotNull(s.editor.saveError)
        assertEquals("New explanation", s.form?.explanation)
        assertTrue(s.editor.isDirty)
        assertTrue(s.canSave)
    }

    @Test fun refreshKeepsUnsavedEdits() = runTest(dispatcher) {
        val vm = loaded(StaffTestRepository())
        vm.setTitle("Typing…")
        vm.refresh()
        advanceUntilIdle()
        assertEquals("Typing…", vm.state.value.form?.title)
        assertTrue(vm.state.value.editor.isDirty)
    }

    @Test fun cancelAfterConfirm() = runTest(dispatcher) {
        val repo = StaffTestRepository()
        val vm = loaded(repo)
        vm.requestCancel()
        advanceUntilIdle()
        assertTrue(vm.state.value.showCancelConfirm)
        vm.dismissCancel()
        advanceUntilIdle()
        assertFalse(vm.state.value.showCancelConfirm)
        assertEquals(0, repo.cancelCalls)

        vm.selectTab(InquiryDetailTab.Details)
        vm.requestCancel()
        vm.confirmCancel()
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(1, repo.cancelCalls)
        assertEquals(InquiryStatus.Cancelled, s.data?.status)
        assertFalse(s.canCancel)
        assertFalse(s.canEdit)
        assertEquals(InquiryDetailTab.Status, s.tab)
    }

    @Test fun cancelFailureIsInline() = runTest(dispatcher) {
        val repo = StaffTestRepository().apply { failWrites = true }
        val vm = loaded(repo)
        vm.requestCancel()
        vm.confirmCancel()
        advanceUntilIdle()
        assertNotNull(vm.state.value.cancelError)
        assertEquals(InquiryStatus.InProgress, vm.state.value.data?.status)
    }

    @Test fun closedInquiryCannotBeCancelledOrEdited() = runTest(dispatcher) {
        val repo = StaffTestRepository()
        val vm = loaded(repo, "si_room_booking")
        vm.requestCancel()
        advanceUntilIdle()
        assertFalse(vm.state.value.showCancelConfirm)
        vm.setTitle("x")
        vm.save()
        advanceUntilIdle()
        assertTrue(repo.updates.isEmpty())
    }

    @Test fun refinedToggle() = runTest(dispatcher) {
        val vm = loaded(StaffTestRepository(), "si_lab_booking")
        assertNotNull(vm.state.value.data?.refined)
        vm.toggleRefined()
        advanceUntilIdle()
        assertTrue(vm.state.value.showRefined)
    }
}
