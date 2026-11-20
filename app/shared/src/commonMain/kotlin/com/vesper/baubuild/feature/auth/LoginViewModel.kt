package com.vesper.baubuild.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.baubuild.core.model.AccountPolicy
import com.vesper.baubuild.data.repository.AuthOutcome
import com.vesper.baubuild.data.repository.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    /** Live, offline domain hint under the email field (same rule as Sign Up). */
    val domainCheck: DomainCheck = DomainCheck.None,
    val submitting: Boolean = false,
    val error: String? = null,
) {
    val canSubmit: Boolean get() = !submitting && domainCheck != DomainCheck.None && password.isNotEmpty()
}

/**
 * Log In with email + password. Non-BAU addresses are refused locally (no request). `domain_not_allowed` opens
 * Not Allowed; a lockout shows how long to wait. On success the repository stores the session and the app leaves the auth flow.
 */
class LoginViewModel(
    private val repository: AuthRepository,
    private val policy: AccountPolicy,
    private val navigator: AuthNavigator,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onEmailChange(email: String) =
        _state.update { it.copy(email = email, domainCheck = policy.domainCheck(email), error = null) }

    fun onPasswordChange(password: String) = _state.update { it.copy(password = password, error = null) }

    fun onTogglePasswordVisible() = _state.update { it.copy(passwordVisible = !it.passwordVisible) }

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        if (!s.domainCheck.isBau) {
            _state.update { it.copy(error = MSG_USE_BAU_EMAIL) }
            return
        }
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val email = s.email.trim()
            try {
                when (val result = repository.login(email, s.password)) {
                    is AuthOutcome.SignedIn -> _state.update { it.copy(submitting = false) }
                    AuthOutcome.InvalidCredentials -> _state.update { it.copy(submitting = false, error = MSG_INVALID_CREDENTIALS) }
                    AuthOutcome.DomainNotAllowed -> {
                        _state.update { it.copy(submitting = false) }
                        navigator.showNotAllowed(email)
                    }
                    is AuthOutcome.TooManyAttempts ->
                        _state.update { it.copy(submitting = false, error = tooManyAttemptsMessage(result.retryAfterSeconds)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(submitting = false, error = failureMessage(e)) }
            }
        }
    }
}
