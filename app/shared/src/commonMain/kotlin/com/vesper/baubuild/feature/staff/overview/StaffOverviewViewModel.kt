package com.vesper.baubuild.feature.staff.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.baubuild.core.DataChanges
import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.core.load
import com.vesper.baubuild.core.model.StaffInquiry
import com.vesper.baubuild.core.model.StaffOverview
import com.vesper.baubuild.core.model.User
import com.vesper.baubuild.core.model.WorldProject
import com.vesper.baubuild.data.repository.StaffRepository
import com.vesper.baubuild.feature.staff.inquiries.staffGreeting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the Overview tab shows, loaded together. */
data class StaffOverviewContent(
    val overview: StaffOverview,
    /** The 3 newest inquiries ("Recent Inquiries"). */
    val recent: List<StaffInquiry>,
    val hasInquiries: Boolean,
    /** Ideas shown instead of Recent Inquiries when the user has none yet. Empty otherwise. */
    val worldProjects: List<WorldProject> = emptyList(),
    /** "Hello, Dr. Hale" / "Hello, Nora"; null if unknown. */
    val greeting: String? = null,
)

data class StaffOverviewUiState(val content: LoadState<StaffOverviewContent> = LoadState.Loading) {
    val isRefreshing: Boolean get() = (content as? LoadState.Content)?.refreshing == true
}

/** Staff Overview (mobile); the desktop portal overview can reuse it unchanged. */
class StaffOverviewViewModel(
    private val repository: StaffRepository,
    private val currentUser: () -> User?,
) : ViewModel() {
    private val content = MutableStateFlow<LoadState<StaffOverviewContent>>(LoadState.Loading)
    private var refreshJob: Job? = null

    val state: StateFlow<StaffOverviewUiState> = content.map(::StaffOverviewUiState)
        .stateIn(viewModelScope, SharingStarted.Eagerly, StaffOverviewUiState())

    init {
        refresh()
        viewModelScope.launch { DataChanges.inquiries.collect { refresh(force = true) } }
    }

    fun refresh(force: Boolean = false) {
        if (refreshJob?.isActive == true) {
            if (!force) return
            refreshJob?.cancel()
        }
        refreshJob = viewModelScope.launch { content.load { fetch() } }
    }

    private suspend fun fetch(): StaffOverviewContent = coroutineScope {
        val overview = async { repository.overview() }
        val inquiries = async { repository.inquiries() }
        // The greeting is decoration: a failed profile call must not block the overview.
        val profile = async { optional { repository.profile() } }
        val list = inquiries.await()
        val world = if (list.isEmpty()) optional { repository.worldProjects() }.orEmpty() else emptyList()
        StaffOverviewContent(
            overview = overview.await(),
            recent = list.take(RECENT_LIMIT),
            hasInquiries = list.isNotEmpty(),
            worldProjects = world,
            greeting = staffGreeting(currentUser(), profile.await()),
        )
    }

    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        null
    }

    companion object {
        const val RECENT_LIMIT = 3
    }
}
