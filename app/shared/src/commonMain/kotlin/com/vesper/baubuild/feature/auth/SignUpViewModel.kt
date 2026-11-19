package com.vesper.baubuild.feature.auth

import androidx.lifecycle.ViewModel
import com.vesper.baubuild.core.model.AccountKind
import com.vesper.baubuild.core.model.AccountPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Local, offline read of the typed email against [AccountPolicy]. The backend still decides on sign-in. */
sealed interface DomainCheck {
    /** Nothing typed, or not yet a full address (no `@`, or no dot in the domain). No message shown. */
    data object None : DomainCheck
    data object Student : DomainCheck
    data object Staff : DomainCheck
    /** Outside BAU. [requiresPayment] once external access opens behind payment, otherwise not available yet. */
    data class External(val requiresPayment: Boolean) : DomainCheck
}

data class SignUpUiState(
    val email: String = "",
    val domainCheck: DomainCheck = DomainCheck.None,
    val termsAccepted: Boolean = false,
) {
    /** The email is optional; only the terms acknowledgement gates "Continue with Microsoft". */
    val canContinue: Boolean get() = termsAccepted

    /** Passed to Microsoft as `login_hint` only when it looks like a complete address. */
    val loginHint: String? get() = email.trim().takeIf { domainCheck != DomainCheck.None }
}

class SignUpViewModel(private val policy: AccountPolicy) : ViewModel() {

    private val _state = MutableStateFlow(SignUpUiState())
    val state: StateFlow<SignUpUiState> = _state.asStateFlow()

    fun onEmailChange(email: String) {
        _state.update { it.copy(email = email, domainCheck = policy.domainCheck(email)) }
    }

    fun onTermsAcceptedChange(accepted: Boolean) {
        _state.update { it.copy(termsAccepted = accepted) }
    }
}

/** Shared by Sign Up and Log In. [DomainCheck.None] until [email] looks like a complete address. */
internal fun AccountPolicy.domainCheck(email: String): DomainCheck {
    val trimmed = email.trim()
    val at = trimmed.lastIndexOf('@')
    if (at <= 0) return DomainCheck.None
    val domain = trimmed.substring(at + 1)
    if (domain.length < 3 || '.' !in domain.trimEnd('.') || domain.any { it.isWhitespace() }) return DomainCheck.None
    return when (classify(trimmed)) {
        AccountKind.BauStudent -> DomainCheck.Student
        AccountKind.BauStaff -> DomainCheck.Staff
        AccountKind.External -> DomainCheck.External(
            requiresPayment = access(trimmed) == AccountPolicy.Access.RequiresPayment,
        )
    }
}
