package com.vesper.baubuild.feature.mywork

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.core.load
import com.vesper.baubuild.core.model.Membership
import com.vesper.baubuild.core.model.MembershipStatus
import com.vesper.baubuild.data.repository.WorkRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The two sections of the My Work list. */
data class MyWorkSections(
    /** Projects the student is a member of ("I’m In"). These open the workspace. */
    val active: List<Membership>,
    /** Join requests that still need a professor / dev lead decision. */
    val waiting: List<Membership>,
) {
    val isEmpty: Boolean get() = active.isEmpty() && waiting.isEmpty()

    companion object {
        fun from(memberships: List<Membership>) = MyWorkSections(
            active = memberships.filter { it.status.isActive },
            waiting = memberships.filter { !it.status.isActive },
        )
    }
}

/** `In` and `Approved` both mean the student is in the project; the rest are still pending a decision. */
internal val MembershipStatus.isActive: Boolean
    get() = this == MembershipStatus.In || this == MembershipStatus.Approved

data class MyWorkUiState(
    val work: LoadState<MyWorkSections> = LoadState.Loading,
) {
    val isRefreshing: Boolean get() = (work as? LoadState.Content)?.refreshing == true
}

class MyWorkViewModel(private val repository: WorkRepository) : ViewModel() {
    private val work = MutableStateFlow<LoadState<MyWorkSections>>(LoadState.Loading)
    private var refreshJob: Job? = null

    val state: StateFlow<MyWorkUiState> = work
        .map { MyWorkUiState(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MyWorkUiState())

    init { refresh() }

    /** Pull-to-refresh / retry. Keeps the current list visible while loading (zero-downtime rule). */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            work.load { MyWorkSections.from(repository.myWork()) }
        }
    }
}
