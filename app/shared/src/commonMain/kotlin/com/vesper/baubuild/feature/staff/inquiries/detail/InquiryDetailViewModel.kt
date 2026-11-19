package com.vesper.baubuild.feature.staff.inquiries.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.baubuild.core.DataChanges
import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.core.dataOrNull
import com.vesper.baubuild.core.load
import com.vesper.baubuild.core.model.InquiryBlocker
import com.vesper.baubuild.core.model.InquiryDraft
import com.vesper.baubuild.core.model.InquiryPriority
import com.vesper.baubuild.core.model.ProjectType
import com.vesper.baubuild.core.model.RewardType
import com.vesper.baubuild.core.model.StaffInquiry
import com.vesper.baubuild.data.repository.StaffRepository
import com.vesper.baubuild.feature.staff.inquiries.IsoDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class InquiryDetailTab(val label: String) { Status("Status"), Details("Details") }

/**
 * The editable part of an inquiry (Details tab). Text fields stay strings so half-typed input survives;
 * [toDraft] converts back after [validate] passes.
 */
data class InquiryForm(
    val title: String = "",
    val explanation: String = "",
    val expectation: String = "",
    val types: Set<ProjectType> = emptySet(),
    val priority: InquiryPriority = InquiryPriority.Normal,
    /** ISO yyyy-MM-dd or blank for no deadline. */
    val deadline: String = "",
    /** Whole dollars or blank for no price. */
    val price: String = "",
) {
    /** First problem that blocks saving, or null. */
    fun validate(): String? = when {
        title.isBlank() -> "Add a project title."
        explanation.isBlank() -> "Add a short project explanation."
        types.isEmpty() -> "Pick at least one project type."
        deadline.isNotBlank() && IsoDate.parse(deadline) == null -> "Use a date like 2026-12-03 for the deadline."
        price.isNotBlank() && (price.trim().toIntOrNull()?.takeIf { it >= 0 } == null) -> "Enter the reward as whole dollars, e.g. 35."
        else -> null
    }

    /** Applies the form onto [base], keeping fields the form doesn't edit (class code, difficulty…). */
    fun toDraft(base: InquiryDraft): InquiryDraft {
        val priceUsd = price.trim().toIntOrNull()
        return base.copy(
            title = title.trim(),
            explanation = explanation.trim(),
            expectation = expectation.trim(),
            types = types,
            priority = priority,
            deadline = deadline.trim().ifBlank { null },
            priceUsd = priceUsd,
            rewards = if (priceUsd != null) base.rewards + RewardType.Price else base.rewards - RewardType.Price,
        )
    }

    companion object {
        fun from(draft: InquiryDraft) = InquiryForm(
            title = draft.title,
            explanation = draft.explanation,
            expectation = draft.expectation,
            types = draft.types,
            priority = draft.priority,
            deadline = draft.deadline.orEmpty(),
            price = draft.priceUsd?.toString().orEmpty(),
        )
    }
}

/** Details-form editing state. [baseline] is what the server has; dirty = they differ. */
data class InquiryEditor(
    val form: InquiryForm? = null,
    val baseline: InquiryForm? = null,
    val isSaving: Boolean = false,
    val saveError: String? = null,
    val savedMessage: String? = null,
) {
    val isDirty: Boolean get() = form != null && form != baseline
}

data class InquiryDetailUiState(
    val inquiry: LoadState<StaffInquiry> = LoadState.Loading,
    val tab: InquiryDetailTab = InquiryDetailTab.Status,
    val editor: InquiryEditor = InquiryEditor(),
    val isResolving: Boolean = false,
    val resolveError: String? = null,
    val showCancelConfirm: Boolean = false,
    val isCancelling: Boolean = false,
    val cancelError: String? = null,
    /** "Review AI version" expanded on the Status tab. */
    val showRefined: Boolean = false,
) {
    val data: StaffInquiry? get() = inquiry.dataOrNull
    val isRefreshing: Boolean get() = (inquiry as? LoadState.Content)?.refreshing == true
    val form: InquiryForm? get() = editor.form
    /** Closed inquiries (Done / Cancelled) are read-only. */
    val canEdit: Boolean get() = data?.status?.isOpen == true
    val canSave: Boolean get() = canEdit && editor.isDirty && !editor.isSaving
    val canCancel: Boolean get() = data?.status?.isOpen == true
    /** Unresolved blocker shown on the Status tab. */
    val blocker: InquiryBlocker? get() = data?.progress?.blocker?.takeIf { !it.resolved }
}

private data class Actions(
    val tab: InquiryDetailTab = InquiryDetailTab.Status,
    val isResolving: Boolean = false,
    val resolveError: String? = null,
    val showCancelConfirm: Boolean = false,
    val isCancelling: Boolean = false,
    val cancelError: String? = null,
    val showRefined: Boolean = false,
)

/**
 * One staff inquiry: daily status (progress, blocker, log) and its editable details.
 * Shared by the Overview and Inquiries tabs (each pushes its own instance) and reusable by the desktop portal.
 */
class InquiryDetailViewModel(
    private val repository: StaffRepository,
    val inquiryId: String,
) : ViewModel() {
    private val inquiry = MutableStateFlow<LoadState<StaffInquiry>>(LoadState.Loading)
    private val editor = MutableStateFlow(InquiryEditor())
    private val actions = MutableStateFlow(Actions())
    private var refreshJob: Job? = null

    val state: StateFlow<InquiryDetailUiState> = combine(inquiry, editor, actions) { i, e, a ->
        InquiryDetailUiState(
            inquiry = i, tab = a.tab, editor = e,
            isResolving = a.isResolving, resolveError = a.resolveError,
            showCancelConfirm = a.showCancelConfirm, isCancelling = a.isCancelling, cancelError = a.cancelError,
            showRefined = a.showRefined,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, InquiryDetailUiState())

    init {
        refresh()
        viewModelScope.launch { DataChanges.inquiries.collect { refresh(force = true) } }
    }

    fun refresh(force: Boolean = false) {
        if (refreshJob?.isActive == true) {
            if (!force) return
            refreshJob?.cancel()
        }
        refreshJob = viewModelScope.launch {
            inquiry.load { repository.inquiry(inquiryId) }
            inquiry.value.dataOrNull?.let(::syncForm)
        }
    }

    fun selectTab(tab: InquiryDetailTab) = actions.update { it.copy(tab = tab) }

    fun toggleRefined() = actions.update { it.copy(showRefined = !it.showRefined) }

    // -- Details form ------------------------------------------------------------------------------------------

    fun editForm(transform: (InquiryForm) -> InquiryForm) = editor.update { e ->
        val form = e.form ?: return@update e
        e.copy(form = transform(form), saveError = null, savedMessage = null)
    }

    fun setTitle(value: String) = editForm { it.copy(title = value) }
    fun setExplanation(value: String) = editForm { it.copy(explanation = value) }
    fun setExpectation(value: String) = editForm { it.copy(expectation = value) }
    fun toggleType(type: ProjectType) = editForm { it.copy(types = if (type in it.types) it.types - type else it.types + type) }
    fun setPriority(value: InquiryPriority) = editForm { it.copy(priority = value) }
    fun setDeadline(iso: String) = editForm { it.copy(deadline = iso) }
    fun setPrice(value: String) = editForm { it.copy(price = value.filter(Char::isDigit).take(6)) }

    /** Saves the details. Validation and network errors are inline; the user's input is never lost. */
    fun save() {
        val current = inquiry.value.dataOrNull ?: return
        val e = editor.value
        val form = e.form ?: return
        if (!e.isDirty || e.isSaving || !current.status.isOpen) return
        form.validate()?.let { problem ->
            editor.update { it.copy(saveError = problem) }
            return
        }
        editor.update { it.copy(isSaving = true, saveError = null, savedMessage = null) }
        viewModelScope.launch {
            try {
                val updated = repository.updateDetails(inquiryId, form.toDraft(current.draft))
                inquiry.value = LoadState.Content(updated)
                val saved = InquiryForm.from(updated.draft)
                // Keep anything typed while the request was in flight.
                editor.update {
                    val stillEditing = it.form != form
                    it.copy(
                        form = if (stillEditing) it.form else saved, baseline = saved, isSaving = false,
                        savedMessage = "Saved. The team sees the changes now.",
                    )
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                editor.update { it.copy(isSaving = false, saveError = "Couldn’t save. Check your connection and try again.") }
            }
        }
    }

    // -- Blocker ------------------------------------------------------------------------------------------------

    fun resolveBlocker() {
        val blocker = inquiry.value.dataOrNull?.progress?.blocker?.takeIf { !it.resolved } ?: return
        if (actions.value.isResolving) return
        actions.update { it.copy(isResolving = true, resolveError = null) }
        viewModelScope.launch {
            try {
                inquiry.value = LoadState.Content(repository.resolveBlocker(inquiryId, blocker.id))
                actions.update { it.copy(isResolving = false) }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                actions.update { it.copy(isResolving = false, resolveError = "Couldn’t mark it resolved. Try again.") }
            }
        }
    }

    // -- Cancel -------------------------------------------------------------------------------------------------

    fun requestCancel() {
        if (isOpen()) actions.update { it.copy(showCancelConfirm = true, cancelError = null) }
    }

    fun dismissCancel() = actions.update { it.copy(showCancelConfirm = false) }

    fun confirmCancel() {
        if (actions.value.isCancelling || !isOpen()) return
        actions.update { it.copy(showCancelConfirm = false, isCancelling = true, cancelError = null) }
        viewModelScope.launch {
            try {
                val cancelled = repository.cancel(inquiryId)
                inquiry.value = LoadState.Content(cancelled)
                syncForm(cancelled)
                actions.update { it.copy(isCancelling = false, tab = InquiryDetailTab.Status) }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                actions.update { it.copy(isCancelling = false, cancelError = "Couldn’t cancel the inquiry. Try again.") }
            }
        }
    }

    private fun isOpen() = inquiry.value.dataOrNull?.status?.isOpen == true

    /** New server data: reset the form unless the user has unsaved edits (then only move the baseline). */
    private fun syncForm(data: StaffInquiry) {
        val fresh = InquiryForm.from(data.draft)
        editor.update { e ->
            if (e.form == null || !e.isDirty || !data.status.isOpen) e.copy(form = fresh, baseline = fresh)
            else e.copy(baseline = fresh)
        }
    }
}
