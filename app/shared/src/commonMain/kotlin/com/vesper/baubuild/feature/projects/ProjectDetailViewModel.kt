package com.vesper.baubuild.feature.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.core.dataOrNull
import com.vesper.baubuild.core.load
import com.vesper.baubuild.core.model.MembershipStatus
import com.vesper.baubuild.core.model.Project
import com.vesper.baubuild.core.session.SessionState
import com.vesper.baubuild.data.repository.ProjectRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** State of the "I'm In" / "Request" write. */
sealed interface JoinState {
    data object Idle : JoinState
    data object Joining : JoinState
    /**
     * [connectGitHubRepo] non-null = ask the user to connect GitHub next (they're In, the project has a repo and
     * GitHub isn't connected); it's the repo the token guide should name.
     */
    data class Joined(val status: MembershipStatus, val connectGitHubRepo: String? = null) : JoinState
    data class Failed(val message: String) : JoinState
}

data class ProjectDetailUiState(
    val viewer: Viewer = Viewer(),
    val project: LoadState<Project> = LoadState.Loading,
    val join: JoinState = JoinState.Idle,
) {
    val eligibility: JoinEligibility?
        get() = project.dataOrNull?.eligibilityFor(viewer.score)

    /** The CTA is tappable only for a loaded project the viewer qualifies for and has not joined yet. */
    val canJoin: Boolean
        get() = eligibility == JoinEligibility.Eligible && (join is JoinState.Idle || join is JoinState.Failed)
}

class ProjectDetailViewModel(
    private val projectId: String,
    private val repository: ProjectRepository,
    private val session: StateFlow<SessionState>,
    /** Row data the user tapped, shown instantly while the full project refreshes. */
    seed: Project? = null,
    /** Whether GitHub is connected right now (e.g. `{ gitHubRepository.account.value != null }`). Default: never prompt. */
    private val isGitHubConnected: () -> Boolean = { true },
) : ViewModel() {
    private val project = MutableStateFlow<LoadState<Project>>(
        seed?.takeIf { it.id == projectId }?.let { LoadState.Content(it) } ?: LoadState.Loading,
    )
    private val join = MutableStateFlow<JoinState>(JoinState.Idle)
    private var refreshJob: Job? = null

    val state: StateFlow<ProjectDetailUiState> = combine(session, project, join) { s, p, j ->
        ProjectDetailUiState(Viewer.of(s), p, j)
    }.stateIn(
        viewModelScope, SharingStarted.Eagerly,
        ProjectDetailUiState(Viewer.of(session.value), project.value, join.value),
    )

    init { refresh() }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch { project.load { repository.project(projectId) } }
    }

    fun join() {
        // Read the source flows directly: `state` is derived asynchronously and could let a double tap through.
        val current = ProjectDetailUiState(Viewer.of(session.value), project.value, join.value)
        if (!current.canJoin) return
        join.value = JoinState.Joining
        viewModelScope.launch {
            join.value = try {
                val status = repository.join(projectId).status
                JoinState.Joined(status, gitHubPromptRepoAfterJoin(status, project.value.dataOrNull, isGitHubConnected()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                JoinState.Failed("Couldn’t join right now. Check your connection and try again.")
            }
        }
    }

    fun dismissJoinError() {
        join.update { if (it is JoinState.Failed) JoinState.Idle else it }
    }
}

/**
 * After a successful join: the repo to name in the "connect GitHub" prompt, or null for no prompt. Only when the
 * user is In (not waiting for approval), the project has a repo and GitHub isn't connected yet.
 */
internal fun gitHubPromptRepoAfterJoin(status: MembershipStatus, project: Project?, gitHubConnected: Boolean): String? =
    project?.repoFullName?.trim()?.takeIf { status == MembershipStatus.In && !gitHubConnected && it.isNotEmpty() }
