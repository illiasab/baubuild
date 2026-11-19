package com.vesper.baubuild.feature.mywork

import com.vesper.baubuild.core.model.TaskStatus
import com.vesper.baubuild.data.fake.FakeData
import com.vesper.baubuild.data.github.FileChange
import com.vesper.baubuild.feature.code.CodeSessionStore
import com.vesper.baubuild.feature.code.TestGitHub
import com.vesper.baubuild.feature.code.TestWork
import com.vesper.baubuild.feature.mywork.task.TaskAction
import com.vesper.baubuild.feature.mywork.task.TaskDetailViewModel
import com.vesper.baubuild.feature.mywork.task.TaskUpdateBus
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Start Task / Open Code / Mark as Done with the in-app code workspace. */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskCodeFlowTest {
    private val dispatcher = StandardTestDispatcher()
    private val project = FakeData.roomBooking.id
    private val t2Branch = "task/t2-add-a-test-for-the-date-check"

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun startTaskAsksForGitHubFirstThenCreatesBranchAndOpensCode() = runTest(dispatcher) {
        val gh = TestGitHub(connected = false)
        val work = TestWork()
        val store = CodeSessionStore()
        val vm = TaskDetailViewModel(work, project, "t2", TaskUpdateBus(), gh, store)
        advanceUntilIdle()
        assertTrue(vm.state.value.codeEnabled)
        assertEquals(TaskAction.Start, vm.state.value.nextAction)
        assertNull(vm.state.value.branchName)

        vm.advance()
        advanceUntilIdle()
        assertTrue(vm.state.value.showConnectGitHub)
        assertTrue(gh.branchCalls.isEmpty())
        assertTrue(work.writes.isEmpty())

        // Dismissing the prompt cancels the start.
        vm.dismissGitHubPrompt()
        vm.onGitHubConnected()
        advanceUntilIdle()
        assertTrue(gh.branchCalls.isEmpty())

        vm.advance()
        gh.connect("token")
        vm.onGitHubConnected()
        assertFalse(vm.state.value.showConnectGitHub)
        assertTrue(vm.state.value.progressLabel!!.startsWith("Creating branch task/t2-"))
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals(listOf(t2Branch), gh.branchCalls)
        assertEquals(t2Branch, s.branchName)
        assertEquals(t2Branch, store.get(project, "t2").branch?.name)
        assertEquals(TaskStatus.InProgress, s.task?.status)
        assertEquals(listOf("t2" to TaskStatus.InProgress), work.writes)
        assertTrue(s.openCodeRequested)
        assertTrue(s.canOpenCode)
        assertNull(s.progressLabel)
        vm.onCodeOpened()
        assertFalse(vm.state.value.openCodeRequested)
    }

    @Test fun connectedStartSkipsPrompt() = runTest(dispatcher) {
        val gh = TestGitHub()
        val vm = TaskDetailViewModel(TestWork(), project, "t2", TaskUpdateBus(), gh, CodeSessionStore())
        advanceUntilIdle()
        vm.advance()
        advanceUntilIdle()
        assertFalse(vm.state.value.showConnectGitHub)
        assertEquals(TaskStatus.InProgress, vm.state.value.task?.status)
    }

    @Test fun branchFailureKeepsTaskAndShowsError() = runTest(dispatcher) {
        val gh = TestGitHub().apply { failBranch = true }
        val work = TestWork()
        val vm = TaskDetailViewModel(work, project, "t2", TaskUpdateBus(), gh, CodeSessionStore())
        advanceUntilIdle()
        vm.advance()
        advanceUntilIdle()
        assertNotNull(vm.state.value.updateError)
        assertEquals(TaskStatus.ToDo, vm.state.value.task?.status)
        assertTrue(work.writes.isEmpty())
    }

    @Test fun markDoneWithoutCommitsExplains() = runTest(dispatcher) {
        val gh = TestGitHub()
        val work = TestWork()
        val vm = TaskDetailViewModel(work, project, "t1", TaskUpdateBus(), gh, CodeSessionStore())
        advanceUntilIdle()
        assertTrue(vm.state.value.canOpenCode) // t1 is already in progress
        assertEquals("task/t1-reject-past-dates-in-the-booking-form", vm.state.value.branchName)

        vm.advance()
        advanceUntilIdle()
        assertEquals("Commit your changes before marking the task done.", vm.state.value.updateError)
        assertEquals(TaskStatus.InProgress, vm.state.value.task?.status)
        assertTrue(work.writes.isEmpty())
        assertNull(vm.state.value.pullRequest)
    }

    @Test fun markDoneWithCommitsOpensPullRequestThenDone() = runTest(dispatcher) {
        val gh = TestGitHub()
        val work = TestWork()
        val store = CodeSessionStore()
        val branch = "task/t1-reject-past-dates-in-the-booking-form"
        // A commit pushed on the branch (e.g. from the code workspace).
        gh.ensureTaskBranch("bau-build/library-booking", branch)
        val commit = gh.commit("bau-build/library-booking", branch, "fix", listOf(FileChange("a.js", "x")))
        store.update(project, "t1") { it.copy(commits = listOf(commit)) }

        val vm = TaskDetailViewModel(work, project, "t1", TaskUpdateBus(), gh, store)
        advanceUntilIdle()
        vm.advance()
        advanceUntilIdle()
        val s = vm.state.value
        assertNull(s.updateError)
        assertEquals(42, s.pullRequest?.number)
        assertEquals(TaskStatus.Done, s.task?.status)
        val (head, base, body) = gh.pullRequests.single()
        assertEquals(branch, head)
        assertEquals("main", base)
        assertTrue(body.contains("- [ ] The Book button stays disabled while the date is invalid"))
        assertEquals(listOf("t1" to TaskStatus.Done), work.writes)
    }

    @Test fun startedHereWithoutCommitsIsRefusedLocallyThenOpensAfterCommit() = runTest(dispatcher) {
        val gh = TestGitHub()
        val work = TestWork()
        val store = CodeSessionStore()
        val vm = TaskDetailViewModel(work, project, "t2", TaskUpdateBus(), gh, store)
        advanceUntilIdle()
        vm.advance() // Start Task
        advanceUntilIdle()
        assertEquals("base0", store.get(project, "t2").createdHeadSha)

        vm.advance() // Mark as Done, nothing committed
        advanceUntilIdle()
        assertEquals("Commit your changes before marking the task done.", vm.state.value.updateError)
        assertEquals(0, gh.pullRequestAttempts)
        assertEquals(TaskStatus.InProgress, vm.state.value.task?.status)

        // Committed from another device: head moved, so the PR opens even with no commits in this session.
        gh.commit("bau-build/library-booking", t2Branch, "test", listOf(FileChange("t.js", "x")))
        vm.advance()
        advanceUntilIdle()
        assertNull(vm.state.value.updateError)
        assertEquals(42, vm.state.value.pullRequest?.number)
        assertEquals(TaskStatus.Done, vm.state.value.task?.status)
    }

    @Test fun withoutGitHubRepositoryKeepsPlainStatusFlow() = runTest(dispatcher) {
        val work = TestWork()
        val vm = TaskDetailViewModel(work, project, "t2", TaskUpdateBus())
        advanceUntilIdle()
        assertFalse(vm.state.value.codeEnabled)
        vm.advance()
        advanceUntilIdle()
        assertEquals(TaskStatus.InProgress, vm.state.value.task?.status)
        assertFalse(vm.state.value.canOpenCode)
        assertFalse(vm.state.value.openCodeRequested)
    }

    @Test fun codeRouteRoundTrips() {
        val route = MyWorkRoute.Code("p_room_booking", "t1")
        assertEquals("code/p_room_booking/t1", route.key)
        assertEquals(route, MyWorkRoute.decode(route.key))
        assertNull(MyWorkRoute.decode("code/p_room_booking"))
    }
}
