package com.vesper.baubuild.feature.auth

import androidx.lifecycle.ViewModel
import com.vesper.baubuild.core.model.AccountPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LoginUiState(
    val email: String = "",
    /** Live, offline domain hint under the email field (same rule as Sign Up). */
    val domainCheck: DomainCheck = DomainCheck.None,
) {
    /** Passed to Microsoft as `login_hint` only when it looks like a complete address. */
    val loginHint: String? get() = email.trim().takeIf { domainCheck != DomainCheck.None }
}

/**
 * Log In form state. There is no password: "Continue with Microsoft" runs [AuthViewModel.signIn] with
 * [LoginUiState.loginHint] (the backend only supports Microsoft sign-in). Progress and errors live in
 * [AuthViewModel]'s status, shared with Sign Up.
 */
class LoginViewModel(private val policy: AccountPolicy) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onEmailChange(email: String) {
        _state.update { it.copy(email = email, domainCheck = policy.domainCheck(email)) }
    }
}
