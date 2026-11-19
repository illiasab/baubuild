package com.vesper.baubuild.core.session

import com.vesper.baubuild.core.model.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

sealed interface SessionState {
    /** Reading the stored token on launch. Show splash, not the sign-in screen, to avoid a flash. */
    data object Restoring : SessionState
    data object SignedOut : SessionState
    data class SignedIn(val user: User) : SessionState
}

/** Why the app signed the user out on its own (not from a "Sign Out" tap). Shown once by the auth screens. */
sealed interface SignOutReason {
    /** The backend answered `403 domain_not_allowed` (e.g. on token refresh): the account's domain may not use the app. */
    data class DomainNotAllowed(val email: String) : SignOutReason
}

/** Persists one secret string. Platform implementations use Keychain / Keystore / OS vault. */
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

/**
 * The backend's opaque token pair (`POST /v1/auth/login`, `verify-email`, `password/reset`, `POST /v1/auth/refresh`).
 * [refreshToken] is null for legacy sessions and tests; [expiresAtMillis] (epoch ms) is null when unknown.
 */
@Serializable
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtMillis: Long? = null,
) {
    /** True when the access token expires within [marginMillis] of [nowMillis] (unknown expiry → false). */
    fun expiresSoon(nowMillis: Long, marginMillis: Long): Boolean =
        expiresAtMillis != null && nowMillis >= expiresAtMillis - marginMillis

    override fun toString(): String = "AuthTokens(expiresAtMillis=$expiresAtMillis)" // never log secrets
}

/** Wall clock in epoch milliseconds. */
@OptIn(ExperimentalTime::class)
fun epochMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** What is written to the secret store: the token pair plus the last known user (offline launch). */
@Serializable
private data class StoredSession(
    val v: Int = 1,
    val tokens: AuthTokens,
    val user: User? = null,
)

private val storeJson = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

/**
 * Single source of truth for "who is signed in". Every screen observes [state].
 *
 * The token pair and the last known user are persisted as one JSON value in [tokenStore] (Keychain / Keystore /
 * prefs). [token] stays the current access token so existing callers keep working; the HTTP client attaches it
 * and rotates it through [updateTokens] (see `core/network/Network.kt`).
 */
class SessionStore(
    private val tokenStore: TokenStore,
    private val clock: () -> Long = ::epochMillis,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Restoring)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    @kotlin.concurrent.Volatile
    var tokens: AuthTokens? = null
        private set

    private val _signOutReason = MutableStateFlow<SignOutReason?>(null)

    /** Set when the app signed out on its own ([signOut] with a reason); cleared by [consumeSignOutReason] / sign-in. */
    val signOutReason: StateFlow<SignOutReason?> = _signOutReason.asStateFlow()

    fun consumeSignOutReason() {
        _signOutReason.value = null
    }

    /** Email of the signed-in user, or "" (e.g. while restoring without a cached user). */
    fun signedInEmail(): String = (state.value as? SessionState.SignedIn)?.user?.email.orEmpty()

    /** Current access token (Bearer). */
    val token: String? get() = tokens?.accessToken

    fun now(): Long = clock()

    /**
     * Launch-time restore. The stored tokens are made current *before* [loadUser] runs, so `GET /v1/me` is sent
     * with them (and can be refreshed on 401). With a cached user the app is signed in immediately and [loadUser]
     * only refreshes it; offline launches therefore keep working (zero downtime). If the backend rejects the
     * session the HTTP layer calls [signOut].
     */
    suspend fun restore(loadUser: suspend (token: String) -> User?) {
        // Unreadable secure storage (e.g. Keychain unavailable) must not crash launch: treat as signed out.
        val stored = runCatchingNonCancel { tokenStore.read() }?.let(::decode)
        if (stored == null) {
            _state.value = SessionState.SignedOut
            return
        }
        tokens = stored.tokens
        stored.user?.let { _state.value = SessionState.SignedIn(it) }
        val fresh = runCatchingNonCancel { loadUser(stored.tokens.accessToken) }
        val current = tokens
        when {
            current == null -> _state.value = SessionState.SignedOut // rejected (refresh failed) while loading
            fresh != null -> {
                _state.value = SessionState.SignedIn(fresh)
                persist(current, fresh)
            }
            stored.user != null -> Unit // offline / server hiccup: stay signed in with the cached user
            else -> {
                tokens = null // keep the stored pair for the next launch, but don't pretend to be signed in
                _state.value = SessionState.SignedOut
            }
        }
    }

    /**
     * Persisting is best effort: if secure storage fails the user stays signed in for this run and simply
     * signs in again next launch, instead of the app crashing.
     */
    suspend fun signedIn(tokens: AuthTokens, user: User) {
        persist(tokens, user)
        this.tokens = tokens
        _signOutReason.value = null
        _state.value = SessionState.SignedIn(user)
    }

    /** Access-token-only session (tests). */
    suspend fun signedIn(token: String, user: User) = signedIn(AuthTokens(token), user)

    /**
     * Stores a rotated pair from `POST /v1/auth/refresh`. Ignored unless [previous] is still current, so a refresh
     * that finishes after a sign-out (or after another refresh) never resurrects or overwrites a session.
     * Returns whether the pair was accepted.
     */
    suspend fun updateTokens(previous: AuthTokens, next: AuthTokens): Boolean {
        if (tokens != previous) return false
        tokens = next
        persist(next, (state.value as? SessionState.SignedIn)?.user)
        return true
    }

    fun updateUser(user: User) {
        if (_state.value is SessionState.SignedIn) _state.value = SessionState.SignedIn(user)
    }

    suspend fun signOut(reason: SignOutReason? = null) {
        tokens = null
        _signOutReason.value = reason
        runCatchingNonCancel { tokenStore.clear() }
        _state.value = SessionState.SignedOut
    }

    private suspend fun persist(tokens: AuthTokens, user: User?) {
        runCatchingNonCancel { tokenStore.write(storeJson.encodeToString(StoredSession.serializer(), StoredSession(tokens = tokens, user = user))) }
    }

    private fun decode(raw: String): StoredSession? {
        if (raw.isBlank()) return null
        if (!raw.trimStart().startsWith("{")) return StoredSession(tokens = AuthTokens(raw)) // legacy: bare token
        return runCatching { storeJson.decodeFromString(StoredSession.serializer(), raw) }.getOrNull()
    }
}

private suspend inline fun <T> runCatchingNonCancel(block: () -> T): T? = try {
    block()
} catch (e: kotlin.coroutines.cancellation.CancellationException) {
    throw e
} catch (e: Throwable) {
    null
}
