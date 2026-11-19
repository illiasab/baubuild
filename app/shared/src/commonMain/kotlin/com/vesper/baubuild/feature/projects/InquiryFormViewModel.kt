package com.vesper.baubuild.feature.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.baubuild.core.model.Inquiry
import com.vesper.baubuild.core.model.NewInquiry
import com.vesper.baubuild.core.session.SessionState
import com.vesper.baubuild.data.repository.InquiryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface SubmitState {
    data object Idle : SubmitState
    data object Submitting : SubmitState
    data class Failed(val message: String) : SubmitState
    data class Submitted(val inquiry: Inquiry) : SubmitState
}

data class InquiryFormUiState(
    val title: String = "",
    val description: String = "",
    val department: String = "",
    val isUrgent: Boolean = false,
    /** Raw text of the optional suggested bounty in USD. */
    val bounty: String = "",
    /** Errors are only shown after the first submit attempt, so the form doesn't shout while typing. */
    val showErrors: Boolean = false,
    val submit: SubmitState = SubmitState.Idle,
) {
    val titleError: String?
        get() = when {
            title.isBlank() -> "Add a short title."
            title.trim().length < MIN_TITLE -> "Use at least $MIN_TITLE characters."
            title.trim().length > MAX_TITLE -> "Keep it under $MAX_TITLE characters."
            else -> null
        }
    val descriptionError: String?
        get() = when {
            description.isBlank() -> "Describe what you need built."
            description.trim().length < MIN_DESCRIPTION -> "Add a bit more detail (at least $MIN_DESCRIPTION characters)."
            else -> null
        }
    val departmentError: String? get() = if (department.isBlank()) "Which department is this for?" else null
    val bountyError: String?
        get() {
            if (bounty.isBlank()) return null
            val value = bounty.trim().toIntOrNull() ?: return "Use whole dollars, e.g. 25."
            return if (value !in 1..MAX_BOUNTY) "Suggest between \$1 and \$$MAX_BOUNTY." else null
        }

    val isValid: Boolean
        get() = titleError == null && descriptionError == null && departmentError == null && bountyError == null

    val isSubmitting: Boolean get() = submit is SubmitState.Submitting

    fun toNewInquiry() = NewInquiry(
        title = title.trim(),
        description = description.trim(),
        department = department.trim(),
        isUrgent = isUrgent,
        suggestedBountyUsd = bounty.trim().toIntOrNull(),
    )

    companion object {
        const val MIN_TITLE = 4
        const val MAX_TITLE = 120
        const val MIN_DESCRIPTION = 20
        const val MAX_BOUNTY = 5000
    }
}

/** "Request a Project" form for BAU staff. Input is kept on failure; the backend re-checks staff access. */
class InquiryFormViewModel(
    private val repository: InquiryRepository,
    private val session: StateFlow<SessionState>,
) : ViewModel() {
    private val _state = MutableStateFlow(InquiryFormUiState())
    val state: StateFlow<InquiryFormUiState> = _state.asStateFlow()

    /** Staff only. Students never reach this screen; this is a second guard. */
    val isAllowed: Boolean get() = Viewer.of(session.value).canRequestProject

    fun onTitleChange(value: String) = edit { copy(title = value) }
    fun onDescriptionChange(value: String) = edit { copy(description = value) }
    fun onDepartmentChange(value: String) = edit { copy(department = value) }
    fun onUrgentChange(value: Boolean) = edit { copy(isUrgent = value) }
    fun onBountyChange(value: String) = edit { copy(bounty = value.filter { it.isDigit() }.take(6)) }

    private inline fun edit(crossinline change: InquiryFormUiState.() -> InquiryFormUiState) = _state.update {
        if (it.isSubmitting || it.submit is SubmitState.Submitted) it
        // Editing clears a previous failure message but keeps the input.
        else it.change().let { s -> if (s.submit is SubmitState.Failed) s.copy(submit = SubmitState.Idle) else s }
    }

    fun submit() {
        val current = _state.value
        if (current.isSubmitting || current.submit is SubmitState.Submitted) return
        if (!isAllowed) {
            _state.value = current.copy(submit = SubmitState.Failed("Only BAU staff (@bau.edu) can request projects."))
            return
        }
        if (!current.isValid) {
            _state.value = current.copy(showErrors = true)
            return
        }
        _state.value = current.copy(showErrors = true, submit = SubmitState.Submitting)
        viewModelScope.launch {
            val result = try {
                SubmitState.Submitted(repository.submit(current.toNewInquiry()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                SubmitState.Failed("Couldn’t send your request. Your details are saved — try again.")
            }
            _state.update { it.copy(submit = result) }
        }
    }

    /** After success: start a fresh request. */
    fun startAnother() {
        _state.value = InquiryFormUiState()
    }
}
