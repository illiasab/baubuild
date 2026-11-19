package com.vesper.baubuild.feature.staff.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.core.dataOrNull
import com.vesper.baubuild.core.load
import com.vesper.baubuild.core.model.SeniorEngineer
import com.vesper.baubuild.core.model.StaffProfile
import com.vesper.baubuild.core.model.User
import com.vesper.baubuild.core.notifications.NotificationPermission
import com.vesper.baubuild.core.notifications.NotificationPermissionStatus
import com.vesper.baubuild.core.session.SessionState
import com.vesper.baubuild.core.session.SessionStore
import com.vesper.baubuild.data.repository.StaffRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The two staff notification preferences. */
enum class StaffNotification(val label: String) {
    ProjectDone("When a project is done"),
    StudentJoins("When a student joins"),
}

/**
 * The open senior-engineer bio (bottom sheet on mobile, dialog on desktop). [engineer] is shown right away — the
 * list item, or the last full profile we loaded — and quietly replaced when [loading] finishes. [error] is an
 * inline note; the bio stays visible.
 */
data class SeniorEngineerSheet(
    val engineer: SeniorEngineer,
    val loading: Boolean = false,
    val error: String? = null,
)

data class StaffProfileUiState(
    /** Null while the session is restoring or after sign-out. */
    val user: User? = null,
    val profile: LoadState<StaffProfile> = LoadState.Loading,
    /** OS notification permission; null until first checked. */
    val permission: NotificationPermissionStatus? = null,
    /** A toggle whose permission prompt or save is in flight (toggles are disabled meanwhile). */
    val busy: StaffNotification? = null,
    /** Inline error under the Notifications card when a save failed. */
    val notificationError: String? = null,
    val signingOut: Boolean = false,
    val signOutError: String? = null,
    /** In-tab navigation: Edit Profile pushed on top of Profile. */
    val editingProfile: Boolean = false,
    /** Bumped on each open so Edit Profile starts from a fresh form. */
    val editSession: Int = 0,
    /** The senior engineer whose bio is open, if any. */
    val engineerSheet: SeniorEngineerSheet? = null,
) {
    private val data: StaffProfile? get() = profile.dataOrNull

    /** "Dr. Nora Hale" for professors, the plain display name otherwise. */
    val displayName: String
        get() {
            val name = user?.displayName.orEmpty()
            val professor = data?.title?.trim()?.equals("Professor", ignoreCase = true) == true
            return if (professor && !name.startsWith("Dr.", ignoreCase = true) && !name.startsWith("Dr ", ignoreCase = true)) "Dr. $name" else name
        }

    /** "Professor · Computer Science". */
    val subtitle: String? get() = data?.let { listOf(it.title, it.department).filter(String::isNotBlank).joinToString(" · ") }

    /** "CMPS433, CMPS210", or "No classes" for staff who don't teach. */
    val classesLabel: String get() = data?.classes?.filter(String::isNotBlank).orEmpty().joinToString().ifEmpty { "No classes" }

    val seniorEngineers: List<SeniorEngineer> get() = data?.seniorEngineers.orEmpty()

    /** "Ilias, Arian". */
    val seniorEngineersLabel: String get() = seniorEngineers.joinToString { it.name }.ifEmpty { "None yet" }

    /** The OS blocks notifications: toggles show off and the "Open Settings" hint is shown. */
    val notificationsBlocked: Boolean get() = permission == NotificationPermissionStatus.Denied

    /** What the switch shows: the saved preference, but off while the OS blocks notifications. */
    fun isOn(kind: StaffNotification): Boolean {
        val p = data ?: return false
        if (notificationsBlocked) return false
        return when (kind) {
            StaffNotification.ProjectDone -> p.notifyWhenDone
            StaffNotification.StudentJoins -> p.notifyWhenStudentJoins
        }
    }
}

/**
 * Staff Profile tab: identity, stats, details, notification preferences (gated by the OS notification permission)
 * and Sign Out. The platform permission is bound from the UI with [bindPermission] (Android needs the hosting
 * activity to request it).
 */
class StaffProfileViewModel(
    private val session: SessionStore,
    private val repository: StaffRepository,
    /** Revokes the backend token (best effort) then clears the local session. */
    private val performSignOut: suspend () -> Unit = { session.signOut() },
) : ViewModel() {
    private val local = MutableStateFlow(StaffProfileUiState())
    private val profile = MutableStateFlow<LoadState<StaffProfile>>(LoadState.Loading)
    private var permission: NotificationPermission? = null

    val state: StateFlow<StaffProfileUiState> = combine(session.state, local, profile) { s, l, p ->
        l.copy(user = (s as? SessionState.SignedIn)?.user, profile = p)
    }.stateIn(
        viewModelScope, SharingStarted.Eagerly,
        local.value.copy(user = (session.state.value as? SessionState.SignedIn)?.user),
    )

    init { refresh() }

    fun refresh() {
        viewModelScope.launch { profile.load { repository.profile() } }
    }

    /** Attaches the platform permission and re-reads its status (also call when the screen resumes). */
    fun bindPermission(permission: NotificationPermission) {
        this.permission = permission
        refreshPermission()
    }

    /** Re-reads the OS status, e.g. when the user comes back from Settings. */
    fun refreshPermission() {
        val p = permission ?: return
        viewModelScope.launch {
            val status = runCatching { p.status() }.getOrNull() ?: return@launch
            local.value = local.value.copy(permission = status)
        }
    }

    fun openNotificationSettings() { permission?.openSystemSettings() }

    fun setNotification(kind: StaffNotification, on: Boolean) {
        val current = profile.value.dataOrNull ?: return
        if (local.value.busy != null || local.value.signingOut) return
        local.value = local.value.copy(busy = kind, notificationError = null)
        viewModelScope.launch {
            try {
                if (on && !ensurePermission()) return@launch
                save(current, kind, on)
            } finally {
                local.value = local.value.copy(busy = null)
            }
        }
    }

    /** True when notifications may be shown; asks the OS when it hasn't been decided yet. */
    private suspend fun ensurePermission(): Boolean {
        val p = permission ?: return true // Not bound (tests / previews): nothing to ask.
        val status = try {
            p.status()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            NotificationPermissionStatus.NotDetermined
        }
        val granted = when (status) {
            NotificationPermissionStatus.Granted -> true
            NotificationPermissionStatus.Denied -> false
            NotificationPermissionStatus.NotDetermined -> try {
                p.request()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                false
            }
        }
        local.value = local.value.copy(
            permission = if (granted) NotificationPermissionStatus.Granted else NotificationPermissionStatus.Denied,
        )
        return granted
    }

    private suspend fun save(before: StaffProfile, kind: StaffNotification, on: Boolean) {
        val latest = profile.value.dataOrNull ?: before
        val updated = when (kind) {
            StaffNotification.ProjectDone -> latest.copy(notifyWhenDone = on)
            StaffNotification.StudentJoins -> latest.copy(notifyWhenStudentJoins = on)
        }
        profile.value = LoadState.Content(updated) // optimistic
        try {
            val saved = repository.updateNotifications(updated.notifyWhenDone, updated.notifyWhenStudentJoins)
            profile.value = LoadState.Content(saved)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            profile.value = LoadState.Content(latest)
            local.value = local.value.copy(notificationError = "Couldn’t save your notification settings. Try again.")
        }
    }

    /** Full profiles loaded this session, by id: reopening a bio shows them instantly. */
    private val engineerDetails = mutableMapOf<String, SeniorEngineer>()
    private var engineerJob: Job? = null

    /** Opens [engineer]'s bio with what we already have, then refreshes it from the backend in the background. */
    fun openSeniorEngineer(engineer: SeniorEngineer) {
        val shown = engineerDetails[engineer.id]?.let { engineer.mergedWith(it) } ?: engineer
        local.value = local.value.copy(engineerSheet = SeniorEngineerSheet(shown, loading = true))
        engineerJob?.cancel()
        engineerJob = viewModelScope.launch {
            val result = try {
                Result.success(repository.seniorEngineer(engineer.id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
            val sheet = local.value.engineerSheet?.takeIf { it.engineer.id == engineer.id } ?: return@launch
            local.value = local.value.copy(
                engineerSheet = result.fold(
                    onSuccess = { full ->
                        engineerDetails[engineer.id] = full
                        SeniorEngineerSheet(sheet.engineer.mergedWith(full))
                    },
                    onFailure = { sheet.copy(loading = false, error = "Couldn’t load the full profile. Showing what we have.") },
                ),
            )
        }
    }

    fun closeSeniorEngineer() {
        engineerJob?.cancel()
        engineerJob = null
        local.value = local.value.copy(engineerSheet = null)
    }

    fun openEditProfile() {
        local.value = local.value.copy(editingProfile = true, editSession = local.value.editSession + 1)
    }

    fun closeEditProfile() { local.value = local.value.copy(editingProfile = false) }

    fun signOut() {
        if (local.value.signingOut) return
        local.value = local.value.copy(signingOut = true, signOutError = null)
        viewModelScope.launch {
            try {
                performSignOut()
                local.value = local.value.copy(signingOut = false, editingProfile = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                local.value = local.value.copy(signingOut = false, signOutError = "Couldn’t sign out. Try again.")
            }
        }
    }
}

/** [newer]'s fields where it has them; ours where it is blank (a public profile may lack title, email…). */
internal fun SeniorEngineer.mergedWith(newer: SeniorEngineer): SeniorEngineer = SeniorEngineer(
    id = id,
    name = newer.name.ifBlank { name },
    title = newer.title.ifBlank { title },
    bio = newer.bio.ifBlank { bio },
    avatarUrl = newer.avatarUrl ?: avatarUrl,
    skills = newer.skills.ifEmpty { skills },
    githubUsername = newer.githubUsername ?: githubUsername,
    email = newer.email ?: email,
    isInterviewer = newer.isInterviewer,
)
