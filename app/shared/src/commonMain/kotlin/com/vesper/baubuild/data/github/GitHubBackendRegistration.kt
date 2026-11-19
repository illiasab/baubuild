package com.vesper.baubuild.data.github

import com.vesper.baubuild.core.network.ApiException
import com.vesper.baubuild.core.network.apiCall
import com.vesper.baubuild.core.prefs.FlagStore
import com.vesper.baubuild.core.prefs.InMemoryFlagStore
import com.vesper.baubuild.core.prefs.platformFlagStore
import com.vesper.baubuild.core.session.TokenStore
import com.vesper.baubuild.core.session.platformSecretStore
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The BAU BUILD backend's side of the student's GitHub token (spec: docs/BACKEND_GITHUB_TOKEN.md).
 *
 * - `PUT /v1/integrations/github/token {token, login}` — **proposed**. The server validates the token with GitHub,
 *   stores it encrypted at rest and links the GitHub login to the BAU BUILD user, so the org agent can attribute and
 *   verify pushes.
 * - `DELETE /v1/integrations/github` — exists; unlinks.
 */
interface GitHubTokenLinkApi {
    suspend fun putToken(token: String, login: String)
    suspend fun deleteToken()
}

/** Talks to the BAU BUILD API with the shared backend client (base URL + Bearer session token, `expectSuccess`). */
class HttpGitHubTokenLinkApi(private val client: HttpClient) : GitHubTokenLinkApi {
    override suspend fun putToken(token: String, login: String) {
        apiCall {
            client.put("v1/integrations/github/token") {
                setBody(TextContent(buildJsonObject { put("token", token); put("login", login) }.toString(), ContentType.Application.Json))
            }
        }
    }

    override suspend fun deleteToken() {
        apiCall { client.delete("v1/integrations/github") }
    }
}

/** Nothing to link (tests, previews). */
object NoOpGitHubTokenLinkApi : GitHubTokenLinkApi {
    override suspend fun putToken(token: String, login: String) = Unit
    override suspend fun deleteToken() = Unit
}

enum class GitHubLinkResult {
    Linked,
    /** The server doesn't have the endpoint yet (404/405). Silent; retried quietly on the next launch. */
    NotAvailable,
    /** Any other failure. The inline note is shown and the link is retried on the next launch. */
    RetryLater,
}

/**
 * Registers the student's GitHub token with the BAU BUILD backend after a successful connect, and unregisters it on
 * disconnect. The app keeps its own copy in Keychain / Keystore ([HttpGitHubRepository]).
 *
 * Failures never block the user: a pending flag is persisted in [flags] and the link is retried on the next launch
 * ([retryPending]). Only "real" failures surface [note]; a missing endpoint (404/405) is logged and stays silent.
 */
class GitHubBackendRegistration(
    private val api: GitHubTokenLinkApi,
    private val flags: FlagStore = platformFlagStore(),
    private val log: (String) -> Unit = { println("[GitHubBackendRegistration] $it") },
    /**
     * `features.github` from `GET /v1/config`. While false the backend's GitHub integration is switched off: linking
     * is skipped (kept pending, retried once the flag is on) and stays silent. The student's own GitHub connection
     * in the app (GitHub API with their token) is unaffected.
     */
    private val backendIntegrationEnabled: () -> Boolean = { true },
) {
    private val _note = MutableStateFlow<String?>(null)

    /** Non-blocking inline note while a "real" failure is pending a retry; null otherwise. */
    val note: StateFlow<String?> = _note.asStateFlow()

    val isLinkPending: Boolean get() = flags.get(KEY_LINK_PENDING)
    val isUnlinkPending: Boolean get() = flags.get(KEY_UNLINK_PENDING)

    suspend fun register(token: String, login: String): GitHubLinkResult {
        flags.set(KEY_UNLINK_PENDING, false)
        if (!backendIntegrationEnabled()) {
            log("Backend GitHub integration is off (features.github=false); link kept pending.")
            flags.set(KEY_LINK_PENDING, true)
            _note.value = null
            return GitHubLinkResult.NotAvailable
        }
        return try {
            api.putToken(token, login)
            flags.set(KEY_LINK_PENDING, false)
            _note.value = null
            GitHubLinkResult.Linked
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.status == 404 || e.status == 405) {
                log("PUT /v1/integrations/github/token not available (${e.status}); will retry on next launch.")
                flags.set(KEY_LINK_PENDING, true)
                _note.value = null
                GitHubLinkResult.NotAvailable
            } else failed("${e.status} ${e.code}")
        } catch (e: Throwable) {
            failed(e::class.simpleName ?: "error")
        }
    }

    private fun failed(reason: String): GitHubLinkResult {
        log("Linking GitHub failed ($reason); will retry on next launch.")
        flags.set(KEY_LINK_PENDING, true)
        _note.value = LINK_FAILED_NOTE
        return GitHubLinkResult.RetryLater
    }

    /** Disconnect: forget any pending link and remove the token server-side (retried on next launch if it fails). */
    suspend fun unregister() {
        flags.set(KEY_LINK_PENDING, false)
        _note.value = null
        try {
            api.deleteToken()
            flags.set(KEY_UNLINK_PENDING, false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            // 404 = nothing linked server-side: done.
            flags.set(KEY_UNLINK_PENDING, e.status != 404 && e.status != 405)
            log("DELETE /v1/integrations/github failed (${e.status} ${e.code}).")
        } catch (e: Throwable) {
            flags.set(KEY_UNLINK_PENDING, true)
            log("DELETE /v1/integrations/github failed (${e::class.simpleName}).")
        }
    }

    /**
     * Launch-time retry. [credentials] returns the stored token + login (null = not connected any more → the pending
     * link is dropped). Never throws (except cancellation).
     */
    suspend fun retryPending(credentials: suspend () -> Pair<String, String>?) {
        if (isUnlinkPending && !isLinkPending) {
            unregister()
            return
        }
        if (!isLinkPending) return
        val creds = try {
            credentials()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return // keep the flag; try again next launch
        }
        if (creds == null) {
            flags.set(KEY_LINK_PENDING, false)
            return
        }
        register(creds.first, creds.second)
    }

    companion object {
        const val KEY_LINK_PENDING = "github.backendLink.pending"
        const val KEY_UNLINK_PENDING = "github.backendUnlink.pending"
        const val LINK_FAILED_NOTE = "Couldn't link GitHub to your BAU BUILD account. We'll retry."

        /** No backend link (tests). */
        fun noOp(): GitHubBackendRegistration = GitHubBackendRegistration(NoOpGitHubTokenLinkApi, InMemoryFlagStore(), log = {})
    }
}

/** Implemented by [GitHubRepository]s that link the token with the backend (see [BackendLinkedGitHubRepository]). */
interface GitHubBackendLinkStatus {
    /** Inline note ("Couldn't link GitHub to your BAU BUILD account. We'll retry.") or null. */
    val backendLinkNote: StateFlow<String?>
}

/**
 * [GitHubRepository] decorator: after a successful [connect] the token is registered with the backend in the
 * background (the connect itself never waits for or fails because of the backend); [disconnect] unregisters it.
 * On creation ([retryOnInit]) a link that failed earlier is retried once the stored account has been restored.
 */
class BackendLinkedGitHubRepository(
    private val delegate: GitHubRepository,
    private val registration: GitHubBackendRegistration,
    /** The token the app keeps in Keychain / Keystore (same store [HttpGitHubRepository] uses). */
    private val storedToken: suspend () -> String? = { secretStore.read() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    retryOnInit: Boolean = true,
    /** How long the launch retry waits for the stored account to be restored. */
    private val restoreTimeoutMs: Long = 60_000,
) : GitHubRepository by delegate, GitHubBackendLinkStatus {

    override val backendLinkNote: StateFlow<String?> get() = registration.note

    init {
        if (retryOnInit) scope.launch { retryPendingLink() }
    }

    override suspend fun connect(token: String): GitHubAccount {
        val account = delegate.connect(token)
        val trimmed = token.trim()
        scope.launch { registration.register(trimmed, account.login) }
        return account
    }

    override suspend fun disconnect() {
        delegate.disconnect()
        scope.launch { registration.unregister() }
    }

    internal suspend fun retryPendingLink() {
        registration.retryPending {
            // No stored token = disconnected (or the token was rejected and cleared): drop the pending link.
            val token = storedToken()?.takeIf { it.isNotBlank() } ?: return@retryPending null
            val login = withTimeoutOrNull(restoreTimeoutMs) { delegate.account.filterNotNull().first() }?.login
                ?: throw IllegalStateException("GitHub account not restored") // offline: keep pending
            token to login
        }
    }

    private companion object {
        val secretStore: TokenStore by lazy { platformSecretStore("github") }
    }
}
