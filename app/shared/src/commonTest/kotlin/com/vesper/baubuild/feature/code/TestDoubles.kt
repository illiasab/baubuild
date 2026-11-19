package com.vesper.baubuild.feature.code

import com.vesper.baubuild.core.model.Membership
import com.vesper.baubuild.core.model.ProjectTask
import com.vesper.baubuild.core.model.ReviewStatus
import com.vesper.baubuild.core.model.TaskStatus
import com.vesper.baubuild.core.model.Workspace
import com.vesper.baubuild.data.fake.FakeData
import com.vesper.baubuild.data.github.*
import com.vesper.baubuild.data.repository.WorkRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Small in-memory GitHub that behaves like the real API where the app depends on it. */
internal class TestGitHub(connected: Boolean = true) : GitHubRepository {
    private val _account = MutableStateFlow(if (connected) GitHubAccount("sam") else null)
    override val account: StateFlow<GitHubAccount?> = _account

    val files = mutableMapOf(
        "README.md" to "# Library booking\n",
        "src/booking-form.js" to "export function validate(date) {\n  return true;\n}\n",
        "src/util/dates.js" to "export const today = () => new Date();\n",
    )
    val branches = mutableMapOf<String, TaskBranch>()
    val branchCalls = mutableListOf<String>()
    val commits = mutableListOf<Triple<String, String, List<FileChange>>>()
    val pullRequests = mutableListOf<Triple<String, String, String>>() // head, base, body
    var failCommit = false
    var failBranch = false
    var pullRequestAttempts = 0
    private var n = 0

    override suspend fun connect(token: String) = GitHubAccount("sam").also { _account.value = it }
    override suspend fun disconnect() { _account.value = null }
    override suspend fun repo(fullName: String) = GitHubRepoInfo(fullName, "main")

    override suspend fun ensureTaskBranch(fullName: String, branchName: String): TaskBranch {
        branchCalls += branchName
        if (failBranch) throw GitHubException.Forbidden()
        return branches.getOrPut(branchName) { TaskBranch(fullName, branchName, "main", "base0") }
    }

    override suspend fun tree(fullName: String, branch: String): List<RepoEntry> {
        val dirs = files.keys.flatMap { CodeTree.ancestors(it) }.toSet()
        return (dirs.map { RepoEntry(it, RepoEntryType.Directory) } + files.keys.map { RepoEntry(it, RepoEntryType.File) }).sortedBy { it.path }
    }

    override suspend fun file(fullName: String, branch: String, path: String): RepoFile =
        RepoFile(path, files[path] ?: throw GitHubException.NotFound(path), "sha-$path")

    override suspend fun commit(fullName: String, branch: String, message: String, changes: List<FileChange>): CommitResult {
        if (failCommit) throw GitHubException.Network()
        commits += Triple(branch, message, changes)
        changes.forEach { c -> c.content?.let { files[c.path] = it } }
        val sha = "abc1234${n++}def"
        branches[branch]?.let { branches[branch] = it.copy(headSha = sha) }
        return CommitResult(sha, "https://github.com/$fullName/commit/$sha", message)
    }

    override suspend fun openPullRequest(fullName: String, head: String, base: String, title: String, body: String): PullRequest {
        pullRequestAttempts++
        if (commits.none { it.first == head }) throw GitHubException.Conflict("No commits between $base and $head")
        pullRequests += Triple(head, base, body)
        return PullRequest(42, "https://github.com/$fullName/pull/42", title)
    }
}

/** Workspace from FakeData with per-task status overrides. */
internal class TestWork : WorkRepository {
    val writes = mutableListOf<Pair<String, TaskStatus>>()
    private val statuses = mutableMapOf<String, TaskStatus>()
    override suspend fun myWork(): List<Membership> = emptyList()
    override suspend fun reviews(): List<ReviewStatus> = emptyList()
    override suspend fun workspace(projectId: String): Workspace {
        val base = FakeData.workspace(projectId)
        return base.copy(tasks = base.tasks.map { t -> statuses[t.id]?.let { t.copy(status = it) } ?: t })
    }
    override suspend fun updateTaskStatus(projectId: String, taskId: String, status: TaskStatus): ProjectTask {
        writes += taskId to status
        statuses[taskId] = status
        return workspace(projectId).tasks.first { it.id == taskId }
    }
}
