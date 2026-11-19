package com.vesper.baubuild.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.baubuild.core.model.Role
import com.vesper.baubuild.core.model.ScoreTier
import com.vesper.baubuild.core.model.User
import com.vesper.baubuild.core.session.SessionState
import com.vesper.baubuild.core.session.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProfileUiState(
    /** Null while the session is restoring or after sign-out. */
    val user: User? = null,
    val signingOut: Boolean = false,
    val signOutError: String? = null,
    /** In-tab navigation: Become a Lead pushed on top of Profile. */
    val showingLead: Boolean = false,
) {
    val score: Int get() = (user?.score ?: 0).coerceIn(0, ScoreTier.MAX_SCORE)
    val tier: ScoreTier get() = ScoreTier.of(score)
    /** Students (not yet Leads, not staff) see the "Become a Lead" entry. */
    val canBecomeLead: Boolean get() = user?.role == Role.Student
}

class ProfileViewModel(
    private val session: SessionStore,
    /** Revokes the backend token (best effort) then clears the local session. */
    private val performSignOut: suspend () -> Unit = { session.signOut() },
) : ViewModel() {
    private val local = MutableStateFlow(ProfileUiState())

    val state: StateFlow<ProfileUiState> = combine(session.state, local) { s, l ->
        l.copy(user = (s as? SessionState.SignedIn)?.user)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, local.value.copy(user = (session.state.value as? SessionState.SignedIn)?.user))

    fun openBecomeLead() { local.value = local.value.copy(showingLead = true) }

    fun closeBecomeLead() { local.value = local.value.copy(showingLead = false) }

    fun signOut() {
        if (local.value.signingOut) return
        local.value = local.value.copy(signingOut = true, signOutError = null)
        viewModelScope.launch {
            try {
                performSignOut()
                local.value = local.value.copy(signingOut = false, showingLead = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                local.value = local.value.copy(signingOut = false, signOutError = "Couldn’t sign out. Try again.")
            }
        }
    }
}
