package com.vesper.baubuild.data.github

import kotlinx.serialization.Serializable

/** The BAU BUILD GitHub organization that owns the project repos (resource owner for students' tokens). */
const val GitHubOrgName = "bau-build"

/** The GitHub account a personal access token belongs to. */
@Serializable
data class GitHubAccount(val login: String, val name: String? = null, val avatarUrl: String? = null)

@Serializable
data class GitHubRepoInfo(val fullName: String, val defaultBranch: String, val private: Boolean = false)

/** A branch created for a task: `task/<taskId>-<slug>` from the repo's default branch. */
@Serializable
data class TaskBranch(val repoFullName: String, val name: String, val baseBranch: String, val headSha: String)

@Serializable
enum class RepoEntryType { File, Directory }

/** One entry of the repo tree (recursive listing). */
@Serializable
data class RepoEntry(val path: String, val type: RepoEntryType, val size: Long? = null, val sha: String? = null) {
    val name: String get() = path.substringAfterLast('/')
    val depth: Int get() = path.count { it == '/' }
}

@Serializable
data class RepoFile(val path: String, val content: String, val sha: String)

/** A changed file to commit. [content] null = delete the file. */
@Serializable
data class FileChange(val path: String, val content: String?)

@Serializable
data class CommitResult(val sha: String, val url: String, val message: String)

@Serializable
data class PullRequest(val number: Int, val url: String, val title: String)

/** Typed failures so the UI can explain them (bad token, no access, branch exists, conflict…). */
sealed class GitHubException(message: String) : Exception(message) {
    class Unauthorized : GitHubException("Your GitHub token is invalid or expired. Connect GitHub again.")
    class Forbidden(detail: String = "") : GitHubException("Your token can't access this repository. $detail".trim())
    class NotFound(what: String) : GitHubException("$what wasn't found on GitHub.")
    class Conflict(detail: String) : GitHubException(detail)
    class RateLimited : GitHubException("GitHub rate limit reached. Try again in a few minutes.")
    class Network(cause: Throwable? = null) : GitHubException("Couldn’t reach GitHub. Check your connection.")
}
