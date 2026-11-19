package com.vesper.baubuild.feature.mywork.task

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.core.dataOrNull
import com.vesper.baubuild.core.load
import com.vesper.baubuild.core.model.Project
import com.vesper.baubuild.core.model.ProjectTask
import com.vesper.baubuild.core.model.TaskStatus
import com.vesper.baubuild.data.github.GitHubException
import com.vesper.baubuild.data.github.GitHubRepository
import com.vesper.baubuild.data.github.PullRequest
import com.vesper.baubuild.data.github.taskBranchName
import com.vesper.baubuild.data.repository.WorkRepository
import com.vesper.baubuild.feature.code.CodeSessionStore
import com.vesper.baubuild.feature.code.NoCommitsException
import com.vesper.baubuild.feature.code.openTaskPullRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A task together with the project it belongs to (for the "Library · Easy" breadcrumb). */
data class TaskDetail(val project: Project, val task: ProjectTask)

/** The one forward step the student can take on a task from the detail screen. */
enum class TaskAction(val label: String, val target: TaskStatus) {
    Start("Start Task", TaskStatus.InProgress),
    MarkDone("Mark as Done", TaskStatus.Done),
}

val ProjectTask.nextAction: TaskAction?
    get() = when (status) {
        TaskStatus.Backlog, TaskStatus.ToDo -> TaskAction.Start
        TaskStatus.InProgress -> TaskAction.MarkDone
        TaskStatus.Done -> null
    }

data class TaskDetailUiState(
    val detail: LoadState<TaskDetail> = LoadState.Loading,
    /** Indexes into [ProjectTask.acceptanceCriteria] the student ticked. Local only. */
    val checkedCriteria: Set<Int> = emptySet(),
    val isUpdating: Boolean = false,
    val updateError: String? = null,
    /** The project has a GitHub repo and the app can code on it (Start Task creates a branch, Open Code). */
    val codeEnabled: Boolean = false,
    /** Inline progress for multi-step actions: "Creating branch task/…", "Opening pull request…". */
    val progressLabel: String? = null,
    /** The task branch, once created (or known). Shown in monospace. */
    val branchName: String? = null,
    /** Start Task needs GitHub: show ConnectGitHubPrompt. */
    val showConnectGitHub: Boolean = false,
    /** One-shot: the task was started, open the code workspace. Consumed via [TaskDetailViewModel.onCodeOpened]. */
    val openCodeRequested: Boolean = false,
    /** PR opened by "Mark as Done": "Pull request #N opened · View". */
    val pullRequest: PullRequest? = null,
) {
    val task: ProjectTask? get() = detail.dataOrNull?.task
    val nextAction: TaskAction? get() = task?.nextAction
    /** "Open Code" is the primary action while a code-enabled task is in progress. */
    val canOpenCode: Boolean get() = codeEnabled && task?.status == TaskStatus.InProgress
    val deadline: TaskDeadline? get() = task?.let(::deadlineOf)
}

/** A task's status changed. Lets the workspace list update without a reload. */
data class TaskUpdate(val projectId: String, val task: ProjectTask)

/** In-process signal from [TaskDetailViewModel] to whatever shows the task list (mobile workspace, portal). */
class TaskUpdateBus {
    private val _updates = MutableSharedFlow<TaskUpdate>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val updates: SharedFlow<TaskUpdate> = _updates.asSharedFlow()

    fun publish(update: TaskUpdate) { _updates.tryEmit(update) }

    companion object {
        /** App-wide instance used by default. Tests pass their own. */
        val shared = TaskUpdateBus()
    }
}

/**
 * Task detail: loads the task from its project's workspace (there's no single-task endpoint yet), lets the
 * student tick acceptance criteria locally and move the task forward (Start → In Progress → Done).
 */
class TaskDetailViewModel(
    private val repository: WorkRepository,
    val projectId: String,
    val taskId: String,
    private val updates: TaskUpdateBus = TaskUpdateBus.shared,
    /** Null = no in-app code workspace (status changes only). The screens pass `AppGraph.current.gitHubRepository`. */
    private val github: GitHubRepository? = null,
    private val codeStore: CodeSessionStore = CodeSessionStore.shared,
) : ViewModel() {
    private val detail = MutableStateFlow<LoadState<TaskDetail>>(LoadState.Loading)
    private val _state = MutableStateFlow(TaskDetailUiState())
    val state: StateFlow<TaskDetailUiState> = _state.asStateFlow()
    private var refreshJob: Job? = null
    private var updateJob: Job? = null
    /** Start Task is waiting for GitHub to be connected. */
    private var pendingStart = false

    init {
        viewModelScope.launch {
            detail.collect { d ->
                _state.update { it.copy(detail = d) }
                syncCodeState()
            }
        }
        viewModelScope.launch { codeStore.sessions.collect { syncCodeState() } }
        refresh()
    }

    private fun syncCodeState() {
        val d = detail.value.dataOrNull ?: return
        val enabled = github != null && d.project.repoFullName != null
        val session = codeStore.get(projectId, taskId)
        val branch = session.branch?.name
            ?: if (enabled && d.task.status == TaskStatus.InProgress) taskBranchName(d.task.id, d.task.title) else null
        _state.update {
            it.copy(codeEnabled = enabled, branchName = branch, pullRequest = session.pullRequest ?: it.pullRequest)
        }
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            detail.load {
                val workspace = repository.workspace(projectId)
                val task = workspace.tasks.firstOrNull { it.id == taskId }
                    ?: throw NoSuchElementException("Task $taskId not in project $projectId")
                TaskDetail(workspace.project, task)
            }
        }
    }

    // TODO(backend): persist ticked criteria per task (e.g. `PUT /v1/projects/{id}/tasks/{taskId}/checklist`)
    //  so they survive app restarts and sync across devices. For now they live as long as this ViewModel.
    fun toggleCriterion(index: Int) {
        val count = _state.value.task?.acceptanceCriteria?.size ?: return
        if (index !in 0 until count) return
        _state.update { s ->
            s.copy(checkedCriteria = if (index in s.checkedCriteria) s.checkedCriteria - index else s.checkedCriteria + index)
        }
    }

    /**
     * Runs the task's [nextAction]. Keeps everything visible on failure and shows an inline error.
     *
     * With a code workspace: Start Task → (connect GitHub) → create the task branch → In Progress → open the code.
     * Mark as Done → open the pull request (needs commits on the branch) → Done.
     */
    fun advance() {
        val action = _state.value.nextAction ?: return
        if (updateJob?.isActive == true) return
        val code = _state.value.codeEnabled
        when {
            !code -> setStatus(action.target)
            action == TaskAction.Start -> {
                if (github?.account?.value == null) {
                    pendingStart = true
                    _state.update { it.copy(showConnectGitHub = true, updateError = null) }
                } else {
                    startWithBranch()
                }
            }
            else -> markDoneWithPullRequest()
        }
    }

    private fun setStatus(target: TaskStatus) {
        _state.update { it.copy(isUpdating = true, updateError = null) }
        updateJob = viewModelScope.launch {
            try {
                applyStatus(target)
                _state.update { it.copy(isUpdating = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update {
                    it.copy(isUpdating = false, updateError = "Couldn’t update the task. Check your connection and try again.")
                }
            }
        }
    }

    private suspend fun applyStatus(target: TaskStatus) {
        val updated = repository.updateTaskStatus(projectId, taskId, target)
        val current = detail.value.dataOrNull
        if (current != null) detail.value = LoadState.Content(current.copy(task = updated))
        updates.publish(TaskUpdate(projectId, updated))
    }

    private fun startWithBranch() {
        val gh = github ?: return
        val d = detail.value.dataOrNull ?: return
        val repo = d.project.repoFullName ?: return
        val name = taskBranchName(d.task.id, d.task.title)
        _state.update { it.copy(isUpdating = true, updateError = null, progressLabel = "Creating branch $name…") }
        updateJob = viewModelScope.launch {
            try {
                val branch = codeStore.get(projectId, taskId).branch?.takeIf { it.name == name } ?: gh.ensureTaskBranch(repo, name)
                codeStore.update(projectId, taskId) { it.copy(branch = branch, createdHeadSha = it.createdHeadSha ?: branch.headSha) }
                _state.update { it.copy(branchName = branch.name, progressLabel = "Starting task…") }
                applyStatus(TaskStatus.InProgress)
                _state.update { it.copy(isUpdating = false, progressLabel = null, openCodeRequested = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update {
                    it.copy(
                        isUpdating = false,
                        progressLabel = null,
                        updateError = failureMessage(e),
                        showConnectGitHub = e is GitHubException.Unauthorized,
                    )
                }
                if (e is GitHubException.Unauthorized) pendingStart = true
            }
        }
    }

    private fun markDoneWithPullRequest() {
        val gh = github ?: return
        val d = detail.value.dataOrNull ?: return
        val repo = d.project.repoFullName ?: return
        _state.update { it.copy(isUpdating = true, updateError = null, progressLabel = "Opening pull request…") }
        updateJob = viewModelScope.launch {
            try {
                val pr = openTaskPullRequest(gh, codeStore, projectId, repo, d.task)
                _state.update { it.copy(pullRequest = pr, progressLabel = "Marking as done…") }
                applyStatus(TaskStatus.Done)
                _state.update { it.copy(isUpdating = false, progressLabel = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(isUpdating = false, progressLabel = null, updateError = failureMessage(e)) }
            }
        }
    }

    private fun failureMessage(e: Throwable): String = when (e) {
        is NoCommitsException -> e.message!!
        is GitHubException -> e.message ?: "GitHub didn’t respond. Try again."
        else -> "Couldn’t update the task. Check your connection and try again."
    }

    /** ConnectGitHubPrompt finished: resume Start Task if it was waiting. */
    fun onGitHubConnected() {
        _state.update { it.copy(showConnectGitHub = false) }
        if (pendingStart) {
            pendingStart = false
            if (_state.value.nextAction == TaskAction.Start && github?.account?.value != null) startWithBranch()
        }
    }

    fun dismissGitHubPrompt() {
        pendingStart = false
        _state.update { it.copy(showConnectGitHub = false) }
    }

    fun onCodeOpened() = _state.update { it.copy(openCodeRequested = false) }

    fun dismissError() = _state.update { it.copy(updateError = null) }
}
