package com.vesper.baubuild.feature.mywork

import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.core.model.Membership
import com.vesper.baubuild.core.model.MembershipStatus
import com.vesper.baubuild.core.model.ReviewStatus
import com.vesper.baubuild.core.model.Workspace
import com.vesper.baubuild.data.fake.FakeData
import com.vesper.baubuild.data.repository.WorkRepository
import com.vesper.baubuild.feature.mywork.workspace.WorkspaceSegment
import com.vesper.baubuild.feature.mywork.workspace.WorkspaceViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class TestWorkRepository(
    var memberships: List<Membership> = FakeData.memberships,
) : WorkRepository {
    var fail = false
    var myWorkCalls = 0
    var workspaceCalls = 0

    override suspend fun myWork(): List<Membership> {
        myWorkCalls++
        if (fail) error("offline")
        return memberships
    }

    override suspend fun workspace(projectId: String): Workspace {
        workspaceCalls++
        if (fail) error("offline")
        return FakeData.workspace(projectId)
    }

    override suspend fun updateTaskStatus(projectId: String, taskId: String, status: com.vesper.baubuild.core.model.TaskStatus): com.vesper.baubuild.core.model.ProjectTask = error("unused")

    override suspend fun reviews(): List<ReviewStatus> = emptyList()
}

@OptIn(ExperimentalCoroutinesApi::class)
class MyWorkViewModelTest {
    private val dispatcher: TestDispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun loadsAndSplitsSections() = runTest(dispatcher) {
        val vm = MyWorkViewModel(TestWorkRepository())
        assertEquals(LoadState.Loading, vm.state.value.work)
        advanceUntilIdle()
        val sections = assertIs<LoadState.Content<MyWorkSections>>(vm.state.value.work).data
        assertEquals(listOf(FakeData.roomBooking.id, FakeData.attendanceCsv.id), sections.active.map { it.project.id })
        assertEquals(listOf(FakeData.courseSearch.id, FakeData.shuttleWidget.id), sections.waiting.map { it.project.id })
    }

    @Test fun emptyWhenNoMemberships() = runTest(dispatcher) {
        val vm = MyWorkViewModel(TestWorkRepository(memberships = emptyList()))
        advanceUntilIdle()
        assertTrue(assertIs<LoadState.Content<MyWorkSections>>(vm.state.value.work).data.isEmpty)
    }

    @Test fun failedRefreshKeepsContentAndMarksStale() = runTest(dispatcher) {
        val repo = TestWorkRepository()
        val vm = MyWorkViewModel(repo)
        advanceUntilIdle()
        repo.fail = true
        vm.refresh()
        advanceUntilIdle()
        val content = assertIs<LoadState.Content<MyWorkSections>>(vm.state.value.work)
        assertTrue(content.stale)
        assertEquals(2, content.data.active.size)
    }

    @Test fun firstLoadFailureShowsFailedThenRetryRecovers() = runTest(dispatcher) {
        val repo = TestWorkRepository().apply { fail = true }
        val vm = MyWorkViewModel(repo)
        advanceUntilIdle()
        assertIs<LoadState.Failed>(vm.state.value.work)
        repo.fail = false
        vm.refresh()
        advanceUntilIdle()
        assertIs<LoadState.Content<MyWorkSections>>(vm.state.value.work)
    }

    @Test fun refreshWhileLoadingIsIgnored() = runTest(dispatcher) {
        val repo = TestWorkRepository()
        val vm = MyWorkViewModel(repo)
        vm.refresh()
        vm.refresh()
        advanceUntilIdle()
        assertEquals(1, repo.myWorkCalls)
    }

    @Test fun membershipSubtitles() {
        assertEquals("Library · 3 tasks", Membership(FakeData.roomBooking, MembershipStatus.In).subtitle)
        assertEquals(
            "Reviewing: dev lead",
            Membership(FakeData.shuttleWidget, MembershipStatus.InReview, reviewingBy = "dev lead").subtitle,
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceViewModelTest {
    private val dispatcher: TestDispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun loadsWorkspaceForProject() = runTest(dispatcher) {
        val vm = WorkspaceViewModel(TestWorkRepository(), FakeData.roomBooking.id)
        advanceUntilIdle()
        val ws = assertIs<LoadState.Content<Workspace>>(vm.state.value.workspace).data
        assertEquals(FakeData.roomBooking.id, ws.project.id)
        assertEquals(3, ws.tasks.size)
        assertEquals(WorkspaceSegment.Tasks, vm.state.value.segment)
    }

    @Test fun switchingSegmentKeepsData() = runTest(dispatcher) {
        val vm = WorkspaceViewModel(TestWorkRepository(), FakeData.roomBooking.id)
        advanceUntilIdle()
        vm.selectSegment(WorkspaceSegment.Changes)
        advanceUntilIdle()
        assertEquals(WorkspaceSegment.Changes, vm.state.value.segment)
        assertIs<LoadState.Content<Workspace>>(vm.state.value.workspace)
    }

    @Test fun failedRefreshKeepsWorkspace() = runTest(dispatcher) {
        val repo = TestWorkRepository()
        val vm = WorkspaceViewModel(repo, FakeData.roomBooking.id)
        advanceUntilIdle()
        repo.fail = true
        vm.refresh()
        advanceUntilIdle()
        assertTrue(assertIs<LoadState.Content<Workspace>>(vm.state.value.workspace).stale)
        assertEquals(2, repo.workspaceCalls)
    }
}

class MyWorkBackStackTest {
    @Test fun pushPopAndRouteRoundTrip() {
        val stack = MyWorkBackStack.of()
        assertNull(stack.current)
        stack.push(MyWorkRoute.Workspace("p1"))
        stack.push(MyWorkRoute.Workspace("p1")) // duplicate top is ignored
        stack.push(MyWorkRoute.MentorAi("p1"))
        assertEquals(2, stack.routes.size)
        assertTrue(stack.pop())
        assertEquals(MyWorkRoute.Workspace("p1"), stack.current)
        assertTrue(stack.pop())
        assertTrue(!stack.pop())

        for (route in listOf(MyWorkRoute.Workspace("p_x"), MyWorkRoute.MentorAi("p_y"))) {
            assertEquals(route, MyWorkRoute.decode(route.key))
        }
        assertNull(MyWorkRoute.decode("unknown/1"))
        assertNull(MyWorkRoute.decode("workspace"))
    }
}
