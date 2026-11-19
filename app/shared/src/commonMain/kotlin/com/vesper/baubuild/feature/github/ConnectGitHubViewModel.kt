package com.vesper.baubuild.feature.github

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.baubuild.core.session.SessionState
import com.vesper.baubuild.core.session.SessionStore
import com.vesper.baubuild.data.github.GitHubAccount
import com.vesper.baubuild.data.github.GitHubBackendLinkStatus
import com.vesper.baubuild.data.github.GitHubException
import com.vesper.baubuild.data.github.GitHubRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ConnectGitHubUiState(
    /** The connected account (from [GitHubRepository.account]), or null. */
    val account: GitHubAccount? = null,
    /** Personal access token being typed / pasted. Kept on failure, cleared on success. */
    val token: String = "",
    val tokenVisible: Boolean = false,
    val connecting: Boolean = false,
    /** Inline error under the token field (validation or connect failure). */
    val error: String? = null,
    /** Set when a connect made from this screen succeeded (the prompt uses it to continue). */
    val justConnected: Boolean = false,
    val showDisconnectConfirm: Boolean = false,
    val disconnecting: Boolean = false,
    /** Inline error under "Disconnect". */
    val disconnectError: String? = null,
    /** Non-blocking note when the token couldn't be linked to the BAU BUILD account (retried on next launch). */
    val backendLinkNote: String? = null,
) {
    val isConnected: Boolean get() = account != null
    val canConnect: Boolean get() = token.isNotBlank() && !connecting
    val connectedMessage: String? get() = account?.let { "Connected as @${it.login}" }
}

/**
 * Connect GitHub: the student pastes a fine-grained personal access token; [GitHubRepository.connect] validates it
 * (`GET /user`) and stores it securely (the data layer then links it with the BAU BUILD backend in the background — see
 * BackendLinkedGitHubRepository). On success the session user's `githubUsername` becomes the GitHub login so
 * Profile shows it everywhere.
 */
class ConnectGitHubViewModel(
    private val repository: GitHubRepository,
    private val session: SessionStore,
) : ViewModel() {
    // One state holder so typing updates synchronously (no async hop between the text field and its state).
    private val local = MutableStateFlow(ConnectGitHubUiState(account = repository.account.value))
    val state: StateFlow<ConnectGitHubUiState> = local.asStateFlow()

    init {
        viewModelScope.launch { repository.account.collect { account -> update { copy(account = account) } } }
        // Backend linking happens in the data layer (BackendLinkedGitHubRepository); just mirror its note.
        (repository as? GitHubBackendLinkStatus)?.let { link ->
            viewModelScope.launch { link.backendLinkNote.collect { note -> update { copy(backendLinkNote = note) } } }
        }
    }

    private inline fun update(block: ConnectGitHubUiState.() -> ConnectGitHubUiState) {
        local.value = local.value.block()
    }

    fun onTokenChange(text: String) = update { copy(token = text.take(MAX_TOKEN_LENGTH), error = null) }

    fun toggleTokenVisible() = update { copy(tokenVisible = !tokenVisible) }

    fun connect() {
        val s = local.value
        if (s.connecting) return
        val token = s.token.trim()
        validateToken(token)?.let { message ->
            update { copy(error = message) }
            return
        }
        update { copy(connecting = true, error = null, disconnectError = null) }
        viewModelScope.launch {
            try {
                val account = repository.connect(token)
                (session.state.value as? SessionState.SignedIn)?.user?.let { user ->
                    if (user.githubUsername != account.login) session.updateUser(user.copy(githubUsername = account.login))
                }
                update { copy(connecting = false, token = "", tokenVisible = false, justConnected = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                update { copy(connecting = false, error = connectErrorMessage(e)) }
            }
        }
    }

    fun requestDisconnect() {
        if (local.value.disconnecting) return
        update { copy(showDisconnectConfirm = true, disconnectError = null) }
    }

    fun cancelDisconnect() = update { copy(showDisconnectConfirm = false) }

    fun confirmDisconnect() {
        if (local.value.disconnecting) return
        update { copy(showDisconnectConfirm = false, disconnecting = true, disconnectError = null) }
        viewModelScope.launch {
            try {
                repository.disconnect()
                update { copy(disconnecting = false, justConnected = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                update { copy(disconnecting = false, disconnectError = "Couldn’t disconnect GitHub. Try again.") }
            }
        }
    }

    /** Clears typed input and messages (the reusable prompt calls this when it closes). Keeps the account. */
    fun reset() {
        if (local.value.connecting || local.value.disconnecting) return
        local.value = ConnectGitHubUiState(
            account = repository.account.value,
            backendLinkNote = (repository as? GitHubBackendLinkStatus)?.backendLinkNote?.value,
        )
    }

    companion object {
        const val MAX_TOKEN_LENGTH = 400

        /** Local checks before calling GitHub. Null = looks like a token. */
        fun validateToken(token: String): String? = when {
            token.isEmpty() -> "Paste your GitHub token first."
            token.any { it.isWhitespace() } -> "Tokens don’t contain spaces. Copy it again from GitHub."
            token.length < 20 -> "That looks too short for a GitHub token. Check it was copied fully."
            else -> null
        }

        fun connectErrorMessage(e: Throwable): String = when (e) {
            is GitHubException.Unauthorized -> "That token didn't work. Check it was copied fully and hasn't expired."
            is GitHubException.Forbidden -> "GitHub refused this token. Check its permissions and try again."
            is GitHubException.RateLimited -> "GitHub is busy right now (rate limit). Try again in a few minutes."
            is GitHubException.Network -> "Couldn’t reach GitHub. Check your connection and try again."
            else -> "Couldn’t connect to GitHub. Try again."
        }
    }
}
