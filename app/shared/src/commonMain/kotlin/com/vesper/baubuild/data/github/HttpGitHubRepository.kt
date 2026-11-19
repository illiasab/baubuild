package com.vesper.baubuild.data.github

import com.vesper.baubuild.core.network.AppJson
import com.vesper.baubuild.core.session.TokenStore
import com.vesper.baubuild.core.session.platformSecretStore
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.appendPathSegments
import io.ktor.http.content.TextContent
import io.ktor.http.takeFrom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * [GitHubRepository] talking straight to the GitHub REST API with the student's personal access token.
 *
 * - Own [HttpClient] (never the backend client): the BAU session token is never sent to GitHub, and the GitHub
 *   token is never sent to our backend by this class (the one-time registration is [BackendLinkedGitHubRepository]).
 * - The token lives in [tokenStore] (Keychain / Keystore / prefs under the `github` name). On init the stored
 *   token is validated in the background with `GET /user`; failures are silent, a 401 clears the token.
 * - Any 401 later on also clears the token and [account], so the UI asks the student to reconnect.
 * - Errors are mapped to [GitHubException]: 401 → Unauthorized, 403/429 with rate-limit headers → RateLimited,
 *   other 403 → Forbidden, 404 → NotFound, 409/422 → Conflict (GitHub's message), IO/5xx → Network.
 * - Idempotent GETs are retried with exponential backoff on 502/503/504 and IO errors.
 */
class HttpGitHubRepository(
    private val tokenStore: TokenStore = platformSecretStore("github"),
    engine: HttpClientEngine? = null,
    private val baseUrl: String = "https://api.github.com",
    restoreOnInit: Boolean = true,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : GitHubRepository {

    private val clientConfig: HttpClientConfig<*>.() -> Unit = {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 30_000
        }
        install(HttpRequestRetry) {
            maxRetries = 3
            retryIf { request, response ->
                request.method == HttpMethod.Get && response.status.value in setOf(502, 503, 504)
            }
            retryOnExceptionIf { request, _ -> request.method == HttpMethod.Get }
            exponentialDelay()
        }
        expectSuccess = false
    }
    private val client: HttpClient = if (engine != null) HttpClient(engine, clientConfig) else HttpClient(clientConfig)

    private val _account = MutableStateFlow<GitHubAccount?>(null)
    override val account: StateFlow<GitHubAccount?> = _account.asStateFlow()

    private val stateLock = Mutex()
    private var cachedToken: String? = null

    init {
        if (restoreOnInit) scope.launch { restore() }
    }

    /** Validates the stored token (if any) and publishes the account. Never throws. */
    internal suspend fun restore() {
        val stored = runCatching { tokenStore.read() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        try {
            val user = fetchUser(stored)
            stateLock.withLock {
                if (cachedToken == null && _account.value == null) {
                    cachedToken = stored
                    _account.value = user
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GitHubException.Unauthorized) {
            stateLock.withLock {
                if (cachedToken == null) runCatching { tokenStore.clear() }
            }
        } catch (_: Throwable) {
            // Offline / GitHub down: keep the token, try again on the next request or launch.
            stateLock.withLock { if (cachedToken == null) cachedToken = stored }
        }
    }

    override suspend fun connect(token: String): GitHubAccount {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) throw GitHubException.Unauthorized()
        val user = fetchUser(trimmed)
        stateLock.withLock {
            cachedToken = trimmed
            _account.value = user
        }
        runCatching { tokenStore.write(trimmed) }
        return user
    }

    override suspend fun disconnect() {
        stateLock.withLock {
            cachedToken = null
            _account.value = null
        }
        runCatching { tokenStore.clear() }
    }

    override suspend fun repo(fullName: String): GitHubRepoInfo {
        val json = get("Repository $fullName", repoPath(fullName)).obj()
        return GitHubRepoInfo(
            fullName = json.str("full_name") ?: fullName,
            defaultBranch = json.str("default_branch") ?: "main",
            private = json["private"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    override suspend fun ensureTaskBranch(fullName: String, branchName: String): TaskBranch {
        val base = repo(fullName).defaultBranch
        val baseSha = headSha(fullName, base)
        return try {
            val created = send(HttpMethod.Post, "Repository $fullName", repoPath(fullName) + listOf("git", "refs"), body = buildJsonObject {
                put("ref", "refs/heads/$branchName")
                put("sha", baseSha)
            }).obj()
            TaskBranch(fullName, branchName, base, created["object"]?.jsonObject?.str("sha") ?: baseSha)
        } catch (e: GitHubException.Conflict) {
            if (e.message?.contains("already exists", ignoreCase = true) != true) throw e
            TaskBranch(fullName, branchName, base, headSha(fullName, branchName))
        }
    }

    /**
     * Recursive listing of [branch], sorted like a file explorer (folders first at every level).
     * Submodules are skipped. For very large repos GitHub returns `truncated: true` and only part of the tree
     * (~100k entries / 7 MB); the partial listing is returned as is.
     */
    override suspend fun tree(fullName: String, branch: String): List<RepoEntry> {
        val sha = headSha(fullName, branch)
        val json = get("Branch $branch", repoPath(fullName) + listOf("git", "trees", sha)) {
            parameter("recursive", "1")
        }.obj()
        return json["tree"]?.jsonArray.orEmpty().mapNotNull { el ->
            val o = el.jsonObject
            val path = o.str("path") ?: return@mapNotNull null
            val type = when (o.str("type")) {
                "blob" -> RepoEntryType.File
                "tree" -> RepoEntryType.Directory
                else -> return@mapNotNull null
            }
            RepoEntry(path, type, size = o["size"]?.jsonPrimitive?.longOrNull, sha = o.str("sha"))
        }.sortedWith(RepoExplorerOrder)
    }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun file(fullName: String, branch: String, path: String): RepoFile {
        val element = get("File $path", repoPath(fullName) + listOf("contents") + segments(path)) {
            parameter("ref", branch)
        }
        val json = element as? JsonObject ?: throw GitHubException.Conflict("$path is a folder, not a file.")
        if (json.str("type") != null && json.str("type") != "file") throw GitHubException.Conflict("$path is not a regular file.")
        val size = json["size"]?.jsonPrimitive?.longOrNull ?: 0
        val encoded = json.str("content").orEmpty()
        if (size > MAX_EDITABLE_BYTES || json.str("encoding") != "base64") throw tooLargeOrBinary()
        val bytes = runCatching { Base64.decode(encoded.filterNot { it.isWhitespace() }) }.getOrElse { throw tooLargeOrBinary() }
        if (bytes.size > MAX_EDITABLE_BYTES || bytes.any { it == 0.toByte() }) throw tooLargeOrBinary()
        val text = runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrElse { throw tooLargeOrBinary() }
        return RepoFile(path = json.str("path") ?: path, content = text, sha = json.str("sha").orEmpty())
    }

    override suspend fun commit(fullName: String, branch: String, message: String, changes: List<FileChange>): CommitResult {
        if (changes.isEmpty()) throw GitHubException.Conflict("There are no changes to commit.")
        val repoPath = repoPath(fullName)
        val head = headSha(fullName, branch)
        val baseTree = get("Commit $head", repoPath + listOf("git", "commits", head)).obj()["tree"]?.jsonObject?.str("sha")
            ?: throw GitHubException.Conflict("GitHub returned an unexpected commit.")

        val entries = changes.map { change ->
            val blobSha = change.content?.let { content ->
                send(HttpMethod.Post, "Repository $fullName", repoPath + listOf("git", "blobs"), body = buildJsonObject {
                    put("content", content)
                    put("encoding", "utf-8")
                }).obj().str("sha") ?: throw GitHubException.Conflict("GitHub didn't accept ${change.path}.")
            }
            buildJsonObject {
                put("path", change.path)
                put("mode", "100644")
                put("type", "blob")
                put("sha", blobSha?.let(::JsonPrimitive) ?: JsonNull) // null sha = delete
            }
        }
        val newTree = send(HttpMethod.Post, "Repository $fullName", repoPath + listOf("git", "trees"), body = buildJsonObject {
            put("base_tree", baseTree)
            put("tree", JsonArray(entries))
        }).obj().str("sha") ?: throw GitHubException.Conflict("GitHub didn't accept the changes.")

        val commit = send(HttpMethod.Post, "Repository $fullName", repoPath + listOf("git", "commits"), body = buildJsonObject {
            put("message", message)
            put("tree", newTree)
            put("parents", buildJsonArray { add(JsonPrimitive(head)) })
        }).obj()
        val sha = commit.str("sha") ?: throw GitHubException.Conflict("GitHub didn't create the commit.")

        // Not forced: if someone pushed to the branch meanwhile GitHub rejects this (422) instead of losing their work.
        send(HttpMethod.Patch, "Branch $branch", repoPath + listOf("git", "refs", "heads") + segments(branch), body = buildJsonObject {
            put("sha", sha)
            put("force", false)
        })
        return CommitResult(sha, commit.str("html_url") ?: "https://github.com/$fullName/commit/$sha", message)
    }

    override suspend fun openPullRequest(fullName: String, head: String, base: String, title: String, body: String): PullRequest {
        return try {
            send(HttpMethod.Post, "Repository $fullName", repoPath(fullName) + listOf("pulls"), body = buildJsonObject {
                put("title", title)
                put("head", head)
                put("base", base)
                put("body", body)
            }).obj().toPullRequest(title)
        } catch (e: GitHubException.Conflict) {
            if (e.message?.contains("already exists", ignoreCase = true) != true) throw e
            val owner = fullName.substringBefore('/')
            val existing = get("Pull request", repoPath(fullName) + listOf("pulls")) {
                parameter("head", "$owner:$head")
                parameter("state", "open")
            } as? JsonArray
            existing?.firstOrNull()?.jsonObject?.toPullRequest(title) ?: throw e
        }
    }

    // ---- HTTP plumbing ----

    private suspend fun fetchUser(token: String): GitHubAccount {
        val json = send(HttpMethod.Get, "GitHub account", listOf("user"), tokenOverride = token).obj()
        return GitHubAccount(
            login = json.str("login") ?: throw GitHubException.Conflict("GitHub returned an unexpected account."),
            name = json.str("name"),
            avatarUrl = json.str("avatar_url"),
        )
    }

    private suspend fun headSha(fullName: String, branch: String): String =
        get("Branch $branch", repoPath(fullName) + listOf("git", "ref", "heads") + segments(branch))
            .obj()["object"]?.jsonObject?.str("sha")
            ?: throw GitHubException.NotFound("Branch $branch")

    private suspend fun get(what: String, path: List<String>, block: HttpRequestBuilder.() -> Unit = {}): JsonElement =
        send(HttpMethod.Get, what, path, block = block)

    private suspend fun currentToken(): String {
        stateLock.withLock { cachedToken }?.let { return it }
        val stored = runCatching { tokenStore.read() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: throw GitHubException.Unauthorized()
        stateLock.withLock { if (cachedToken == null) cachedToken = stored }
        return stored
    }

    /**
     * Sends one request and returns the parsed JSON body (JsonNull for empty bodies).
     * [what] names the thing for NotFound messages. [tokenOverride] is used by connect/restore.
     */
    private suspend fun send(
        method: HttpMethod,
        what: String,
        path: List<String>,
        body: JsonObject? = null,
        tokenOverride: String? = null,
        block: HttpRequestBuilder.() -> Unit = {},
    ): JsonElement {
        val token = tokenOverride ?: currentToken()
        val response: HttpResponse
        val text: String
        try {
            response = client.request {
                this.method = method
                url {
                    takeFrom(baseUrl)
                    appendPathSegments(path)
                }
                header(HttpHeaders.Accept, "application/vnd.github+json")
                header("X-GitHub-Api-Version", "2022-11-28")
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.UserAgent, "BAU-BUILD-App")
                if (body != null) setBody(TextContent(body.toString(), ContentType.Application.Json))
                block()
            }
            text = response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: GitHubException) {
            throw e
        } catch (e: Throwable) {
            throw GitHubException.Network(e)
        }
        val json = if (text.isBlank()) JsonNull else runCatching { AppJson.parseToJsonElement(text) }.getOrElse { JsonNull }
        val status = response.status.value
        if (status in 200..299) return json

        val error = mapError(status, what, json, response)
        if (error is GitHubException.Unauthorized && tokenOverride == null) {
            stateLock.withLock {
                if (cachedToken == token) {
                    cachedToken = null
                    _account.value = null
                    runCatching { tokenStore.clear() }
                }
            }
        }
        throw error
    }

    private fun mapError(status: Int, what: String, json: JsonElement, response: HttpResponse): GitHubException {
        val detail = errorDetail(json)
        val rateLimited = response.headers["x-ratelimit-remaining"] == "0" ||
            response.headers[HttpHeaders.RetryAfter] != null ||
            detail?.contains("rate limit", ignoreCase = true) == true
        return when {
            status == 401 -> GitHubException.Unauthorized()
            (status == 403 || status == 429) && rateLimited -> GitHubException.RateLimited()
            status == 403 -> GitHubException.Forbidden(detail.orEmpty())
            status == 404 -> GitHubException.NotFound(what)
            status == 409 || status == 422 -> GitHubException.Conflict(detail ?: "GitHub rejected the request.")
            status >= 500 -> GitHubException.Network()
            else -> GitHubException.Conflict(detail ?: "GitHub returned an error ($status).")
        }
    }

    /** GitHub's `message`, refined by `errors[].message` (e.g. "Validation Failed" → "A pull request already exists…"). */
    private fun errorDetail(json: JsonElement): String? {
        val o = json as? JsonObject ?: return null
        val message = o.str("message")
        val errors = (o["errors"] as? JsonArray).orEmpty().mapNotNull { e ->
            when (e) {
                is JsonObject -> e.str("message") ?: e.str("code")
                is JsonPrimitive -> e.contentOrNull
                else -> null
            }
        }
        return when {
            errors.isNotEmpty() -> errors.joinToString(" ")
            else -> message
        }
    }

    private fun JsonObject.toPullRequest(fallbackTitle: String) = PullRequest(
        number = this["number"]?.jsonPrimitive?.intOrNull ?: throw GitHubException.Conflict("GitHub returned an unexpected pull request."),
        url = str("html_url").orEmpty(),
        title = str("title") ?: fallbackTitle,
    )

    private companion object {
        const val MAX_EDITABLE_BYTES = 1_000_000L

        fun tooLargeOrBinary() = GitHubException.Conflict("This file is too large or binary to edit in the app")

        fun segments(path: String) = path.split('/').filter { it.isNotEmpty() }
        fun repoPath(fullName: String) = listOf("repos") + segments(fullName)

        fun JsonElement.obj(): JsonObject = this as? JsonObject ?: throw GitHubException.Conflict("GitHub returned an unexpected response.")
        fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    }
}

/** Folders before files at every level, then case-insensitive by name; a folder precedes its contents. */
internal val RepoExplorerOrder = Comparator<RepoEntry> { a, b ->
    val pa = a.path.split('/')
    val pb = b.path.split('/')
    var i = 0
    while (i < pa.size && i < pb.size && pa[i] == pb[i]) i++
    when {
        i == pa.size && i == pb.size -> 0
        i == pa.size -> -1 // a is an ancestor of b
        i == pb.size -> 1
        else -> {
            val aDir = i < pa.lastIndex || a.type == RepoEntryType.Directory
            val bDir = i < pb.lastIndex || b.type == RepoEntryType.Directory
            when {
                aDir != bDir -> if (aDir) -1 else 1
                else -> pa[i].lowercase().compareTo(pb[i].lowercase()).takeIf { it != 0 } ?: pa[i].compareTo(pb[i])
            }
        }
    }
}
