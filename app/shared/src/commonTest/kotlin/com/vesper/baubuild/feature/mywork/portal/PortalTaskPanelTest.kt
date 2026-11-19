package com.vesper.baubuild.feature.mywork.portal

import com.vesper.baubuild.core.dataOrNull
import com.vesper.baubuild.core.model.TaskStatus
import com.vesper.baubuild.data.fake.FakeData
import com.vesper.baubuild.data.fake.FakeWorkRepository
import com.vesper.baubuild.feature.mywork.task.TaskDetailViewModel
import com.vesper.baubuild.feature.mywork.workspace.WorkspaceViewModel
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

/**
 * The portal workspace builds `WorkspaceViewModel(repo, projectId)` and the task panel builds
 * `TaskDetailViewModel(repo, projectId, taskId)`, both with the default (shared) TaskUpdateBus.
 * Advancing a task in the panel must update the workspace list without a reload.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PortalTaskPanelTest {
    private val dispatcher = StandardTestDispatcher()
    private val project = FakeData.roomBooking.id

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun advancingInPanelUpdatesWorkspaceListViaDefaultBus() = runTest(dispatcher) {
        val repo = FakeWorkRepository()
        val workspaceVm = WorkspaceViewModel(repo, project)
        advanceUntilIdle()
        val task = workspaceVm.state.value.workspace.dataOrNull!!.tasks.first { it.status == TaskStatus.ToDo || it.status == TaskStatus.Backlog }

        val panelVm = TaskDetailViewModel(repo, project, task.id)
        advanceUntilIdle()
        panelVm.advance() // Start Task
        advanceUntilIdle()

        fun listStatus() = workspaceVm.state.value.workspace.dataOrNull!!.tasks.first { it.id == task.id }.status
        assertEquals(TaskStatus.InProgress, listStatus())

        panelVm.advance() // Mark as Done
        advanceUntilIdle()
        assertEquals(TaskStatus.Done, listStatus())
    }
}
