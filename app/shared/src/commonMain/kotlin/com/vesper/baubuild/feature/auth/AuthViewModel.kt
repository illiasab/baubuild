package com.vesper.baubuild.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.baubuild.core.auth.FakeMicrosoftSignIn
import com.vesper.baubuild.core.auth.MicrosoftSignIn
import com.vesper.baubuild.core.auth.MicrosoftSignInCancelled
import com.vesper.baubuild.core.auth.MicrosoftSignInException
import com.vesper.baubuild.core.model.AccountKind
import com.vesper.baubuild.core.model.AccountPolicy
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

/** Shown only with the fake sign-in, so every branch of the flow can be demoed without Entra ID. */
data class DemoAccounts(val emails: List<String>, val selected: String)

data class AuthUiState(
    val status: AuthStatus = AuthStatus.Idle,
    val demo: DemoAccounts? = null,
) {
    val isSigningIn: Boolean get() = status == AuthStatus.SigningIn
}

class AuthViewModel(
    private val microsoft: MicrosoftSignIn,
    private val repository: AuthRepository,
    private val session: SessionStore,
    private val policy: AccountPolicy,
) : ViewModel() {

    private val fake = microsoft as? FakeMicrosoftSignIn
    private val _state = MutableStateFlow(AuthUiState(demo = fake?.let { DemoAccounts(it.demoEmails, it.selectedEmail) }))
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    private var signInJob: Job? = null

    /** "Sign Up" and "Log In" both go through the BAU Microsoft account; the backend creates users on first sign-in. */
    fun signIn() {
        if (signInJob?.isActive == true) return
        setStatus(AuthStatus.SigningIn)
        signInJob = viewModelScope.launch {
            val next = try {
                val auth = microsoft.signIn()
                when (val result = repository.exchangeMicrosoftCode(auth.code, auth.codeVerifier, auth.redirectUri)) {
                    is AuthExchangeResult.SignedIn -> {
                        session.signedIn(result.session.opaqueToken, result.session.user)
                        AuthStatus.Idle
                    }
                    is AuthExchangeResult.NotAllowed -> explainNotAllowed(result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: MicrosoftSignInCancelled) {
                AuthStatus.Idle
            } catch (e: MicrosoftSignInException) {
                AuthStatus.Error("Microsoft sign-in didn't finish. ${e.description ?: "Please try again."}")
            } catch (e: Exception) {
                AuthStatus.Error("Couldn't reach BAU BUILD. Check your connection and try again.")
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

    /** From the blocked screen: pick a different Microsoft account (`prompt=select_account`). */
    fun useAnotherAccount() {
        setStatus(AuthStatus.Idle)
        signIn()
    }

    /** From the blocked screen. No backend session exists for a refused account, so this just resets. */
    fun signOut() {
        signInJob?.cancel()
        setStatus(AuthStatus.Idle)
    }

    fun dismissError() {
        if (_state.value.status is AuthStatus.Error) setStatus(AuthStatus.Idle)
    }

    fun selectDemoAccount(email: String) {
        val fake = fake ?: return
        fake.selectedEmail = email
        _state.update { it.copy(demo = it.demo?.copy(selected = email)) }
    }

    private fun explainNotAllowed(result: AuthExchangeResult.NotAllowed): AuthStatus =
        when (result.access ?: policy.access(result.email)) {
            AccountPolicy.Access.RequiresPayment -> AuthStatus.RequiresPayment(result.email)
            AccountPolicy.Access.NotYetAvailable -> AuthStatus.BlockedDomain(result.email, policy.classify(result.email))
            // Backend refused an address the client thinks is fine (e.g. domain list changed): stay generic.
            AccountPolicy.Access.Free -> AuthStatus.Error("This account can't use BAU BUILD right now. Contact BAU IT if that seems wrong.")
        }

    private fun setStatus(status: AuthStatus) = _state.update { it.copy(status = status) }
}
