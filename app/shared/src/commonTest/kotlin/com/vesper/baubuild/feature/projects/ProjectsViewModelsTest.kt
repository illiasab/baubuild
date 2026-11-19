package com.vesper.baubuild.feature.projects

import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.core.dataOrNull
import com.vesper.baubuild.core.model.Difficulty
import com.vesper.baubuild.core.model.MembershipStatus
import com.vesper.baubuild.data.fake.FakeData
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
abstract class MainDispatcherTest {
    protected val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUpMain() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDownMain() = Dispatchers.resetMain()

    protected fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }
}

class ProjectsHomeViewModelTest : MainDispatcherTest() {
    @Test fun loadsGreetingUrgentAndProjects() = test {
        val vm = ProjectsHomeViewModel(TestProjectRepository(), TestInquiryRepository(), signedIn())
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals("Sam", s.viewer.firstName)
        assertFalse(s.viewer.canRequestProject, "students never see Request a Project")
        assertEquals(FakeData.inquiries.size, s.urgent.dataOrNull?.size)
        assertEquals(FakeData.projects, s.projects.dataOrNull)
        assertFalse(s.isRefreshing)
    }

    @Test fun staffCanRequestProject() = test {
        val vm = ProjectsHomeViewModel(TestProjectRepository(), TestInquiryRepository(), signedIn(staff))
        advanceUntilIdle()
        assertTrue(vm.state.value.viewer.canRequestProject)
    }

    @Test fun failedRefreshKeepsContentAndMarksStale() = test {
        val projects = TestProjectRepository()
        val vm = ProjectsHomeViewModel(projects, TestInquiryRepository(), signedIn())
        advanceUntilIdle()
        projects.fail = true
        vm.refresh()
        advanceUntilIdle()
        val state = assertIs<LoadState.Content<*>>(vm.state.value.projects)
        assertTrue(state.stale)
        assertEquals(FakeData.projects, state.data)
    }

    @Test fun firstLoadFailureIsFailedAndRetryRecovers() = test {
        val projects = TestProjectRepository().apply { fail = true }
        val vm = ProjectsHomeViewModel(projects, TestInquiryRepository(), signedIn())
        advanceUntilIdle()
        assertIs<LoadState.Failed>(vm.state.value.projects)
        projects.fail = false
        vm.refresh()
        advanceUntilIdle()
        assertEquals(FakeData.projects, vm.state.value.projects.dataOrNull)
    }

    @Test fun urgentFailureDoesNotBlockProjects() = test {
        val vm = ProjectsHomeViewModel(TestProjectRepository(), TestInquiryRepository().apply { fail = true }, signedIn())
        advanceUntilIdle()
        assertIs<LoadState.Failed>(vm.state.value.urgent)
        assertEquals(FakeData.projects, vm.state.value.projects.dataOrNull)
    }
}

class ProjectListViewModelTest : MainDispatcherTest() {
    @Test fun hardListShowsOnlyHardProjects() = test {
        val vm = ProjectListViewModel(ProjectListSource.ByDifficulty(Difficulty.Hard), TestProjectRepository(), signedIn())
        advanceUntilIdle()
        val projects = vm.state.value.projects.dataOrNull.orEmpty()
        assertTrue(projects.isNotEmpty())
        assertTrue(projects.all { it.difficulty == Difficulty.Hard })
        assertEquals("Hard Projects", vm.state.value.title)
    }

    @Test fun classListUsesProjectsForClass() = test {
        val vm = ProjectListViewModel(
            ProjectListSource.ForClass("CMPS433", "Information Retrieval"), TestProjectRepository(), signedIn(),
        )
        advanceUntilIdle()
        assertEquals(listOf(FakeData.courseSearch), vm.state.value.projects.dataOrNull)
        assertEquals("CMPS433", vm.state.value.title)
        assertEquals("Information Retrieval", vm.state.value.subtitle)
    }

    @Test fun myClassesLoads() = test {
        val vm = MyClassesViewModel(TestClassRepository())
        advanceUntilIdle()
        assertEquals(FakeData.classes, vm.state.value.dataOrNull)
    }
}

class ProjectDetailViewModelTest : MainDispatcherTest() {
    @Test fun seedIsShownBeforeLoad() = test {
        val vm = ProjectDetailViewModel(FakeData.roomBooking.id, TestProjectRepository(), signedIn(), seed = FakeData.roomBooking)
        assertEquals(FakeData.roomBooking, vm.state.value.project.dataOrNull)
        advanceUntilIdle()
        assertEquals(FakeData.roomBooking, vm.state.value.project.dataOrNull)
    }

    @Test fun joiningOpenProjectShowsProgressThenIn() = test {
        val repo = TestProjectRepository().apply { joinGate = CompletableDeferred() }
        val vm = ProjectDetailViewModel(FakeData.roomBooking.id, repo, signedIn())
        advanceUntilIdle()
        vm.join()
        advanceUntilIdle()
        assertEquals(JoinState.Joining, vm.state.value.join)
        assertFalse(vm.state.value.canJoin)
        vm.join() // double tap while in flight is ignored
        repo.joinGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(JoinState.Joined(MembershipStatus.In), vm.state.value.join)
        assertEquals(1, repo.joinCalls)
    }

    @Test fun approvalProjectWaitsForApproval() = test {
        val highScore = FakeData.sam.copy(score = 320)
        val vm = ProjectDetailViewModel(FakeData.courseSearch.id, TestProjectRepository(), signedIn(highScore))
        advanceUntilIdle()
        vm.join()
        advanceUntilIdle()
        assertEquals(JoinState.Joined(MembershipStatus.WaitingForApproval), vm.state.value.join)
    }

    @Test fun belowMinScoreCannotJoin() = test {
        val repo = TestProjectRepository()
        val vm = ProjectDetailViewModel(FakeData.courseSearch.id, repo, signedIn()) // Sam has 180, needs 300
        advanceUntilIdle()
        assertEquals(JoinEligibility.BelowMinScore(300, 180), vm.state.value.eligibility)
        assertFalse(vm.state.value.canJoin)
        vm.join()
        advanceUntilIdle()
        assertEquals(JoinState.Idle, vm.state.value.join)
        assertEquals(0, repo.joinCalls)
    }

    @Test fun joinFailureKeepsProjectAndShowsInlineError() = test {
        val repo = TestProjectRepository()
        val vm = ProjectDetailViewModel(FakeData.roomBooking.id, repo, signedIn())
        advanceUntilIdle()
        repo.fail = true
        vm.join()
        advanceUntilIdle()
        assertIs<JoinState.Failed>(vm.state.value.join)
        assertEquals(FakeData.roomBooking, vm.state.value.project.dataOrNull)
        assertTrue(vm.state.value.canJoin, "user can retry")
        repo.fail = false
        vm.join()
        advanceUntilIdle()
        assertEquals(JoinState.Joined(MembershipStatus.In), vm.state.value.join)
    }
}

class InquiryFormViewModelTest : MainDispatcherTest() {
    private fun InquiryFormViewModel.fillValid() {
        onTitleChange("Shuttle timetable widget")
        onDescriptionChange("Show the next three campus shuttles on the home screen.")
        onDepartmentChange("Transport")
        onBountyChange("35")
        onUrgentChange(true)
    }

    @Test fun invalidSubmitShowsErrorsWithoutCallingBackend() = test {
        val repo = TestInquiryRepository()
        val vm = InquiryFormViewModel(repo, signedIn(staff))
        vm.onBountyChange("0")
        vm.submit()
        advanceUntilIdle()
        val s = vm.state.value
        assertTrue(s.showErrors)
        assertEquals("Add a short title.", s.titleError)
        assertEquals("Which department is this for?", s.departmentError)
        assertTrue(s.bountyError != null)
        assertTrue(repo.submitted.isEmpty())
    }

    @Test fun bountyAcceptsDigitsOnly() = test {
        val vm = InquiryFormViewModel(TestInquiryRepository(), signedIn(staff))
        vm.onBountyChange("$3a5")
        assertEquals("35", vm.state.value.bounty)
        vm.onBountyChange("")
        assertNull(vm.state.value.bountyError, "bounty is optional")
    }

    @Test fun validSubmitSucceeds() = test {
        val repo = TestInquiryRepository()
        val vm = InquiryFormViewModel(repo, signedIn(staff))
        vm.fillValid()
        vm.submit()
        assertTrue(vm.state.value.isSubmitting)
        advanceUntilIdle()
        val submitted = assertIs<SubmitState.Submitted>(vm.state.value.submit)
        assertEquals("Shuttle timetable widget", submitted.inquiry.title)
        assertEquals(35, repo.submitted.single().suggestedBountyUsd)
        assertTrue(repo.submitted.single().isUrgent)
    }

    @Test fun failureKeepsInputAndAllowsRetry() = test {
        val repo = TestInquiryRepository().apply { fail = true }
        val vm = InquiryFormViewModel(repo, signedIn(staff))
        vm.fillValid()
        vm.submit()
        advanceUntilIdle()
        assertIs<SubmitState.Failed>(vm.state.value.submit)
        assertEquals("Shuttle timetable widget", vm.state.value.title)
        assertEquals("35", vm.state.value.bounty)
        repo.fail = false
        vm.submit()
        advanceUntilIdle()
        assertIs<SubmitState.Submitted>(vm.state.value.submit)
    }

    @Test fun studentsCannotSubmit() = test {
        val repo = TestInquiryRepository()
        val vm = InquiryFormViewModel(repo, signedIn())
        assertFalse(vm.isAllowed)
        vm.fillValid()
        vm.submit()
        advanceUntilIdle()
        assertIs<SubmitState.Failed>(vm.state.value.submit)
        assertTrue(repo.submitted.isEmpty())
    }
}

class ProjectsNavigationTest {
    @Test fun routesRoundTripThroughKeys() {
        val routes = listOf(
            ProjectsRoute.ProjectList(ProjectListSource.AllOpen),
            ProjectsRoute.ProjectList(ProjectListSource.ByDifficulty(Difficulty.Easy)),
            ProjectsRoute.ProjectList(ProjectListSource.ByDifficulty(Difficulty.Hard)),
            ProjectsRoute.ProjectList(ProjectListSource.ForClass("CMPS433", "Information Retrieval")),
            ProjectsRoute.MyClasses,
            ProjectsRoute.Detail("p_room_booking"),
            ProjectsRoute.RequestProject,
        )
        routes.forEach { assertEquals(it, ProjectsRoute.decode(it.key)) }
        assertNull(ProjectsRoute.decode("nope/123"))
    }

    @Test fun backStackPushPop() {
        val stack = ProjectsBackStack.of()
        assertFalse(stack.canGoBack)
        stack.push(ProjectsRoute.MyClasses)
        stack.push(ProjectsRoute.MyClasses) // no duplicate on double tap
        assertEquals(1, stack.routes.size)
        stack.push(ProjectsRoute.Detail("p"))
        assertTrue(stack.pop())
        assertEquals(ProjectsRoute.MyClasses, stack.current)
        assertTrue(stack.pop())
        assertFalse(stack.pop())
    }
}

class ProjectsPresentationTest {
    @Test fun urgentCardCopyMatchesDesign() {
        val shuttle = FakeData.inquiries.first { it.id == "i_shuttle" }
        assertEquals("Urgent · Hard · any score welcome", shuttle.urgentEyebrow)
        assertEquals("$35 shuttle timetable widget", shuttle.urgentHeadline)
        assertEquals("$25 Wi-Fi outage status page", FakeData.inquiries.first { it.id == "i_wifi" }.urgentHeadline)
    }

    @Test fun scoreAndJoinLabels() {
        assertEquals("Any score", FakeData.roomBooking.scoreChipLabel)
        assertEquals("Score 300+", FakeData.courseSearch.scoreChipLabel)
        assertEquals("I’m In", FakeData.roomBooking.joinCtaLabel)
        assertEquals("Request", FakeData.courseSearch.joinCtaLabel)
        assertEquals(JoinEligibility.Eligible, FakeData.courseSearch.eligibilityFor(300))
    }
}
