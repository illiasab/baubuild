package com.vesper.baubuild.feature.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.core.dataOrNull
import com.vesper.baubuild.core.load
import com.vesper.baubuild.core.model.ReviewStatus
import com.vesper.baubuild.data.repository.WorkRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ReviewUiState(
    val reviews: LoadState<List<ReviewStatus>> = LoadState.Loading,
    /** Project id of the review opened from the list (only used when there is more than one review). */
    val selectedProjectId: String? = null,
) {
    /** The review to show in detail: the only one, or the one picked from the list. */
    val detail: ReviewStatus?
        get() {
            val list = reviews.dataOrNull ?: return null
            return if (list.size == 1) list.first() else list.firstOrNull { it.project.id == selectedProjectId }
        }

    /** Back closes the detail only when it was pushed from the list. */
    val canGoBack: Boolean get() = (reviews.dataOrNull?.size ?: 0) > 1 && detail != null
}

class ReviewViewModel(private val work: WorkRepository) : ViewModel() {
    private val reviews = MutableStateFlow<LoadState<List<ReviewStatus>>>(LoadState.Loading)
    private val selected = MutableStateFlow<String?>(null)
    private var refreshJob: Job? = null

    val state: StateFlow<ReviewUiState> = combine(reviews, selected) { r, s -> ReviewUiState(r, s) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ReviewUiState())

    init { refresh() }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch { reviews.load { work.reviews() } }
    }

    fun open(projectId: String) { selected.value = projectId }

    fun back() { selected.value = null }
}
