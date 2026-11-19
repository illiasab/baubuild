package com.vesper.baubuild.feature.staff.inquiries

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.baubuild.core.DataChanges
import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.core.load
import com.vesper.baubuild.core.model.StaffInquiry
import com.vesper.baubuild.data.repository.StaffRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Segmented control on the Inquiries tab. */
enum class InquiryFilter(val label: String) {
    All("All"), Open("Open"), Closed("Closed");

    fun matches(inquiry: StaffInquiry): Boolean = when (this) {
        All -> true
        Open -> inquiry.status.isOpen
        Closed -> !inquiry.status.isOpen
    }
}

/** One titled block of rows ("AI Refining", "Posted", "Closed"). */
data class InquirySection(val group: InquiryGroup, val inquiries: List<StaffInquiry>) {
    val title: String get() = group.title
}

/** Filters [all] and groups it in display order, keeping the backend's newest-first order inside each group. */
fun sectionsFor(all: List<StaffInquiry>, filter: InquiryFilter): List<InquirySection> {
    val visible = all.filter(filter::matches)
    return InquiryGroup.entries.mapNotNull { group ->
        visible.filter { InquiryGroup.of(it.status) == group }.takeIf { it.isNotEmpty() }?.let { InquirySection(group, it) }
    }
}

data class StaffInquiriesUiState(
    val inquiries: LoadState<List<StaffInquiry>> = LoadState.Loading,
    val filter: InquiryFilter = InquiryFilter.All,
) {
    val isRefreshing: Boolean get() = (inquiries as? LoadState.Content)?.refreshing == true
    val sections: List<InquirySection>
        get() = (inquiries as? LoadState.Content)?.data?.let { sectionsFor(it, filter) }.orEmpty()
    val hasAny: Boolean get() = (inquiries as? LoadState.Content)?.data?.isNotEmpty() == true
}

/** Inquiries tab (mobile) — also usable as-is by the desktop portal's inquiries page. */
class StaffInquiriesViewModel(private val repository: StaffRepository) : ViewModel() {
    private val inquiries = MutableStateFlow<LoadState<List<StaffInquiry>>>(LoadState.Loading)
    private val filter = MutableStateFlow(InquiryFilter.All)
    private var refreshJob: Job? = null

    val state: StateFlow<StaffInquiriesUiState> = combine(inquiries, filter, ::StaffInquiriesUiState)
        .stateIn(viewModelScope, SharingStarted.Eagerly, StaffInquiriesUiState())

    init {
        refresh()
        // Posting / editing / cancelling elsewhere (New Inquiry, detail) must show here without a pull.
        viewModelScope.launch { DataChanges.inquiries.collect { refresh(force = true) } }
    }

    fun selectFilter(value: InquiryFilter) { filter.value = value }

    /** Pull-to-refresh / retry. [force] restarts a running load so a change signal is never lost. */
    fun refresh(force: Boolean = false) {
        if (refreshJob?.isActive == true) {
            if (!force) return
            refreshJob?.cancel()
        }
        refreshJob = viewModelScope.launch { inquiries.load { repository.inquiries() } }
    }
}
