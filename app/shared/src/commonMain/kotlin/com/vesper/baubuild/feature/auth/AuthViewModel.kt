package com.vesper.baubuild.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.baubuild.core.auth.MicrosoftSignIn
import com.vesper.baubuild.core.auth.MicrosoftSignInCancelled
import com.vesper.baubuild.core.auth.MicrosoftSignInException
import com.vesper.baubuild.core.auth.MicrosoftSignInNotConfigured
import com.vesper.baubuild.core.model.AccountKind
import com.vesper.baubuild.core.model.AccountPolicy
import com.vesper.baubuild.core.network.ApiException
import com.vesper.baubuild.core.session.SessionStore
import com.vesper.baubuild.data.repository.AuthExchangeResult
import com.vesper.baubuild.data.repository.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal const val SIGN_IN_NOT_CONFIGURED = "Microsoft sign-in isn't switched on for BAU BUILD yet. Please try again later."
internal const val SIGN_IN_REJECTED = "Microsoft sign-in didn't finish. Please try again."
internal const val SIGN_IN_FAILED = "Sign-in didn't work. Please try again."
internal const val SIGN_IN_NETWORK_ERROR = "Couldn't reach BAU BUILD. Try again."
/** This build has no Microsoft app registration yet ([com.vesper.baubuild.core.auth.MicrosoftAuthConfig]). */
internal const val SIGN_IN_NOT_SET_UP = "Microsoft sign-in isn't set up for this build yet."

/** Where the sign-in flow is. Idle / SigningIn / Error all render the welcome screen. */
sealed interface AuthStatus {
    data object Idle : AuthStatus
    data object SigningIn : AuthStatus
    /** Inline, under the button. The welcome screen stays put (zero-downtime rule). */
    data class Error(val message: String) : AuthStatus
    /** Signed in with Microsoft, but not a BAU account; external access isn't open yet. */
    data class BlockedDomain(val email: String, val kind: AccountKind) : AuthStatus
    /** Signed in with a non-BAU account while external access is open behind payment. */
    data class RequiresPayment(val email: String) : AuthStatus
}

/** Screens inside the signed-out flow. [Welcome] is the root; the others sit on top of it. */
enum class AuthScreen { Welcome, Login, SignUp, ForgotPassword }

data class AuthUiState(
    val status: AuthStatus = AuthStatus.Idle,
    /** In-auth back stack, root first. Never empty. */
    val backStack: List<AuthScreen> = listOf(AuthScreen.Welcome),
) {
    val isSigningIn: Boolean get() = status == AuthStatus.SigningIn
    val screen: AuthScreen get() = backStack.last()
    val canGoBack: Boolean get() = backStack.size > 1
}

class AuthViewModel(
    private val microsoft: MicrosoftSignIn,
    private val repository: AuthRepository,
    private val session: SessionStore,
    private val policy: AccountPolicy,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    private var signInJob: Job? = null

    /**
     * Microsoft browser sign-in — the only way in (Log In's and Sign Up's "Continue with Microsoft", the blocked
     * screen's "Use My BAU Account"). The backend creates users on first sign-in. [loginHint] (the typed email)
     * pre-fills Microsoft's account picker.
     */
    fun signIn(loginHint: String? = null) {
        if (signInJob?.isActive == true) return
        setStatus(AuthStatus.SigningIn)
        signInJob = viewModelScope.launch {
            val next = try {
                val auth = microsoft.signIn(loginHint)
                when (val result = repository.exchange(auth.code, auth.codeVerifier, auth.redirectUri)) {
                    is AuthExchangeResult.SignedIn -> {
                        session.signedIn(result.session.tokens, result.session.user)
                        AuthStatus.Idle
                    }
                    // The backend doesn't echo the refused address; show what the user typed, if anything.
                    is AuthExchangeResult.NotAllowed ->
                        explainNotAllowed(result.copy(email = result.email.ifBlank { loginHint?.trim().orEmpty() }))
                    AuthExchangeResult.NotConfigured -> AuthStatus.Error(SIGN_IN_NOT_CONFIGURED)
                    AuthExchangeResult.Rejected -> AuthStatus.Error(SIGN_IN_REJECTED)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: MicrosoftSignInCancelled) {
                AuthStatus.Idle
            } catch (e: MicrosoftSignInNotConfigured) {
                AuthStatus.Error(SIGN_IN_NOT_SET_UP)
            } catch (e: MicrosoftSignInException) {
                AuthStatus.Error("Microsoft sign-in didn't finish. ${e.description ?: "Please try again."}")
            } catch (e: ApiException) {
                // 404 (wrong base URL / API not deployed) and gateway errors mean the API itself isn't reachable.
                AuthStatus.Error(if (e.status in UNREACHABLE_STATUSES) SIGN_IN_NETWORK_ERROR else SIGN_IN_FAILED)
            } catch (e: Exception) {
                AuthStatus.Error(SIGN_IN_NETWORK_ERROR)
            }
            setStatus(next)
        }
    }

    /** Lets the user back out of a browser they abandoned (mainly desktop, where nothing tells us). */
    fun cancelSignIn() {
        signInJob?.cancel()
        signInJob = null
        setStatus(AuthStatus.Idle)
    }

    /**
     * From the blocked screen. On Log In, go back to the form so a BAU address can be typed (the next "Continue
     * with Microsoft" uses it as the hint); elsewhere pick a different Microsoft account (`prompt=select_account`).
     */
    fun useAnotherAccount() {
        setStatus(AuthStatus.Idle)
        if (_state.value.screen != AuthScreen.Login) signIn()
    }

    /** From the blocked screen. No backend session exists for a refused account, so this just resets. */
    fun signOut() {
        signInJob?.cancel()
        setStatus(AuthStatus.Idle)
    }

    fun dismissError() {
        if (_state.value.status is AuthStatus.Error) setStatus(AuthStatus.Idle)
    }

    /**
     * Opens [screen] on top of the current one (no-op if it's already showing). If [screen] is already further
     * down the stack, pops back to it instead, so Log In ⇄ Sign Up links never grow the stack.
     * Clears a stale inline error.
     */
    fun navigateTo(screen: AuthScreen) {
        _state.update { state ->
            if (state.screen == screen) return@update state
            val existing = state.backStack.indexOf(screen)
            val stack = if (existing >= 0) state.backStack.subList(0, existing + 1).toList() else state.backStack + screen
            state.copy(
                backStack = stack,
                status = if (state.status is AuthStatus.Error) AuthStatus.Idle else state.status,
            )
        }
    }

    /** System back / back arrow. Returns false at the root so the host can handle it (e.g. leave the app). */
    fun back(): Boolean {
        if (!_state.value.canGoBack) return false
        _state.update {
            it.copy(
                backStack = it.backStack.dropLast(1),
                status = if (it.status is AuthStatus.Error) AuthStatus.Idle else it.status,
            )
        }
        return true
    }

    /** Straight back to the welcome screen, dropping everything above it. */
    fun backToWelcome() = navigateTo(AuthScreen.Welcome)

    private fun explainNotAllowed(result: AuthExchangeResult.NotAllowed): AuthStatus =
        when (result.access ?: policy.access(result.email)) {
            AccountPolicy.Access.RequiresPayment -> AuthStatus.RequiresPayment(result.email)
            AccountPolicy.Access.NotYetAvailable -> AuthStatus.BlockedDomain(result.email, policy.classify(result.email))
            // Backend refused an address the client thinks is fine (e.g. domain list changed): stay generic.
            AccountPolicy.Access.Free -> AuthStatus.Error("This account can't use BAU BUILD right now. Contact BAU IT if that seems wrong.")
        }

    private fun setStatus(status: AuthStatus) = _state.update { it.copy(status = status) }

    private companion object {
        val UNREACHABLE_STATUSES = setOf(404, 502, 503, 504)
    }
}
