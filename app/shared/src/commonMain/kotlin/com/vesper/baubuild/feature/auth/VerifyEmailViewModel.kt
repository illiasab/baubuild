package com.vesper.baubuild.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.baubuild.data.repository.AuthOutcome
import com.vesper.baubuild.data.repository.AuthRepository
import com.vesper.baubuild.data.repository.CodePurpose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VerifyEmailUiState(
    val email: String = "",
    /** Digits only, at most [CODE_LENGTH]. */
    val code: String = "",
    val submitting: Boolean = false,
    val error: String? = null,
    /** Confirmation after "Resend code" ("We sent a new code to …"). */
    val notice: String? = null,
    /** Seconds until "Resend code" is available again. */
    val resendInSeconds: Long = 0,
    val resending: Boolean = false,
) {
    val canResend: Boolean get() = resendInSeconds == 0L && !resending && !submitting
    val canSubmit: Boolean get() = code.length == CODE_LENGTH && !submitting
}

/**
 * Verify Email: the 6-digit code from the BAU inbox. Submits automatically on the 6th digit (typed or pasted).
 * Success stores the session (repository) and the app leaves the auth flow. "Resend code" has a cooldown from the
 * server's `resend_after_s`.
 */
class VerifyEmailViewModel(private val repository: AuthRepository) : ViewModel() {

    private val _state = MutableStateFlow(VerifyEmailUiState())
    val state: StateFlow<VerifyEmailUiState> = _state.asStateFlow()

    private val countdown = Countdown(viewModelScope) { left -> _state.update { it.copy(resendInSeconds = left) } }
    private var startedId: Long? = null

    /** Called when Verify Email opens. The same [pending] (e.g. after rotation) keeps the typed code and timer. */
    fun start(pending: PendingVerification) {
        if (startedId == pending.id) return
        startedId = pending.id
        _state.value = VerifyEmailUiState(email = pending.email)
        countdown.start(pending.resendAfterSeconds ?: DEFAULT_RESEND_AFTER_SECONDS)
    }

    fun onCodeChange(raw: String) {
        val code = normalizeCode(raw)
        if (code == _state.value.code) return
        _state.update { it.copy(code = code, error = null) }
        if (code.length == CODE_LENGTH) submit()
    }

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        _state.update { it.copy(submitting = true, error = null, notice = null) }
        viewModelScope.launch {
            try {
                val error = when (val result = repository.verifyEmail(s.email, s.code)) {
                    is AuthOutcome.SignedIn -> null
                    AuthOutcome.InvalidCode -> MSG_INVALID_CODE
                    AuthOutcome.CodeExpired -> MSG_CODE_EXPIRED
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
        if (!s.canResend || s.email.isBlank()) return
        _state.update { it.copy(resending = true, error = null, notice = null) }
        viewModelScope.launch {
            try {
                when (val result = repository.resendCode(s.email, CodePurpose.Verify)) {
                    is AuthOutcome.CodeSent -> {
                        _state.update { it.copy(resending = false, code = "", notice = "We sent a new code to ${s.email}.") }
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
}
