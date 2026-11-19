package com.vesper.baubuild.core.session

import com.vesper.baubuild.core.model.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface SessionState {
    /** Reading the stored token on launch. Show splash, not the sign-in screen, to avoid a flash. */
    data object Restoring : SessionState
    data object SignedOut : SessionState
    data class SignedIn(val user: User) : SessionState
}

/** Persists the backend's opaque session token. Platform implementations use Keychain / Keystore / OS vault. */
interface TokenStore {
    suspend fun read(): String?
    suspend fun write(token: String)
    suspend fun clear()
}

class InMemoryTokenStore : TokenStore {
    private var token: String? = null
    override suspend fun read() = token
    override suspend fun write(token: String) { this.token = token }
    override suspend fun clear() { token = null }
}

/** Single source of truth for "who is signed in". Every screen observes [state]. */
class SessionStore(private val tokenStore: TokenStore) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Restoring)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    @kotlin.concurrent.Volatile
    var token: String? = null
        private set

    suspend fun restore(loadUser: suspend (token: String) -> User?) {
        val stored = tokenStore.read()
        val user = stored?.let { runCatching { loadUser(it) }.getOrNull() }
        if (stored != null && user != null) {
            token = stored
            _state.value = SessionState.SignedIn(user)
        } else {
            _state.value = SessionState.SignedOut
        }
    }

    suspend fun signedIn(token: String, user: User) {
        tokenStore.write(token)
        this.token = token
        _state.value = SessionState.SignedIn(user)
    }

    fun updateUser(user: User) {
        if (_state.value is SessionState.SignedIn) _state.value = SessionState.SignedIn(user)
    }

    suspend fun signOut() {
        tokenStore.clear()
        token = null
        _state.value = SessionState.SignedOut
    }
}
