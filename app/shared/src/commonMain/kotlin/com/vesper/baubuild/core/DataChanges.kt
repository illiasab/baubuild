package com.vesper.baubuild.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * App-wide "this data changed" signals so every screen showing it reloads without the user pulling to refresh.
 * Repositories emit after a successful write; ViewModels collect and call their refresh.
 */
object DataChanges {
    private val _memberships = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The user joined / requested / left a project: Projects lists drop it, My Work gains it. */
    val memberships: SharedFlow<Unit> = _memberships.asSharedFlow()

    fun membershipsChanged() { _memberships.tryEmit(Unit) }

    private val _inquiries = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Staff posted / edited / cancelled an inquiry or resolved a blocker: Overview and Inquiries reload. */
    val inquiries: SharedFlow<Unit> = _inquiries.asSharedFlow()

    fun inquiriesChanged() { _inquiries.tryEmit(Unit) }
}
