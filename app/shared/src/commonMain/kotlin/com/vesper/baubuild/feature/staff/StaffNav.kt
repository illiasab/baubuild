package com.vesper.baubuild.feature.staff

import com.vesper.baubuild.core.model.AccountKind
import com.vesper.baubuild.core.model.User
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Staff (@bau.edu) get the staff app (Overview / Inquiries / Profile + yellow "+"), everyone else the student app. */
val User.isStaff: Boolean get() = kind == AccountKind.BauStaff

enum class StaffTab { Overview, Inquiries, Profile }

sealed interface StaffNavEvent {
    /** Open the New Inquiry flow (same as tapping the yellow + button). */
    data object OpenNewInquiry : StaffNavEvent
    data class SelectTab(val tab: StaffTab) : StaffNavEvent
}

/**
 * Cross-tab navigation requests from staff screens (e.g. Overview "See All" → Inquiries tab, empty state → "+").
 * Every staff shell (native iOS, Compose Android, desktop portal) collects [events] and acts on them.
 */
object StaffNav {
    private val _events = MutableSharedFlow<StaffNavEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<StaffNavEvent> = _events.asSharedFlow()

    fun openNewInquiry() { _events.tryEmit(StaffNavEvent.OpenNewInquiry) }
    fun selectTab(tab: StaffTab) { _events.tryEmit(StaffNavEvent.SelectTab(tab)) }
}
