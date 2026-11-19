package com.vesper.baubuild.feature.code

import com.vesper.baubuild.core.model.ProjectTask
import com.vesper.baubuild.data.github.GitHubException
import com.vesper.baubuild.data.github.GitHubRepository
import com.vesper.baubuild.data.github.PullRequest
import com.vesper.baubuild.data.github.TaskBranch
import com.vesper.baubuild.data.github.taskBranchName

/** The task branch has nothing to review yet. */
class NoCommitsException : Exception("Commit your changes before marking the task done.")

/**
 * Opens (once) the task's pull request: head = task branch, base = the branch it was cut from, title = task title,
 * body = acceptance criteria checklist. Shared by the code workspace and the task's "Mark as Done".
 *
 * Gating: with commits made in this app session we open straight away. If Start Task created the branch here and its
 * head hasn't moved, there is nothing to review → [NoCommitsException] without asking GitHub. Otherwise the branch
 * may have commits pushed from elsewhere, so we ask GitHub; its "no commits between …" refusal (a
 * [GitHubException.Conflict]) also becomes [NoCommitsException]. "Already exists" counts as opened.
 */
internal suspend fun openTaskPullRequest(
    github: GitHubRepository,
    store: CodeSessionStore,
    projectId: String,
    repoFullName: String,
    task: ProjectTask,
): PullRequest {
    val session = store.get(projectId, task.id)
    session.pullRequest?.let { return it }
    // Idempotent: returns the existing branch with its current head.
    val branch: TaskBranch = github.ensureTaskBranch(repoFullName, session.branch?.name ?: taskBranchName(task.id, task.title))
    store.update(projectId, task.id) { it.copy(branch = branch) }
    if (session.commits.isEmpty() && session.createdHeadSha != null && branch.headSha == session.createdHeadSha) {
        throw NoCommitsException()
    }
    val pr = try {
        github.openPullRequest(repoFullName, head = branch.name, base = branch.baseBranch, title = task.commitTitle(), body = task.pullRequestBody())
    } catch (e: GitHubException.Conflict) {
        val msg = e.message.orEmpty()
        when {
            msg.contains("already exists", ignoreCase = true) -> PullRequest(0, "https://github.com/$repoFullName/pulls", task.title)
            msg.contains("no commits", ignoreCase = true) -> throw NoCommitsException()
            else -> throw e
        }
    }
    store.update(projectId, task.id) { it.copy(pullRequest = pr) }
    return pr
}

/** User-facing text for a failed GitHub call. */
internal fun Throwable.gitHubMessage(fallback: String): String = when (this) {
    is GitHubException -> message ?: fallback
    is NoCommitsException -> message!!
    else -> fallback
}
