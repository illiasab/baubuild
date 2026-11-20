package com.vesper.baubuild.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.baubuild.core.model.Role
import com.vesper.baubuild.core.model.ScoreTier
import com.vesper.baubuild.core.model.User
import com.vesper.baubuild.core.session.SessionState
import com.vesper.baubuild.core.session.SessionStore
import com.vesper.baubuild.data.github.GitHubAccount
import com.vesper.baubuild.data.http.config.FeatureFlags
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
    /** In-tab navigation: Edit Profile pushed on top of Profile. */
    val editingProfile: Boolean = false,
    /** Bumped on each open so Edit Profile starts from a fresh form. */
    val editSession: Int = 0,
    /** In-tab navigation: Connect GitHub pushed on top of Profile (or of Edit Profile, via "Manage"). */
    val showingGitHub: Boolean = false,
    /** The connected GitHub account (token in secure storage), or null. */
    val gitHubAccount: GitHubAccount? = null,
    /** `features.github`: off → no Connect / Manage actions, a quiet note instead. */
    val gitHubEnabled: Boolean = true,
) {
    /** GitHub login to show: the connected account, else the username saved on the profile. */
    val gitHubLogin: String?
        get() = gitHubAccount?.login ?: user?.githubUsername?.trim()?.removePrefix("@")?.takeIf { it.isNotEmpty() }
    val score: Int get() = (user?.score ?: 0).coerceIn(0, ScoreTier.MAX_SCORE)
    val tier: ScoreTier get() = ScoreTier.of(score)
    /** Students (not yet Leads, not staff) see the "Become a Lead" entry. */
    val canBecomeLead: Boolean get() = user?.role == Role.Student
}

class ProfileViewModel(
    private val session: SessionStore,
    /** Revokes the backend token (best effort) then clears the local session. */
    private val performSignOut: suspend () -> Unit = { session.signOut() },
    /** [com.vesper.baubuild.data.github.GitHubRepository.account]. */
    private val gitHubAccount: StateFlow<GitHubAccount?> = MutableStateFlow(null),
    /** `/v1/config` feature flags; `github` gates the Connect GitHub actions. */
    private val features: StateFlow<FeatureFlags> = MutableStateFlow(FeatureFlags(mapOf("github" to true))),
) : ViewModel() {
    private val local = MutableStateFlow(ProfileUiState())

    val state: StateFlow<ProfileUiState> = combine(session.state, local, gitHubAccount, features) { s, l, gh, f ->
        l.copy(user = (s as? SessionState.SignedIn)?.user, gitHubAccount = gh, gitHubEnabled = f.github)
    }.stateIn(
        viewModelScope, SharingStarted.Eagerly,
        local.value.copy(
            user = (session.state.value as? SessionState.SignedIn)?.user, gitHubAccount = gitHubAccount.value,
            gitHubEnabled = features.value.github,
        ),
    )

    fun openBecomeLead() { local.value = local.value.copy(showingLead = true) }

    fun closeBecomeLead() { local.value = local.value.copy(showingLead = false) }

    fun openEditProfile() {
        local.value = local.value.copy(editingProfile = true, editSession = local.value.editSession + 1)
    }

    fun closeEditProfile() { local.value = local.value.copy(editingProfile = false) }

    fun openGitHub() {
        if (state.value.gitHubEnabled) local.value = local.value.copy(showingGitHub = true)
    }

    fun closeGitHub() { local.value = local.value.copy(showingGitHub = false) }

    fun signOut() {
        if (local.value.signingOut) return
        local.value = local.value.copy(signingOut = true, signOutError = null)
        viewModelScope.launch {
            try {
                performSignOut()
                local.value = local.value.copy(signingOut = false, showingLead = false, editingProfile = false, showingGitHub = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                local.value = local.value.copy(signingOut = false, signOutError = "Couldn’t sign out. Try again.")
            }
        }
    }
}
