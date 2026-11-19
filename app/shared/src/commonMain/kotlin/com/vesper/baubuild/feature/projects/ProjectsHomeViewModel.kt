package com.vesper.baubuild.feature.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.core.load
import com.vesper.baubuild.core.model.Inquiry
import com.vesper.baubuild.core.model.Project
import com.vesper.baubuild.core.session.SessionState
import com.vesper.baubuild.data.repository.InquiryRepository
import com.vesper.baubuild.data.repository.NotificationRepository
import kotlinx.coroutines.CancellationException
import com.vesper.baubuild.data.repository.ProjectRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProjectsHomeUiState(
    val viewer: Viewer = Viewer(),
    val urgent: LoadState<List<Inquiry>> = LoadState.Loading,
    val projects: LoadState<List<Project>> = LoadState.Loading,
    /** Drives the dot on the header bell. Best effort: a failed check keeps the last known value. */
    val hasUnreadNotifications: Boolean = false,
) {
    /** True only for a refresh over existing content (drives the pull-to-refresh indicator). */
    val isRefreshing: Boolean
        get() = (urgent as? LoadState.Content)?.refreshing == true || (projects as? LoadState.Content)?.refreshing == true

    /** How many open projects the home shows before "See All". */
    companion object { const val HOME_PROJECT_LIMIT = 4 }
}

class ProjectsHomeViewModel(
    private val projectRepository: ProjectRepository,
    private val inquiryRepository: InquiryRepository,
    session: StateFlow<SessionState>,
    private val notificationRepository: NotificationRepository? = null,
) : ViewModel() {
    private val hasUnread = MutableStateFlow(false)
    private var unreadJob: Job? = null
    private val urgent = MutableStateFlow<LoadState<List<Inquiry>>>(LoadState.Loading)
    private val projects = MutableStateFlow<LoadState<List<Project>>>(LoadState.Loading)
    private var refreshJob: Job? = null

    val state: StateFlow<ProjectsHomeUiState> = combine(session, urgent, projects, hasUnread) { s, u, p, n ->
        ProjectsHomeUiState(Viewer.of(s), u, p, n)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ProjectsHomeUiState(Viewer.of(session.value)))

    init {
        refresh()
        // Joining a project elsewhere (detail screen, portal) changes what this screen lists.
        viewModelScope.launch { com.vesper.baubuild.core.DataChanges.memberships.collect { refresh() } }
    }

    /** Reloads both sections in parallel; content stays visible while refreshing. */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            launch { urgent.load { inquiryRepository.urgentInquiries().filter { it.isUrgent } } }
            launch { projects.load { projectRepository.openProjects() } }
        }
        refreshUnread()
    }

    /** Re-checks the bell badge (also called when the user comes back from the Notifications screen). */
    fun refreshUnread() {
        val repository = notificationRepository ?: return
        if (unreadJob?.isActive == true) return
        unreadJob = viewModelScope.launch {
            try {
                hasUnread.value = repository.notifications().any { !it.read }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Keep the last known badge; the bell is never worth an error.
            }
        }
    }
}
