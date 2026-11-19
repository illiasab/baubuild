package com.vesper.baubuild.feature.mywork.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.core.load
import com.vesper.baubuild.core.model.Workspace
import com.vesper.baubuild.data.repository.WorkRepository
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
) : ViewModel() {
    private val workspace = MutableStateFlow<LoadState<Workspace>>(LoadState.Loading)
    private val segment = MutableStateFlow(WorkspaceSegment.Tasks)
    private var refreshJob: Job? = null

    val state: StateFlow<WorkspaceUiState> = combine(workspace, segment, ::WorkspaceUiState)
        .stateIn(viewModelScope, SharingStarted.Eagerly, WorkspaceUiState())

    init { refresh() }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch { workspace.load { repository.workspace(projectId) } }
    }

    fun selectSegment(value: WorkspaceSegment) { segment.value = value }
}
