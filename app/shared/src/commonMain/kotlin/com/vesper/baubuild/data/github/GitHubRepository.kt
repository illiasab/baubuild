package com.vesper.baubuild.data.github

import kotlinx.coroutines.flow.StateFlow

/**
 * The student's GitHub, used directly from the app with their personal access token. The token is also registered
 * with the BAU BUILD backend (see [BackendLinkedGitHubRepository] / docs/BACKEND_GITHUB_TOKEN.md) so the org agent
 * can attribute and verify pushes; this interface itself only talks to GitHub.
 * Fine-grained token permissions needed on the project repos: Contents (read & write), Pull requests (read & write),
 * Metadata (read).
 *
 * REST endpoints (api.github.com, `Authorization: Bearer <token>`, `X-GitHub-Api-Version: 2022-11-28`):
 * user → `GET /user`; repo → `GET /repos/{o}/{r}`; branch → `GET /repos/{o}/{r}/git/ref/heads/{base}` +
 * `POST /repos/{o}/{r}/git/refs`; tree → `GET /repos/{o}/{r}/git/trees/{branch}?recursive=1`;
 * file → `GET /repos/{o}/{r}/contents/{path}?ref={branch}`; multi-file commit → Git Data API
 * (`POST blobs` → `POST trees {base_tree}` → `POST commits` → `PATCH refs/heads/{branch}`);
 * PR → `POST /repos/{o}/{r}/pulls`.
 */
interface GitHubRepository {
    /** Connected account, or null. Restored from secure storage on launch. */
    val account: StateFlow<GitHubAccount?>

    /** Validates the token with `GET /user`; on success stores it securely and returns the account. */
    suspend fun connect(token: String): GitHubAccount
    suspend fun disconnect()

    suspend fun repo(fullName: String): GitHubRepoInfo
    /** Creates the task branch from the default branch, or returns it if it already exists. */
    suspend fun ensureTaskBranch(fullName: String, branchName: String): TaskBranch
    suspend fun tree(fullName: String, branch: String): List<RepoEntry>
    suspend fun file(fullName: String, branch: String, path: String): RepoFile
    /** One commit containing all [changes] on [branch]; returns the new commit. */
    suspend fun commit(fullName: String, branch: String, message: String, changes: List<FileChange>): CommitResult
    suspend fun openPullRequest(fullName: String, head: String, base: String, title: String, body: String): PullRequest
}

/** `task/t1-reject-past-dates-in-the-booking-form` (max ~60 chars, git-ref safe). */
fun taskBranchName(taskId: String, taskTitle: String): String {
    val slug = taskTitle.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
        .replace(Regex("-+"), "-").trim('-').take(40).trimEnd('-')
    return "task/${taskId.lowercase().filter { it.isLetterOrDigit() || it == '-' || it == '_' }}-$slug"
}
