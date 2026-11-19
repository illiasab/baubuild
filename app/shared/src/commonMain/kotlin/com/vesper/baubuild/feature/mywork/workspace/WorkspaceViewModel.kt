package com.vesper.baubuild.feature.mywork.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.core.load
import com.vesper.baubuild.core.model.Workspace
import com.vesper.baubuild.data.repository.WorkRepository
import com.vesper.baubuild.feature.mywork.task.TaskUpdate
import com.vesper.baubuild.feature.mywork.task.TaskUpdateBus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class WorkspaceSegment(val label: String) { Tasks("My Tasks"), Changes("My Changes") }

data class WorkspaceUiState(
    val workspace: LoadState<Workspace> = LoadState.Loading,
    val segment: WorkspaceSegment = WorkspaceSegment.Tasks,
) {
    val isRefreshing: Boolean get() = (workspace as? LoadState.Content)?.refreshing == true
}

class WorkspaceViewModel(
    private val repository: WorkRepository,
    val projectId: String,
    private val taskUpdates: TaskUpdateBus = TaskUpdateBus.shared,
) : ViewModel() {
    private val workspace = MutableStateFlow<LoadState<Workspace>>(LoadState.Loading)
    private val segment = MutableStateFlow(WorkspaceSegment.Tasks)
    private var refreshJob: Job? = null

    val state: StateFlow<WorkspaceUiState> = combine(workspace, segment, ::WorkspaceUiState)
        .stateIn(viewModelScope, SharingStarted.Eagerly, WorkspaceUiState())

    init {
        refresh()
        // A task changed on the detail screen: patch it in place so the list is right when the user comes back.
        viewModelScope.launch { taskUpdates.updates.collect(::applyTaskUpdate) }
    }

    private fun applyTaskUpdate(update: TaskUpdate) {
        if (update.projectId != projectId) return
        val current = workspace.value as? LoadState.Content ?: return
        val tasks = current.data.tasks
        if (tasks.none { it.id == update.task.id }) return
        workspace.value = current.copy(data = current.data.copy(tasks = tasks.map { if (it.id == update.task.id) update.task else it }))
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch { workspace.load { repository.workspace(projectId) } }
    }

    fun selectSegment(value: WorkspaceSegment) { segment.value = value }
}
