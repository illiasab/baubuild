package com.vesper.baubuild.feature.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.core.load
import com.vesper.baubuild.data.repository.AppNotification
import com.vesper.baubuild.data.repository.NotificationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

data class NotificationsUiState(
    val notifications: LoadState<List<AppNotification>> = LoadState.Loading,
    /** Reference time for relative timestamps ("5m ago"), refreshed on every load. */
    val now: Long = 0L,
) {
    val isRefreshing: Boolean get() = (notifications as? LoadState.Content)?.refreshing == true
    val unreadCount: Int get() = (notifications as? LoadState.Content)?.data?.count { !it.read } ?: 0
}

class NotificationsViewModel(
    private val repository: NotificationRepository,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ViewModel() {
    private val loaded = MutableStateFlow<LoadState<List<AppNotification>>>(LoadState.Loading)
    /** Ids marked read on this device; applied over loaded data so a refresh racing a mark-read can't undo it. */
    private val readLocally = MutableStateFlow<Set<String>>(emptySet())
    private val now = MutableStateFlow(clock())
    private var refreshJob: Job? = null

    val state: StateFlow<NotificationsUiState> = combine(loaded, readLocally, now) { load, read, t ->
        NotificationsUiState(load.withRead(read), t)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, NotificationsUiState(now = now.value))

    init { refresh() }

    /** Pull-to-refresh / retry. Content stays visible while refreshing and on failure (marked stale). */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            loaded.load { repository.notifications() }
            now.value = clock()
        }
    }

    /** Tap on a notification: shows it read immediately; reverts quietly if the backend call fails. */
    fun markRead(id: String) {
        val item = state.value.notifications.let { it as? LoadState.Content }?.data?.firstOrNull { it.id == id } ?: return
        if (item.read) return
        readLocally.update { it + id }
        viewModelScope.launch {
            try {
                repository.markRead(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                readLocally.update { it - id }
            }
        }
    }

    private fun LoadState<List<AppNotification>>.withRead(read: Set<String>): LoadState<List<AppNotification>> =
        if (this is LoadState.Content && read.isNotEmpty()) {
            copy(data = data.map { if (!it.read && it.id in read) it.copy(read = true) else it })
        } else this
}

/** Short relative time for a notification row: "Just now", "5m", "3h", "Yesterday", "4d", "2w", "1y". */
fun relativeTime(createdAtEpochMs: Long, nowEpochMs: Long): String {
    val minutes = ((nowEpochMs - createdAtEpochMs).coerceAtLeast(0)) / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "Just now"
        hours < 1 -> "${minutes}m"
        days < 1 -> "${hours}h"
        days == 1L -> "Yesterday"
        days < 7 -> "${days}d"
        days < 365 -> "${days / 7}w"
        else -> "${days / 365}y"
    }
}
