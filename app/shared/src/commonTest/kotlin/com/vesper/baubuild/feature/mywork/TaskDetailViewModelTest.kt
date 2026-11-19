package com.vesper.baubuild.feature.mywork

import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.core.model.Membership
import com.vesper.baubuild.core.model.ProjectTask
import com.vesper.baubuild.core.model.ReviewStatus
import com.vesper.baubuild.core.model.TaskStatus
import com.vesper.baubuild.core.model.Workspace
import com.vesper.baubuild.data.fake.FakeData
import com.vesper.baubuild.data.fake.FakeWorkRepository
import com.vesper.baubuild.data.repository.WorkRepository
import com.vesper.baubuild.feature.mywork.task.TaskAction
import com.vesper.baubuild.feature.mywork.task.TaskDetailViewModel
import com.vesper.baubuild.feature.mywork.task.TaskUpdateBus
import com.vesper.baubuild.feature.mywork.task.deadlineOf
import com.vesper.baubuild.feature.mywork.task.formatDueDate
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Keeps per-task status in memory like the fake backend, with a switch to fail writes. */
private class TaskRepo : WorkRepository {
    var failUpdates = false
    val writes = mutableListOf<Pair<String, TaskStatus>>()
    private val statuses = mutableMapOf<String, TaskStatus>()

    override suspend fun myWork(): List<Membership> = emptyList()
    override suspend fun reviews(): List<ReviewStatus> = emptyList()

    override suspend fun workspace(projectId: String): Workspace {
        val base = FakeData.workspace(projectId)
        return base.copy(tasks = base.tasks.map { t -> statuses[t.id]?.let { t.copy(status = it) } ?: t })
    }

    override suspend fun updateTaskStatus(projectId: String, taskId: String, status: TaskStatus): ProjectTask {
        if (failUpdates) error("offline")
        writes += taskId to status
        statuses[taskId] = status
        return workspace(projectId).tasks.first { it.id == taskId }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TaskDetailViewModelTest {
    private val dispatcher: TestDispatcher = StandardTestDispatcher()
    private val project = FakeData.roomBooking.id

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun loadsTaskWithProject() = runTest(dispatcher) {
        val vm = TaskDetailViewModel(TaskRepo(), project, "t1", TaskUpdateBus())
        advanceUntilIdle()
        val detail = assertIs<LoadState.Content<*>>(vm.state.value.detail)
        assertNotNull(detail)
        assertEquals("Reject past dates in the booking form", vm.state.value.task?.title)
        assertEquals(4, vm.state.value.task?.acceptanceCriteria?.size)
        assertEquals(TaskAction.MarkDone, vm.state.value.nextAction)
    }

    @Test fun unknownTaskFailsToLoad() = runTest(dispatcher) {
        val vm = TaskDetailViewModel(TaskRepo(), project, "nope", TaskUpdateBus())
        advanceUntilIdle()
        assertIs<LoadState.Failed>(vm.state.value.detail)
    }

    @Test fun startMovesToDoToInProgressThenDone() = runTest(dispatcher) {
        val repo = TaskRepo()
        val vm = TaskDetailViewModel(repo, project, "t2", TaskUpdateBus())
        advanceUntilIdle()
        assertEquals(TaskAction.Start, vm.state.value.nextAction)

        vm.advance()
        assertTrue(vm.state.value.isUpdating)
        advanceUntilIdle()
        assertFalse(vm.state.value.isUpdating)
        assertEquals(TaskStatus.InProgress, vm.state.value.task?.status)
        assertEquals(TaskAction.MarkDone, vm.state.value.nextAction)

        vm.advance()
        advanceUntilIdle()
        assertEquals(TaskStatus.Done, vm.state.value.task?.status)
        assertNull(vm.state.value.nextAction)
        assertEquals(listOf("t2" to TaskStatus.InProgress, "t2" to TaskStatus.Done), repo.writes)

        vm.advance() // Done has no next action: no write
        advanceUntilIdle()
        assertEquals(2, repo.writes.size)
    }

    @Test fun failedUpdateKeepsStateAndShowsError() = runTest(dispatcher) {
        val repo = TaskRepo()
        val vm = TaskDetailViewModel(repo, project, "t2", TaskUpdateBus())
        advanceUntilIdle()
        vm.toggleCriterion(0)
        repo.failUpdates = true

        vm.advance()
        advanceUntilIdle()
        val s = vm.state.value
        assertFalse(s.isUpdating)
        assertNotNull(s.updateError)
        assertEquals(TaskStatus.ToDo, s.task?.status)
        assertEquals(setOf(0), s.checkedCriteria)
        assertIs<LoadState.Content<*>>(s.detail)

        repo.failUpdates = false
        vm.advance()
        advanceUntilIdle()
        assertNull(vm.state.value.updateError)
        assertEquals(TaskStatus.InProgress, vm.state.value.task?.status)
    }

    @Test fun togglesCriteriaAndIgnoresOutOfRange() = runTest(dispatcher) {
        val vm = TaskDetailViewModel(TaskRepo(), project, "t1", TaskUpdateBus())
        advanceUntilIdle()
        vm.toggleCriterion(0)
        vm.toggleCriterion(2)
        assertEquals(setOf(0, 2), vm.state.value.checkedCriteria)
        vm.toggleCriterion(0)
        assertEquals(setOf(2), vm.state.value.checkedCriteria)
        vm.toggleCriterion(4)
        vm.toggleCriterion(-1)
        assertEquals(setOf(2), vm.state.value.checkedCriteria)
    }

    @Test fun workspaceListReflectsStatusChange() = runTest(dispatcher) {
        val repo = TaskRepo()
        val bus = TaskUpdateBus()
        val workspaceVm = WorkspaceViewModel(repo, project, bus)
        val detailVm = TaskDetailViewModel(repo, project, "t3", bus)
        advanceUntilIdle()

        detailVm.advance()
        advanceUntilIdle()
        val ws = assertIs<LoadState.Content<Workspace>>(workspaceVm.state.value.workspace).data
        assertEquals(TaskStatus.InProgress, ws.tasks.first { it.id == "t3" }.status)
    }

    @Test fun fakeRepositoryPersistsOverrides() = runTest(dispatcher) {
        val repo = FakeWorkRepository()
        val updated = repo.updateTaskStatus(project, "t2", TaskStatus.InProgress)
        assertEquals(TaskStatus.InProgress, updated.status)
        assertEquals(TaskStatus.InProgress, repo.workspace(project).tasks.first { it.id == "t2" }.status)
        // Other projects and a fresh repository are untouched.
        assertEquals(TaskStatus.ToDo, FakeWorkRepository().workspace(project).tasks.first { it.id == "t2" }.status)
    }
}

class TaskDeadlineTest {
    private val base = ProjectTask("t", "Task", TaskStatus.ToDo)

    @Test fun formatsIsoDates() {
        assertEquals("Sun, 22 Nov", formatDueDate("2026-11-22"))
        assertEquals("Thu, 1 Jan", formatDueDate("2026-01-01"))
        assertEquals("Thu, 29 Feb", formatDueDate("2024-02-29"))
        assertNull(formatDueDate("2026-02-29"))
        assertNull(formatDueDate("22/11/2026"))
        assertNull(formatDueDate(""))
    }

    @Test fun relativeLabels() {
        val d = deadlineOf(base.copy(dueDate = "2026-11-22", dueInDays = 3))
        assertEquals("Sun, 22 Nov", d.dateLabel)
        assertEquals("Due in 3 days", d.relativeLabel)
        assertFalse(d.isOverdue)
        assertEquals("Due today", deadlineOf(base.copy(dueInDays = 0)).relativeLabel)
        assertEquals("Due tomorrow", deadlineOf(base.copy(dueInDays = 1)).relativeLabel)
    }

    @Test fun overdueOnlyWhenUnfinished() {
        val late = deadlineOf(base.copy(dueDate = "2026-11-17", dueInDays = -2))
        assertTrue(late.isOverdue)
        assertEquals("Overdue by 2 days", late.relativeLabel)
        assertEquals("Overdue by 1 day", deadlineOf(base.copy(dueInDays = -1)).relativeLabel)
        val done = deadlineOf(base.copy(status = TaskStatus.Done, dueInDays = -2))
        assertFalse(done.isOverdue)
        assertEquals("Completed", done.relativeLabel)
    }

    @Test fun noDeadline() {
        val d = deadlineOf(base)
        assertFalse(d.hasDeadline)
        assertEquals("No deadline", d.relativeLabel)
        assertNull(d.dateLabel)
    }
}

class TaskDetailRouteTest {
    @Test fun roundTripsThroughKey() {
        val route = MyWorkRoute.TaskDetail("p_room_booking", "t1")
        assertEquals(route, MyWorkRoute.decode(route.key))
        assertNull(MyWorkRoute.decode("task/p_room_booking"))
        assertEquals(MyWorkRoute.Workspace("p1"), MyWorkRoute.decode("workspace/p1"))
    }
}
