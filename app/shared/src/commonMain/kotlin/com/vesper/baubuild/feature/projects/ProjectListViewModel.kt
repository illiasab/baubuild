package com.vesper.baubuild.feature.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.core.load
import com.vesper.baubuild.core.model.BauClass
import com.vesper.baubuild.core.model.Difficulty
import com.vesper.baubuild.core.model.Project
import com.vesper.baubuild.core.session.SessionState
import com.vesper.baubuild.data.repository.ClassRepository
import com.vesper.baubuild.data.repository.ProjectRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProjectListUiState(
    val title: String,
    val subtitle: String,
    val viewer: Viewer = Viewer(),
    val projects: LoadState<List<Project>> = LoadState.Loading,
) {
    val isRefreshing: Boolean get() = (projects as? LoadState.Content)?.refreshing == true
}

/** Lists open projects for one [ProjectListSource] (Easy, Hard, a class, or all). */
class ProjectListViewModel(
    private val source: ProjectListSource,
    private val repository: ProjectRepository,
    session: StateFlow<SessionState>,
) : ViewModel() {
    private val projects = MutableStateFlow<LoadState<List<Project>>>(LoadState.Loading)
    private var refreshJob: Job? = null

    private val title = when (source) {
        ProjectListSource.AllOpen -> "Open Projects"
        is ProjectListSource.ByDifficulty -> "${source.difficulty.label} Projects"
        is ProjectListSource.ForClass -> source.code
    }
    private val subtitle = when (source) {
        ProjectListSource.AllOpen -> "Everything you can join right now."
        is ProjectListSource.ByDifficulty -> when (source.difficulty) {
            Difficulty.Easy -> "Good first projects. Everyone welcome."
            Difficulty.Medium -> "A step up. Most are open to every score."
            Difficulty.Hard -> "Bigger bounties. Some need a minimum score or approval."
        }
        is ProjectListSource.ForClass -> source.name.ifBlank { "Projects from your class" }
    }

    val state: StateFlow<ProjectListUiState> = combine(session, projects) { s, p ->
        ProjectListUiState(title, subtitle, Viewer.of(s), p)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ProjectListUiState(title, subtitle, Viewer.of(session.value)))

    init {
        refresh()
        viewModelScope.launch { com.vesper.baubuild.core.DataChanges.memberships.collect { refresh() } }
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            projects.load {
                when (source) {
                    ProjectListSource.AllOpen -> repository.openProjects()
                    is ProjectListSource.ByDifficulty -> repository.openProjects(source.difficulty)
                    is ProjectListSource.ForClass -> repository.projectsForClass(source.code)
                }
            }
        }
    }
}

/** The student's classes ("My Classes"). */
class MyClassesViewModel(private val repository: ClassRepository) : ViewModel() {
    private val _state = MutableStateFlow<LoadState<List<BauClass>>>(LoadState.Loading)
    val state: StateFlow<LoadState<List<BauClass>>> = _state.asStateFlow()
    private var refreshJob: Job? = null

    init { refresh() }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch { _state.load { repository.myClasses() } }
    }
}
