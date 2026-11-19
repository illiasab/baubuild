package com.vesper.baubuild.feature.code

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.core.dataOrNull
import com.vesper.baubuild.core.model.Project
import com.vesper.baubuild.core.model.ProjectTask
import com.vesper.baubuild.data.github.CommitResult
import com.vesper.baubuild.data.github.FileChange
import com.vesper.baubuild.data.github.GitHubException
import com.vesper.baubuild.data.github.GitHubRepository
import com.vesper.baubuild.data.github.RepoEntry
import com.vesper.baubuild.data.github.RepoEntryType
import com.vesper.baubuild.data.github.TaskBranch
import com.vesper.baubuild.data.github.taskBranchName
import com.vesper.baubuild.data.repository.WorkRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the workspace is editing: the task, its project's repo and the task branch. */
data class CodeContext(val project: Project, val task: ProjectTask, val branch: TaskBranch) {
    val repoFullName: String get() = branch.repoFullName
}

data class CodeUiState(
    val setup: LoadState<CodeContext> = LoadState.Loading,
    /** Inline progress while setting up, e.g. "Creating branch task/t1-…". */
    val setupStep: String? = null,
    /** GitHub isn't connected (or the token stopped working): show the connect prompt. */
    val needsGitHub: Boolean = false,
    val tree: LoadState<List<RepoEntry>> = LoadState.Loading,
    val expandedDirs: Set<String> = emptySet(),
    /** Mirrors [CodeSessionStore] for this task: drafts, tabs, commits, PR. */
    val session: TaskCodeSession = TaskCodeSession(),
    val loadingFiles: Set<String> = emptySet(),
    val fileErrors: Map<String, String> = emptyMap(),
    /** Files currently shown as a diff instead of the editor. */
    val diffPaths: Set<String> = emptySet(),
    val commitSheetVisible: Boolean = false,
    val excludedFromCommit: Set<String> = emptySet(),
    val isCommitting: Boolean = false,
    val commitError: String? = null,
    /** Last commit made here: "Committed abc1234 · View on GitHub". */
    val lastCommit: CommitResult? = null,
    val isOpeningPullRequest: Boolean = false,
    val pullRequestError: String? = null,
    /** File whose edits the student asked to discard; waiting for confirmation. */
    val pendingDiscard: String? = null,
) {
    val context: CodeContext? get() = setup.dataOrNull
    val activePath: String? get() = session.activePath
    val activeFile: FileDraft? get() = session.activePath?.let { session.files[it] }
    val changedFiles: List<FileDraft> get() = session.changedFiles
    val changedCount: Int get() = changedFiles.size
    val filesToCommit: List<FileDraft> get() = changedFiles.filter { it.path !in excludedFromCommit }
    val commitMessage: String get() = session.commitMessage ?: context?.task?.commitTitle().orEmpty()
    val canCommit: Boolean get() = context != null && !isCommitting && filesToCommit.isNotEmpty() && commitMessage.isNotBlank()
    val canOpenPullRequest: Boolean
        get() = context != null && session.commits.isNotEmpty() && session.pullRequest == null && !isOpeningPullRequest
    val visibleTree: List<RepoEntry> get() = CodeTree.visibleRows(tree.dataOrNull.orEmpty(), expandedDirs)
}

/**
 * In-app code workspace for one task: ensures the task branch, lists the repo tree, opens files in tabs, keeps
 * per-file original vs. edited text, and commits all changed files as one commit.
 *
 * Zero data loss: every edit is written through to [store] synchronously, so leaving the screen or recreating this
 * ViewModel restores the same tabs and unsaved text. A failed commit keeps every edit and shows an inline error.
 */
class CodeSessionViewModel(
    private val work: WorkRepository,
    private val github: GitHubRepository,
    val projectId: String,
    val taskId: String,
    private val store: CodeSessionStore = CodeSessionStore.shared,
) : ViewModel() {
    private val _state = MutableStateFlow(CodeUiState(session = store.get(projectId, taskId)))
    val state: StateFlow<CodeUiState> = _state.asStateFlow()
    private var setupJob: Job? = null
    private var commitJob: Job? = null
    private var prJob: Job? = null
    private val fileJobs = mutableMapOf<String, Job>()

    init {
        viewModelScope.launch {
            github.account.collect { account ->
                val s = _state.value
                if (account == null && s.context == null) _state.update { it.copy(needsGitHub = true) }
                if (account != null && (s.needsGitHub || s.setup !is LoadState.Content)) {
                    _state.update { it.copy(needsGitHub = false) }
                    start()
                }
            }
        }
    }

    /** (Re)runs setup: task → branch → tree → reopen the session's tabs. Safe to call again after a failure. */
    fun start() {
        if (setupJob?.isActive == true) return
        if (github.account.value == null) { _state.update { it.copy(needsGitHub = true) }; return }
        setupJob = viewModelScope.launch {
            val previous = _state.value.setup.dataOrNull
            if (previous == null) _state.update { it.copy(setup = LoadState.Loading) }
            try {
                val workspace = work.workspace(projectId)
                val task = workspace.tasks.firstOrNull { it.id == taskId }
                    ?: throw NoSuchElementException("Task $taskId not in project $projectId")
                val repo = workspace.project.repoFullName
                    ?: throw IllegalStateException("This project isn’t linked to a GitHub repository yet.")
                val branch = store.get(projectId, taskId).branch?.takeIf { it.repoFullName == repo } ?: run {
                    val name = taskBranchName(task.id, task.title)
                    _state.update { it.copy(setupStep = "Creating branch $name…") }
                    github.ensureTaskBranch(repo, name)
                }
                mutateSession { it.copy(branch = branch) }
                _state.update { it.copy(setup = LoadState.Content(CodeContext(workspace.project, task, branch)), setupStep = null) }
                loadTree()
                _state.value.session.tabs.forEach { if (it !in _state.value.session.files) loadFile(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val message = when (e) {
                    is GitHubException -> e.message ?: "GitHub didn’t respond."
                    is IllegalStateException -> e.message ?: "Couldn’t open the code workspace."
                    else -> "Couldn’t open the code workspace. Check your connection and try again."
                }
                _state.update {
                    it.copy(
                        setup = if (previous != null) LoadState.Content(previous, stale = true) else LoadState.Failed(message),
                        setupStep = null,
                        needsGitHub = it.needsGitHub || e is GitHubException.Unauthorized,
                    )
                }
            }
        }
    }

    fun loadTree() {
        val ctx = _state.value.context ?: return
        viewModelScope.launch {
            val previous = _state.value.tree.dataOrNull
            if (previous != null) _state.update { it.copy(tree = LoadState.Content(previous, refreshing = true)) }
            try {
                val entries = github.tree(ctx.repoFullName, ctx.branch.name)
                _state.update { s ->
                    val expanded = if (previous == null) CodeTree.defaultExpanded(entries, s.session.tabs) else s.expandedDirs
                    s.copy(tree = LoadState.Content(entries), expandedDirs = expanded)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update {
                    it.copy(tree = if (previous != null) LoadState.Content(previous, stale = true) else LoadState.Failed(e.gitHubMessage("Couldn’t load the files.")))
                }
            }
        }
    }

    fun toggleDir(path: String) = _state.update {
        it.copy(expandedDirs = if (path in it.expandedDirs) it.expandedDirs - path else it.expandedDirs + path)
    }

    /** Opens [path] in a tab (max [MaxTabs]; the oldest unchanged tab makes room) and loads it if needed. */
    fun openFile(path: String) {
        val entry = _state.value.tree.dataOrNull?.firstOrNull { it.path == path }
        if (entry?.type == RepoEntryType.Directory) { toggleDir(path); return }
        mutateSession { s ->
            var tabs = if (path in s.tabs) s.tabs else s.tabs + path
            while (tabs.size > MaxTabs) {
                val victim = tabs.firstOrNull { it != path && s.files[it]?.isChanged != true } ?: tabs.first { it != path }
                tabs = tabs - victim
            }
            s.copy(tabs = tabs, activePath = path)
        }
        _state.update { it.copy(expandedDirs = it.expandedDirs + CodeTree.ancestors(path)) }
        if (path !in _state.value.session.files) loadFile(path)
    }

    fun retryFile(path: String) = loadFile(path)

    private fun loadFile(path: String) {
        val ctx = _state.value.context ?: return
        if (fileJobs[path]?.isActive == true) return
        _state.update { it.copy(loadingFiles = it.loadingFiles + path, fileErrors = it.fileErrors - path) }
        fileJobs[path] = viewModelScope.launch {
            try {
                val file = github.file(ctx.repoFullName, ctx.branch.name, path)
                // Never overwrite a draft that appeared meanwhile.
                mutateSession { s -> if (path in s.files) s else s.copy(files = s.files + (path to FileDraft(path, file.content))) }
                _state.update { it.copy(loadingFiles = it.loadingFiles - path) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update {
                    it.copy(loadingFiles = it.loadingFiles - path, fileErrors = it.fileErrors + (path to e.gitHubMessage("Couldn’t open this file.")))
                }
            }
        }
    }

    fun selectTab(path: String) = mutateSession { if (path in it.tabs) it.copy(activePath = path) else it }

    /** Closing a tab never loses edits: changed files stay in the session (and in the commit list). */
    fun closeTab(path: String) = mutateSession { s ->
        val i = s.tabs.indexOf(path)
        if (i < 0) return@mutateSession s
        val tabs = s.tabs - path
        val active = if (s.activePath == path) tabs.getOrNull(i) ?: tabs.lastOrNull() else s.activePath
        val files = if (s.files[path]?.isChanged == true) s.files else s.files - path
        s.copy(tabs = tabs, activePath = active, files = files)
    }

    fun edit(path: String, text: String) = mutateSession { s ->
        val draft = s.files[path] ?: return@mutateSession s
        if (draft.text == text) s else s.copy(files = s.files + (path to draft.copy(text = text)))
    }

    /** ⌘/Ctrl+S: marks the file saved locally (clears the unsaved dot). Nothing is sent to GitHub. */
    fun markSaved(path: String? = _state.value.activePath) {
        path ?: return
        mutateSession { s ->
            val d = s.files[path] ?: return@mutateSession s
            s.copy(files = s.files + (path to d.copy(savedText = d.text)))
        }
    }

    fun toggleDiff(path: String) = _state.update {
        it.copy(diffPaths = if (path in it.diffPaths) it.diffPaths - path else it.diffPaths + path)
    }

    fun requestDiscard(path: String) {
        if (_state.value.session.files[path]?.isChanged == true) _state.update { it.copy(pendingDiscard = path) }
    }

    fun cancelDiscard() = _state.update { it.copy(pendingDiscard = null) }

    fun confirmDiscard() {
        val path = _state.value.pendingDiscard ?: return
        mutateSession { s ->
            val d = s.files[path] ?: return@mutateSession s
            s.copy(files = s.files + (path to d.copy(text = d.original, savedText = d.original, revision = d.revision + 1)))
        }
        _state.update { it.copy(pendingDiscard = null, diffPaths = it.diffPaths - path, excludedFromCommit = it.excludedFromCommit - path) }
    }

    // --- Commit ---------------------------------------------------------------------------------------------------

    fun openCommitSheet() = _state.update { it.copy(commitSheetVisible = true, commitError = null) }
    fun dismissCommitSheet() { if (!_state.value.isCommitting) _state.update { it.copy(commitSheetVisible = false) } }
    fun setCommitMessage(message: String) = mutateSession { it.copy(commitMessage = message) }

    fun toggleCommitFile(path: String) = _state.update {
        it.copy(excludedFromCommit = if (path in it.excludedFromCommit) it.excludedFromCommit - path else it.excludedFromCommit + path)
    }

    /** Commit & Push: one commit with every included changed file. Edits are kept on failure. */
    fun commit() {
        val s = _state.value
        val ctx = s.context ?: return
        if (commitJob?.isActive == true) return
        val files = s.filesToCommit
        if (files.isEmpty()) { _state.update { it.copy(commitError = "No changes to commit.") }; return }
        val message = s.commitMessage.trim()
        if (message.isEmpty()) { _state.update { it.copy(commitError = "Write a commit message.") }; return }
        val committed = files.associate { it.path to it.text }
        _state.update { it.copy(isCommitting = true, commitError = null) }
        commitJob = viewModelScope.launch {
            try {
                val result = github.commit(ctx.repoFullName, ctx.branch.name, message, committed.map { (p, t) -> FileChange(p, t) })
                mutateSession { session ->
                    val updated = session.files.mapValues { (path, d) ->
                        val sent = committed[path] ?: return@mapValues d
                        // Text typed while the commit was in flight stays changed.
                        d.copy(original = sent, savedText = if (d.savedText == sent || d.text == sent) d.text else d.savedText)
                    }
                    session.copy(files = updated, commits = session.commits + result, commitMessage = null)
                }
                _state.update {
                    it.copy(
                        isCommitting = false, commitSheetVisible = false, lastCommit = result,
                        excludedFromCommit = it.excludedFromCommit - committed.keys, diffPaths = it.diffPaths - committed.keys,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update {
                    it.copy(
                        isCommitting = false,
                        commitError = e.gitHubMessage("Couldn’t commit. Your changes are safe here — try again."),
                        needsGitHub = it.needsGitHub || e is GitHubException.Unauthorized,
                    )
                }
            }
        }
    }

    fun dismissLastCommit() = _state.update { it.copy(lastCommit = null) }

    // --- Pull request ---------------------------------------------------------------------------------------------

    fun openPullRequest() {
        val s = _state.value
        val ctx = s.context ?: return
        if (!s.canOpenPullRequest || prJob?.isActive == true) return
        _state.update { it.copy(isOpeningPullRequest = true, pullRequestError = null) }
        prJob = viewModelScope.launch {
            try {
                openTaskPullRequest(github, store, projectId, ctx.repoFullName, ctx.task)
                _state.update { it.copy(isOpeningPullRequest = false, session = store.get(projectId, taskId)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(isOpeningPullRequest = false, pullRequestError = e.gitHubMessage("Couldn’t open the pull request.")) }
            }
        }
    }

    fun toggleCriterion(index: Int) {
        val count = _state.value.context?.task?.acceptanceCriteria?.size ?: return
        if (index !in 0 until count) return
        mutateSession { it.copy(checkedCriteria = if (index in it.checkedCriteria) it.checkedCriteria - index else it.checkedCriteria + index) }
    }

    /** GitHub connected from the prompt: setup resumes via the account collector. */
    fun onGitHubConnected() {
        _state.update { it.copy(needsGitHub = false) }
        if (_state.value.setup !is LoadState.Content) start()
    }
    fun dismissGitHubPrompt() = _state.update { it.copy(needsGitHub = false) }

    private fun mutateSession(transform: (TaskCodeSession) -> TaskCodeSession) {
        val updated = store.update(projectId, taskId, transform)
        _state.update { it.copy(session = updated) }
    }

    companion object {
        const val MaxTabs = 8
    }
}
