package com.vesper.baubuild.feature.code

import com.vesper.baubuild.core.model.ProjectTask
import com.vesper.baubuild.data.github.CommitResult
import com.vesper.baubuild.data.github.PullRequest
import com.vesper.baubuild.data.github.TaskBranch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A file the student opened. [original] is the content on the branch (as last fetched or committed), [text] the
 * current edit, [savedText] what ⌘S last "saved" locally (no commit). [revision] bumps only on changes that don't
 * come from typing (discard, reload), so the editor knows when to replace its own text/selection.
 */
data class FileDraft(
    val path: String,
    val original: String,
    val text: String = original,
    val savedText: String = text,
    val revision: Int = 0,
) {
    val isChanged: Boolean get() = text != original
    val isUnsaved: Boolean get() = text != savedText
}

/** Everything about one task's code session that must outlive the editor screen. */
data class TaskCodeSession(
    val branch: TaskBranch? = null,
    val files: Map<String, FileDraft> = emptyMap(),
    val tabs: List<String> = emptyList(),
    val activePath: String? = null,
    val commits: List<CommitResult> = emptyList(),
    val pullRequest: PullRequest? = null,
    val commitMessage: String? = null,
    val checkedCriteria: Set<Int> = emptySet(),
    /** Head of the task branch when Start Task created it; unchanged head + no commits here = nothing to review. */
    val createdHeadSha: String? = null,
) {
    val changedFiles: List<FileDraft> get() = files.values.filter { it.isChanged }.sortedBy { it.path }
}

/**
 * In-memory home of every task's code session (unsaved edits, open tabs, commits made, PR), keyed by project + task.
 * Leaving the editor, popping the screen or recreating the ViewModel never loses work; it lives for the app process.
 * TODO(persistence): also write drafts to disk so they survive process death.
 */
class CodeSessionStore {
    private val _sessions = MutableStateFlow<Map<String, TaskCodeSession>>(emptyMap())
    val sessions: StateFlow<Map<String, TaskCodeSession>> = _sessions.asStateFlow()

    fun get(projectId: String, taskId: String): TaskCodeSession = _sessions.value[key(projectId, taskId)] ?: TaskCodeSession()

    fun update(projectId: String, taskId: String, transform: (TaskCodeSession) -> TaskCodeSession): TaskCodeSession {
        val k = key(projectId, taskId)
        var result = TaskCodeSession()
        _sessions.update { all -> all + (k to transform(all[k] ?: TaskCodeSession()).also { result = it }) }
        return result
    }

    companion object {
        fun key(projectId: String, taskId: String) = "$projectId/$taskId"

        /** App-wide instance used by default. Tests pass their own. */
        val shared = CodeSessionStore()
    }
}

/** Default commit message / PR title. */
internal fun ProjectTask.commitTitle(): String = title

/** PR body: the task's "Done when…" list as a GitHub checklist. */
internal fun ProjectTask.pullRequestBody(): String = buildString {
    if (description.isNotBlank()) append(description.trim()).append("\n\n")
    if (acceptanceCriteria.isNotEmpty()) {
        append("### Done when\n")
        acceptanceCriteria.forEach { append("- [ ] ").append(it).append('\n') }
    }
    append("\n_Opened from BAU BUILD._")
}
