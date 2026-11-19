package com.vesper.baubuild.feature.staff.newinquiry

import com.vesper.baubuild.core.model.*
import com.vesper.baubuild.data.repository.StaffRepository
import com.vesper.baubuild.feature.staff.StaffNav
import com.vesper.baubuild.feature.staff.StaffNavEvent
import com.vesper.baubuild.feature.staff.StaffTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NewInquiryViewModelTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** 2026-11-19. */
    private val today = InquiryDates.isoToEpochDay("2026-11-19")!!

    private class Repo(
        var classes: List<String> = listOf("CMPS433", "CMPS210"),
    ) : StaffRepository {
        var refineFails = false
        var postFails = false
        var draftFails = false
        var refineCalls = 0
        val refinedDrafts = mutableListOf<InquiryDraft>()
        val posted = mutableListOf<Pair<InquiryDraft, RefinedInquiry>>()
        val drafts = mutableListOf<InquiryDraft>()

        override suspend fun refine(draft: InquiryDraft): RefinedInquiry {
            delay(2_000)
            refineCalls++
            if (refineFails) error("503")
            refinedDrafts += draft
            return RefinedInquiry(
                title = "Build ${draft.title} v$refineCalls", brief = "Brief", doneLooksLike = listOf("Works"),
                types = draft.types, difficulty = Difficulty.Medium, minScore = 150, rewardSummary = "$40",
                deadline = draft.deadline, whatAiChanged = "Rewrote it.", notifiesOnDone = null,
            )
        }
        override suspend fun post(draft: InquiryDraft, refined: RefinedInquiry): StaffInquiry {
            delay(300)
            if (postFails) error("503")
            posted += draft to refined
            return StaffInquiry("new", draft, InquiryStatus.Posted, refined)
        }
        override suspend fun saveDraft(draft: InquiryDraft): StaffInquiry {
            delay(300)
            if (draftFails) error("503")
            drafts += draft
            return StaffInquiry("d", draft, InquiryStatus.Draft)
        }
        override suspend fun profile() = StaffProfile("CS", "Professor", classes, 0, 0, 0, com.vesper.baubuild.data.fake.FakeStaffData.seniorEngineers)
        override suspend fun seniorEngineer(id: String) = TODO()
        override suspend fun overview() = TODO()
        override suspend fun inquiries() = TODO()
        override suspend fun inquiry(id: String) = TODO()
        override suspend fun updateDetails(id: String, draft: InquiryDraft) = TODO()
        override suspend fun cancel(id: String) = TODO()
        override suspend fun resolveBlocker(inquiryId: String, blockerId: String) = TODO()
        override suspend fun worldProjects() = TODO()
        override suspend fun updateNotifications(notifyWhenDone: Boolean, notifyWhenStudentJoins: Boolean) = TODO()
    }

    private fun TestScope.setup(repo: Repo = Repo()): Pair<Repo, NewInquiryViewModel> {
        val vm = NewInquiryViewModel(repo, todayEpochDay = { today })
        advanceUntilIdle()
        return repo to vm
    }

    private fun NewInquiryViewModel.s() = state.value

    private fun NewInquiryViewModel.fillDetails() {
        onTitleChange("lab booking app")
        onExplanationChange("students book lab gear on paper")
        onExpectationChange("see what is free, book it")
        toggleType(ProjectType.IosApp)
    }

    private fun NewInquiryViewModel.fillReward() {
        onPriceChange("40")
        setDeadline("2026-12-03")
    }

    private fun TestScope.readyToPost(vm: NewInquiryViewModel) {
        vm.fillDetails()
        assertTrue(vm.continueToReward())
        vm.fillReward()
        vm.refine()
        advanceUntilIdle()
        assertEquals(NewInquiryStep.Ready, vm.s().step)
    }

    // ---- step 1 ----

    @Test fun step1RequiresTitleExplanationAndAType() = runTest {
        val (_, vm) = setup()
        assertFalse(vm.s().isDetailsValid)
        assertNull(vm.s().titleError) // no messages before the first attempt
        assertFalse(vm.continueToReward())
        assertEquals(NewInquiryStep.Details, vm.s().step)
        assertNotNull(vm.s().titleError)
        assertNotNull(vm.s().explanationError)
        assertNotNull(vm.s().typesError)

        vm.onTitleChange("  ")
        vm.onExplanationChange("x")
        vm.toggleType(ProjectType.Website)
        assertFalse(vm.s().isDetailsValid) // blank title
        vm.onTitleChange("Title")
        assertTrue(vm.s().isDetailsValid)
        vm.toggleType(ProjectType.Website) // untick → invalid again
        assertFalse(vm.s().isDetailsValid)
        vm.toggleType(ProjectType.Website)
        assertTrue(vm.continueToReward())
        assertEquals(NewInquiryStep.RewardTiming, vm.s().step)
    }

    @Test fun titleIsSingleLineAndCapped() = runTest {
        val (_, vm) = setup()
        vm.onTitleChange("a\nb" + "x".repeat(200))
        assertEquals(NewInquiryRules.MAX_TITLE, vm.s().form.title.length)
        assertFalse('\n' in vm.s().form.title)
    }

    // ---- step 2 ----

    @Test fun prefillsClassProjectFromProfileWithoutMakingItDirty() = runTest {
        val (_, vm) = setup()
        assertEquals(ProjectFor.ClassProject, vm.s().form.projectFor)
        assertEquals("CMPS433", vm.s().form.classCode)
        assertEquals(listOf("CMPS210"), vm.s().visibleClassSuggestions)
        assertFalse(vm.s().isDirty)
        assertEquals("Ilias and Arian, the senior engineers", vm.s().notifyFallback)
    }

    @Test fun noClassesMeansBauProjectDefault() = runTest {
        val (_, vm) = setup(Repo(classes = emptyList()))
        assertEquals(ProjectFor.BauProject, vm.s().form.projectFor)
        assertFalse(vm.s().showsClassCode)
    }

    @Test fun classCodeOnlyForClassProjects() = runTest {
        val (_, vm) = setup(Repo(classes = emptyList()))
        vm.fillDetails(); vm.continueToReward(); vm.fillReward()
        assertTrue(vm.s().isRewardValid)
        assertNull(vm.s().draft.classCode)

        vm.setProjectFor(ProjectFor.ClassProject)
        assertTrue(vm.s().showsClassCode)
        assertFalse(vm.s().isRewardValid) // class code required
        vm.refine()
        assertNotNull(vm.s().classCodeError)
        vm.onClassCodeChange("cmps 433")
        assertEquals("CMPS433", vm.s().form.classCode)
        assertNull(vm.s().classCodeError)
        assertEquals("CMPS433", vm.s().draft.classCode)

        vm.setProjectFor(ProjectFor.BauProject)
        assertNull(vm.s().draft.classCode) // not sent for BAU projects…
        assertEquals("CMPS433", vm.s().form.classCode) // …but kept for switching back
        assertTrue(vm.s().isRewardValid)
    }

    @Test fun switchingToClassProjectSuggestsFirstClass() = runTest {
        val (_, vm) = setup()
        vm.setProjectFor(ProjectFor.BauProject)
        vm.onClassCodeChange("")
        vm.setProjectFor(ProjectFor.ClassProject)
        assertEquals("CMPS433", vm.s().form.classCode)
    }

    @Test fun rewardFields() = runTest {
        val (_, vm) = setup()
        vm.fillDetails(); vm.continueToReward()
        vm.setDeadline("2026-12-03")
        assertTrue(vm.s().showsPrice) // Price is ticked by default
        assertFalse(vm.s().showsDiscount)
        assertFalse(vm.s().isRewardValid) // price missing
        vm.onPriceChange("\$0040abc")
        assertEquals("40", vm.s().form.price)
        assertTrue(vm.s().isRewardValid)
        assertEquals(40, vm.s().draft.priceUsd)

        vm.toggleReward(RewardType.SchoolDiscount)
        assertTrue(vm.s().showsDiscount)
        assertFalse(vm.s().isRewardValid)
        vm.onDiscountChange("150")
        vm.refine()
        assertNotNull(vm.s().discountError)
        vm.onDiscountChange("15%")
        assertTrue(vm.s().isRewardValid)
        assertEquals(15, vm.s().draft.schoolDiscountPercent)

        vm.toggleReward(RewardType.Price)
        assertFalse(vm.s().showsPrice)
        assertNull(vm.s().draft.priceUsd) // price not sent when unticked
        vm.toggleReward(RewardType.SchoolDiscount)
        assertNotNull(vm.s().rewardsError) // none ticked
        vm.toggleReward(RewardType.Volunteer)
        assertTrue(vm.s().isRewardValid)
        assertEquals(setOf(RewardType.Volunteer), vm.s().draft.rewards)
    }

    @Test fun deadlineMustBeAFutureRealDate() = runTest {
        val (_, vm) = setup()
        vm.fillDetails(); vm.continueToReward(); vm.onPriceChange("40")
        vm.refine()
        assertNotNull(vm.s().deadlineError) // missing
        vm.setDeadline("2026-11-19")
        assertEquals("Pick a date in the future.", vm.s().deadlineError)
        vm.setDeadline("2026-02-30")
        assertEquals("Use a date like 2026-12-03.", vm.s().deadlineError)
        vm.setDeadlineEpochDay(today + 1)
        assertEquals("2026-11-20", vm.s().form.deadline)
        assertNull(vm.s().deadlineError)
    }

    @Test fun refineWithIncompleteStep1GoesBack() = runTest {
        val (repo, vm) = setup()
        vm.onTitleChange("t"); vm.onExplanationChange("e"); vm.toggleType(ProjectType.Other)
        vm.continueToReward()
        vm.onTitleChange("")
        vm.refine()
        assertEquals(NewInquiryStep.Details, vm.s().step)
        assertNotNull(vm.s().titleError)
        advanceUntilIdle()
        assertEquals(0, repo.refineCalls)
    }

    // ---- refine ----

    @Test fun refineSuccessShowsReadyToPost() = runTest {
        val (repo, vm) = setup()
        vm.fillDetails(); vm.continueToReward(); vm.fillReward()
        vm.refine()
        runCurrent()
        assertTrue(vm.s().refining)
        vm.onTitleChange("ignored while refining")
        assertEquals("lab booking app", vm.s().form.title)
        advanceUntilIdle()
        assertFalse(vm.s().refining)
        assertEquals(NewInquiryStep.Ready, vm.s().step)
        assertEquals("Build lab booking app v1", vm.s().refined?.title)
        assertEquals(vm.s().draft, vm.s().refinedFor)
        assertEquals("CMPS433", repo.refinedDrafts.single().classCode)
        assertEquals("Ilias and Arian, the senior engineers", vm.s().notifiesOnDone)
    }

    @Test fun refineFailureKeepsInputAndCanRetry() = runTest {
        val (repo, vm) = setup()
        vm.fillDetails(); vm.continueToReward(); vm.fillReward()
        val before = vm.s().form
        repo.refineFails = true
        vm.refine()
        advanceUntilIdle()
        assertFalse(vm.s().refining)
        assertNotNull(vm.s().refineError)
        assertEquals(NewInquiryStep.RewardTiming, vm.s().step)
        assertEquals(before, vm.s().form)

        repo.refineFails = false
        vm.refine()
        assertNull(vm.s().refineError)
        advanceUntilIdle()
        assertEquals(NewInquiryStep.Ready, vm.s().step)
    }

    @Test fun cancelRefineReturnsToStep2KeepingInput() = runTest {
        val (repo, vm) = setup()
        vm.fillDetails(); vm.continueToReward(); vm.fillReward()
        val before = vm.s().form
        vm.refine()
        advanceTimeBy(500)
        vm.cancelRefine()
        assertFalse(vm.s().refining)
        advanceUntilIdle()
        assertEquals(NewInquiryStep.RewardTiming, vm.s().step)
        assertNull(vm.s().refined)
        assertEquals(before, vm.s().form)
        assertEquals(0, repo.refineCalls)
    }

    @Test fun unchangedDraftReusesResultChangedDraftRefinesAgain() = runTest {
        val (repo, vm) = setup()
        readyToPost(vm)
        vm.editFromReady()
        assertEquals(NewInquiryStep.Details, vm.s().step)
        assertEquals("lab booking app", vm.s().form.title) // everything kept
        vm.continueToReward()
        vm.refine()
        assertEquals(NewInquiryStep.Ready, vm.s().step) // no new AI call
        advanceUntilIdle()
        assertEquals(1, repo.refineCalls)

        vm.backFromReady()
        vm.setPriority(InquiryPriority.Urgent)
        vm.refine()
        advanceUntilIdle()
        assertEquals(2, repo.refineCalls)
        assertEquals(InquiryPriority.Urgent, vm.s().refinedFor?.priority)
    }

    @Test fun refineAgainReplacesResult() = runTest {
        val (repo, vm) = setup()
        readyToPost(vm)
        assertEquals("Build lab booking app v1", vm.s().refined?.title)
        vm.refineAgain()
        runCurrent()
        assertTrue(vm.s().refining)
        advanceUntilIdle()
        assertEquals(NewInquiryStep.Ready, vm.s().step)
        assertEquals("Build lab booking app v2", vm.s().refined?.title)
        assertEquals(repo.refinedDrafts[0], repo.refinedDrafts[1]) // same draft
    }

    @Test fun refineAgainCancelledKeepsPreviousResult() = runTest {
        val (_, vm) = setup()
        readyToPost(vm)
        vm.refineAgain()
        advanceTimeBy(100)
        vm.cancelRefine()
        advanceUntilIdle()
        assertEquals(NewInquiryStep.Ready, vm.s().step)
        assertEquals("Build lab booking app v1", vm.s().refined?.title)
    }

    // ---- post ----

    @Test fun postSuccessSelectsInquiriesTab() = runTest {
        val (repo, vm) = setup()
        readyToPost(vm)
        val events = mutableListOf<StaffNavEvent>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { StaffNav.events.collect { events += it } }
        runCurrent()
        vm.post()
        runCurrent()
        assertTrue(vm.s().posting)
        advanceUntilIdle()
        assertFalse(vm.s().posting)
        assertTrue(vm.s().posted)
        assertEquals("Build lab booking app v1", repo.posted.single().second.title)
        assertEquals(vm.s().draft, repo.posted.single().first)
        assertTrue(StaffNavEvent.SelectTab(StaffTab.Inquiries) in events)
        collector.cancel()

        vm.requestClose() // ignored once posted (the flow closes itself)
        assertFalse(vm.s().showDiscardConfirm)
    }

    @Test fun postFailureKeepsEverything() = runTest {
        val (repo, vm) = setup()
        readyToPost(vm)
        val refined = vm.s().refined
        repo.postFails = true
        vm.post()
        advanceUntilIdle()
        assertFalse(vm.s().posting)
        assertFalse(vm.s().posted)
        assertNotNull(vm.s().postError)
        assertEquals(refined, vm.s().refined)
        assertEquals(NewInquiryStep.Ready, vm.s().step)

        repo.postFails = false
        vm.post()
        advanceUntilIdle()
        assertTrue(vm.s().posted)
        assertNull(vm.s().postError)
    }

    // ---- closing ----

    @Test fun cleanCloseClosesImmediately() = runTest {
        val (_, vm) = setup()
        vm.requestClose()
        assertTrue(vm.s().closed)
        assertFalse(vm.s().showDiscardConfirm)
    }

    @Test fun dirtyCloseAsksThenDiscards() = runTest {
        val (repo, vm) = setup()
        vm.onTitleChange("Something")
        vm.requestClose()
        assertTrue(vm.s().showDiscardConfirm)
        assertFalse(vm.s().closed)
        vm.keepEditing()
        assertFalse(vm.s().showDiscardConfirm)
        assertEquals("Something", vm.s().form.title)
        vm.requestClose()
        vm.discard()
        assertTrue(vm.s().closed)
        advanceUntilIdle()
        assertTrue(repo.drafts.isEmpty())
    }

    @Test fun dirtyCloseSaveDraft() = runTest {
        val (repo, vm) = setup()
        vm.onTitleChange("Draft title")
        vm.requestClose()
        repo.draftFails = true
        vm.saveDraftAndClose()
        advanceUntilIdle()
        assertNotNull(vm.s().saveDraftError)
        assertTrue(vm.s().showDiscardConfirm)
        assertFalse(vm.s().closed)

        repo.draftFails = false
        vm.saveDraftAndClose()
        runCurrent()
        assertTrue(vm.s().savingDraft)
        advanceUntilIdle()
        assertTrue(vm.s().closed)
        assertEquals("Draft title", repo.drafts.single().title)
    }

    // ---- restore ----

    @Test fun snapshotRestoresForm() = runTest {
        val (repo, vm) = setup()
        readyToPost(vm)
        var json: String? = null
        val job = backgroundScope.launch { vm.snapshots.collect { json = it } }
        runCurrent()
        job.cancel()
        val restored = NewInquiryViewModel(repo, NewInquirySnapshot.decode(json!!), todayEpochDay = { today })
        advanceUntilIdle()
        assertEquals(vm.s().form, restored.s().form)
        assertEquals(NewInquiryStep.Ready, restored.s().step)
        assertEquals(vm.s().refined, restored.s().refined)
        assertTrue(restored.s().isDirty)
        assertNull(NewInquirySnapshot.decode("not json"))
    }

    // ---- dates ----

    @Test fun dateHelpers() {
        assertEquals("1970-01-01", InquiryDates.epochDayToIso(0))
        assertEquals(today, InquiryDates.isoToEpochDay(InquiryDates.epochDayToIso(today)))
        assertEquals("2024-02-29", InquiryDates.epochDayToIso(InquiryDates.isoToEpochDay("2024-02-29")!!))
        assertNull(InquiryDates.isoToEpochDay("2025-02-29"))
        assertNull(InquiryDates.isoToEpochDay("12/03/2026"))
        assertEquals("Dec 3", InquiryDates.short("2026-12-03"))
        assertEquals("Dec 3, 2026", InquiryDates.long("2026-12-03"))
    }

    @Test fun refinedTagsMatchDesign() {
        val refined = RefinedInquiry(
            "t", "b", emptyList(), setOf(ProjectType.AndroidApp, ProjectType.IosApp), Difficulty.Medium, 150, "$40",
            "2026-12-03", "w",
        )
        assertEquals(listOf("iOS App", "Android App", "Medium", "Score 150+", "Due Dec 3"), refined.tags())
        assertEquals("Class Project · CMPS433", InquiryDraft(projectFor = ProjectFor.ClassProject, classCode = "CMPS433").headline)
    }
}
