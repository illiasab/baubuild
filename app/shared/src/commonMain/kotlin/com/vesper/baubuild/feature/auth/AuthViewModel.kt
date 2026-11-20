package com.vesper.baubuild.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.baubuild.core.model.AccountKind
import com.vesper.baubuild.core.model.AccountPolicy
import com.vesper.baubuild.core.session.SessionStore
import com.vesper.baubuild.core.session.SignOutReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Whether the signed-out flow is showing its screens or the "not allowed" explanation on top of them. */
sealed interface AuthStatus {
    data object Idle : AuthStatus
    /** The server refused this account's domain (`domain_not_allowed`); external access isn't open yet. */
    data class BlockedDomain(val email: String, val kind: AccountKind) : AuthStatus
    /** A non-BAU account while external access is open behind payment (not offered by the server today). */
    data class RequiresPayment(val email: String) : AuthStatus
}

/** Screens inside the signed-out flow. [Welcome] is the root; the others sit on top of it. */
enum class AuthScreen { Welcome, Login, SignUp, ForgotPassword }

data class AuthUiState(
    val status: AuthStatus = AuthStatus.Idle,
    /** In-auth back stack, root first. Never empty. */
    val backStack: List<AuthScreen> = listOf(AuthScreen.Welcome),
) {
    val screen: AuthScreen get() = backStack.last()
    val canGoBack: Boolean get() = backStack.size > 1
    val isBlocked: Boolean get() = status is AuthStatus.BlockedDomain || status is AuthStatus.RequiresPayment
}

/**
 * Navigation of the signed-out flow (Welcome → Log In / Sign Up, Forgot Password) and the Not Allowed
 * screen. The forms ([SignUpViewModel], [LoginViewModel]) talk to
 * the repository themselves and report back through [AuthNavigator]. A session that the app ended because the
 * server refused the domain ([SignOutReason.DomainNotAllowed], e.g. on token refresh) opens Not Allowed too.
 *
 * [initialScreen] is a DEBUG-only visual shortcut ([debugInitialAuthScreen]); production always starts on Welcome.
 */
class AuthViewModel(
    private val session: SessionStore,
    private val policy: AccountPolicy,
    initialScreen: AuthScreen? = null,
) : ViewModel(), AuthNavigator {

    private val _state = MutableStateFlow(
        AuthUiState(
            backStack = listOfNotNull(AuthScreen.Welcome, initialScreen?.takeIf { it != AuthScreen.Welcome }),
        ),
    )
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            session.signOutReason.filterNotNull().collect { reason ->
                when (reason) {
                    is SignOutReason.DomainNotAllowed -> showNotAllowed(reason.email)
                }
                session.consumeSignOutReason()
            }
        }
    }

    override fun showNotAllowed(email: String) {
        val address = email.trim()
        val status = when (policy.access(address)) {
            AccountPolicy.Access.RequiresPayment -> AuthStatus.RequiresPayment(address)
            // `Free` here means the server's list changed under us; still explain the rule rather than fail silently.
            AccountPolicy.Access.NotYetAvailable, AccountPolicy.Access.Free -> AuthStatus.BlockedDomain(address, policy.classify(address))
        }
        _state.update { it.copy(status = status) }
    }

    /** Not Allowed → "Use My BAU Account": back to Log In to type a BAU address. */
    fun useAnotherAccount() {
        _state.update { it.copy(status = AuthStatus.Idle) }
        navigateTo(AuthScreen.Login)
    }

    /** Not Allowed → "Sign Out": no session exists for a refused account, so this just returns to Welcome. */
    fun dismissNotAllowed() {
        _state.update { it.copy(status = AuthStatus.Idle) }
        backToWelcome()
    }

    /**
     * Opens [screen] on top of the current one (no-op if it's already showing). If [screen] is already further
     * down the stack, pops back to it instead, so Log In ⇄ Sign Up links never grow the stack.
     */
    fun navigateTo(screen: AuthScreen) {
        _state.update { state ->
            if (state.screen == screen) return@update state
            val existing = state.backStack.indexOf(screen)
            val stack = if (existing >= 0) state.backStack.subList(0, existing + 1).toList() else state.backStack + screen
            state.copy(backStack = stack)
        }
    }

    /** System back / back arrow. Returns false at the root so the host can handle it (e.g. leave the app). */
    fun back(): Boolean {
        if (!_state.value.canGoBack) return false
        _state.update { it.copy(backStack = it.backStack.dropLast(1)) }
        return true
    }

    /** Straight back to the welcome screen, dropping everything above it. */
    fun backToWelcome() = navigateTo(AuthScreen.Welcome)
}
