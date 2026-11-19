package com.vesper.baubuild.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.baubuild.core.model.AccountKind
import com.vesper.baubuild.core.model.AccountPolicy
import com.vesper.baubuild.core.model.PasswordCheck
import com.vesper.baubuild.data.repository.AuthOutcome
import com.vesper.baubuild.data.repository.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Local, offline read of the typed email against [AccountPolicy]. The backend still decides. */
sealed interface DomainCheck {
    /** Nothing typed, or not yet a full address (no `@`, or no dot in the domain). No message shown. */
    data object None : DomainCheck
    data object Student : DomainCheck
    data object Staff : DomainCheck
    /** Outside BAU. [requiresPayment] once external access opens behind payment, otherwise not available. */
    data class External(val requiresPayment: Boolean) : DomainCheck
}

/** Student or staff address (the only ones the server accepts). */
val DomainCheck.isBau: Boolean get() = this == DomainCheck.Student || this == DomainCheck.Staff

data class SignUpUiState(
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val passwordVisible: Boolean = false,
    val termsAccepted: Boolean = false,
    val domainCheck: DomainCheck = DomainCheck.None,
    val submitting: Boolean = false,
    /** Inline error under the form (zero-downtime rule: the form and the user's input stay). */
    val error: String? = null,
    /** `409 email_taken`: shown as "An account with this email already exists. Log In instead?" with a link. */
    val emailTaken: Boolean = false,
) {
    val passwordCheck: PasswordCheck get() = PasswordCheck.of(password)

    /** Shown under Confirm password once something is typed there. */
    val confirmMismatch: Boolean get() = confirmPassword.isNotEmpty() && confirmPassword != password

    val canSubmit: Boolean
        get() = !submitting && name.isNotBlank() && domainCheck.isBau && passwordCheck.isValid &&
            confirmPassword == password && termsAccepted
}

/**
 * Sign Up: full name, BAU email (validated offline with [AccountPolicy] before any request), password + confirm,
 * terms. "Create Account" registers; the server emails a 6-digit code and the flow moves to Verify Email.
 */
class SignUpViewModel(
    private val repository: AuthRepository,
    private val policy: AccountPolicy,
    private val navigator: AuthNavigator,
) : ViewModel() {

    private val _state = MutableStateFlow(SignUpUiState())
    val state: StateFlow<SignUpUiState> = _state.asStateFlow()

    fun onNameChange(name: String) = _state.update { it.copy(name = name, error = null) }

    fun onEmailChange(email: String) =
        _state.update { it.copy(email = email, domainCheck = policy.domainCheck(email), error = null, emailTaken = false) }

    fun onPasswordChange(password: String) = _state.update { it.copy(password = password, error = null) }

    fun onConfirmPasswordChange(confirm: String) = _state.update { it.copy(confirmPassword = confirm, error = null) }

    fun onTogglePasswordVisible() = _state.update { it.copy(passwordVisible = !it.passwordVisible) }

    fun onTermsAcceptedChange(accepted: Boolean) = _state.update { it.copy(termsAccepted = accepted) }

    fun submit() {
        val s = _state.value
        if (!s.domainCheck.isBau && s.domainCheck != DomainCheck.None) {
            _state.update { it.copy(error = MSG_BAU_ONLY) }
            return
        }
        if (!s.canSubmit) return
        _state.update { it.copy(submitting = true, error = null, emailTaken = false) }
        viewModelScope.launch {
            val email = s.email.trim()
            try {
                when (val result = repository.register(s.name.trim(), email, s.password)) {
                    is AuthOutcome.VerificationRequired -> {
                        _state.update { it.copy(submitting = false) }
                        navigator.openVerifyEmail(result.email.ifBlank { email }, result.resendAfterSeconds)
                    }
                    AuthOutcome.DomainNotAllowed -> _state.update { it.copy(submitting = false, error = MSG_BAU_ONLY) }
                    AuthOutcome.EmailTaken -> _state.update { it.copy(submitting = false, emailTaken = true) }
                    AuthOutcome.WeakPassword -> _state.update { it.copy(submitting = false, error = MSG_WEAK_PASSWORD) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(submitting = false, error = failureMessage(e)) }
            }
        }
    }
}

/** Shared by Sign Up, Log In and Forgot Password. [DomainCheck.None] until [email] looks like a complete address. */
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
