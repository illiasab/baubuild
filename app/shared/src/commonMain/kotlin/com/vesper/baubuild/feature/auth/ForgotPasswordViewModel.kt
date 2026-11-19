package com.vesper.baubuild.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.baubuild.core.model.AccountPolicy
import com.vesper.baubuild.core.model.PasswordCheck
import com.vesper.baubuild.data.repository.AuthOutcome
import com.vesper.baubuild.data.repository.AuthRepository
import com.vesper.baubuild.data.repository.CodePurpose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ForgotPasswordStep { EnterEmail, Reset }

data class ForgotPasswordUiState(
    val step: ForgotPasswordStep = ForgotPasswordStep.EnterEmail,
    val email: String = "",
    val domainCheck: DomainCheck = DomainCheck.None,
    val code: String = "",
    val newPassword: String = "",
    val confirmPassword: String = "",
    val passwordVisible: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
    /** "If an account exists for …, we sent a code" — never reveals whether the account exists. */
    val notice: String? = null,
    val resendInSeconds: Long = 0,
    val resending: Boolean = false,
) {
    val passwordCheck: PasswordCheck get() = PasswordCheck.of(newPassword)
    val confirmMismatch: Boolean get() = confirmPassword.isNotEmpty() && confirmPassword != newPassword
    val canSendCode: Boolean get() = !submitting && domainCheck != DomainCheck.None
    val canReset: Boolean
        get() = !submitting && code.length == CODE_LENGTH && passwordCheck.isValid && confirmPassword == newPassword
    val canResend: Boolean get() = resendInSeconds == 0L && !resending && !submitting
}

/**
 * Forgot password in two steps: (1) email → "Send code" (`POST /v1/auth/password/forgot`, always 202 — the screen
 * always moves on, without saying whether the account exists); (2) code + new password → "Reset Password"
 * (`POST /v1/auth/password/reset`), which signs in and ends the server's other sessions.
 */
class ForgotPasswordViewModel(
    private val repository: AuthRepository,
    private val policy: AccountPolicy,
) : ViewModel() {

    private val _state = MutableStateFlow(ForgotPasswordUiState())
    val state: StateFlow<ForgotPasswordUiState> = _state.asStateFlow()

    private val countdown = Countdown(viewModelScope) { left -> _state.update { it.copy(resendInSeconds = left) } }

    fun onEmailChange(email: String) =
        _state.update { it.copy(email = email, domainCheck = policy.domainCheck(email), error = null) }

    fun onCodeChange(raw: String) = _state.update { it.copy(code = normalizeCode(raw), error = null) }

    fun onNewPasswordChange(password: String) = _state.update { it.copy(newPassword = password, error = null) }

    fun onConfirmPasswordChange(confirm: String) = _state.update { it.copy(confirmPassword = confirm, error = null) }

    fun onTogglePasswordVisible() = _state.update { it.copy(passwordVisible = !it.passwordVisible) }

    /** Step 1. */
    fun sendCode() {
        val s = _state.value
        if (!s.canSendCode) return
        if (!s.domainCheck.isBau) {
            _state.update { it.copy(error = MSG_USE_BAU_EMAIL) }
            return
        }
        val email = s.email.trim()
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            try {
                when (val result = repository.forgotPassword(email)) {
                    is AuthOutcome.CodeSent -> {
                        _state.update {
                            it.copy(
                                submitting = false,
                                step = ForgotPasswordStep.Reset,
                                code = "",
                                notice = "If an account exists for $email, we sent a 6-digit code to it.",
                            )
                        }
                        countdown.start(result.resendAfterSeconds ?: DEFAULT_RESEND_AFTER_SECONDS)
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

    /** Step 2. Success stores the session (repository) and the app leaves the auth flow. */
    fun resetPassword() {
        val s = _state.value
        if (!s.canReset) return
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            try {
                val error = when (val result = repository.resetPassword(s.email.trim(), s.code, s.newPassword)) {
                    is AuthOutcome.SignedIn -> null
                    AuthOutcome.InvalidCode -> MSG_INVALID_CODE
                    AuthOutcome.CodeExpired -> MSG_CODE_EXPIRED
                    AuthOutcome.WeakPassword -> MSG_WEAK_PASSWORD
                    is AuthOutcome.TooManyAttempts -> MSG_CODE_LOCKED
                }
                _state.update { it.copy(submitting = false, error = error) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(submitting = false, error = failureMessage(e)) }
            }
        }
    }

    fun resend() {
        val s = _state.value
        if (!s.canResend || s.step != ForgotPasswordStep.Reset) return
        val email = s.email.trim()
        _state.update { it.copy(resending = true, error = null, notice = null) }
        viewModelScope.launch {
            try {
                when (val result = repository.resendCode(email, CodePurpose.Reset)) {
                    is AuthOutcome.CodeSent -> {
                        _state.update { it.copy(resending = false, notice = "If an account exists for $email, we sent a new code to it.") }
                        countdown.start(result.resendAfterSeconds ?: DEFAULT_RESEND_AFTER_SECONDS)
                    }
                    is AuthOutcome.TooManyAttempts -> {
                        _state.update { it.copy(resending = false, error = tooManyAttemptsMessage(result.retryAfterSeconds)) }
                        countdown.start(result.retryAfterSeconds ?: DEFAULT_RESEND_AFTER_SECONDS)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(resending = false, error = failureMessage(e)) }
            }
        }
    }

    /** "Wrong email? Change it" / back on step 2. Returns false on step 1 so the caller pops the screen. */
    fun back(): Boolean {
        if (_state.value.step == ForgotPasswordStep.EnterEmail) return false
        countdown.cancel()
        _state.update { it.copy(step = ForgotPasswordStep.EnterEmail, code = "", error = null, notice = null, resendInSeconds = 0) }
        return true
    }
}
