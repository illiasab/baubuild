package com.vesper.baubuild.feature.profile.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.baubuild.core.media.LocalImageStore
import com.vesper.baubuild.core.media.PickedImage
import com.vesper.baubuild.core.model.AccountKind
import com.vesper.baubuild.core.model.User
import com.vesper.baubuild.core.session.SessionState
import com.vesper.baubuild.core.session.SessionStore
import com.vesper.baubuild.data.repository.ProfileRepository
import com.vesper.baubuild.data.repository.ProfileUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What happens to the profile photo on save. */
sealed interface PhotoEdit {
    data object Unchanged : PhotoEdit
    data object Removed : PhotoEdit
    /** A newly picked photo, uploaded on save. Identity-compared (bytes). */
    class Picked(val image: PickedImage) : PhotoEdit
}

data class EditProfileUiState(
    /** The user as it was when editing started. Null when nobody is signed in. */
    val original: User? = null,
    val displayName: String = "",
    val bio: String = "",
    val skills: List<String> = emptyList(),
    /** Text in the "Add a skill" field, not yet committed. */
    val skillDraft: String = "",
    val githubUsername: String = "",
    /** Staff only: job title ("Professor") and department. */
    val title: String = "",
    val department: String = "",
    val photo: PhotoEdit = PhotoEdit.Unchanged,
    val saving: Boolean = false,
    /** Inline error under the Save button (save failed). The form keeps its input. */
    val error: String? = null,
    /** Inline note under the skills editor (limit reached, too long). */
    val skillError: String? = null,
    val showDiscardConfirm: Boolean = false,
    /** Saved or closed: the host should pop the screen. */
    val finished: Boolean = false,
) {
    val normalizedName: String get() = displayName.trim().replace(whitespace, " ")
    val normalizedBio: String get() = bio.trim()
    val normalizedGithub: String get() = EditProfileRules.normalizeGithub(githubUsername)

    val nameError: String?
        get() = when {
            normalizedName.length < EditProfileRules.MIN_NAME -> "Enter your name (at least ${EditProfileRules.MIN_NAME} characters)."
            normalizedName.length > EditProfileRules.MAX_NAME -> "Keep your name under ${EditProfileRules.MAX_NAME} characters."
            else -> null
        }
    val githubError: String?
        get() = if (normalizedGithub.isEmpty() || EditProfileRules.isValidGithub(normalizedGithub)) null
        else "GitHub usernames use letters, numbers and single hyphens (max 39)."

    val bioRemaining: Int get() = EditProfileRules.MAX_BIO - bio.length
    val isValid: Boolean get() = original != null && nameError == null && githubError == null && bio.length <= EditProfileRules.MAX_BIO

    val isDirty: Boolean
        get() {
            val u = original ?: return false
            return photo != PhotoEdit.Unchanged ||
                normalizedName != u.displayName ||
                normalizedBio != u.bio ||
                skills != u.skills ||
                normalizedGithub != (u.githubUsername ?: "") ||
                (isStaff && (title.trim() != u.title.orEmpty() || department.trim() != u.department.orEmpty()))
        }

    /** Staff accounts also edit their title and department. */
    val isStaff: Boolean get() = original?.kind == AccountKind.BauStaff

    val canSave: Boolean get() = isDirty && isValid && !saving
    val canAddMoreSkills: Boolean get() = skills.size < EditProfileRules.MAX_SKILLS

    /** Suggestions not yet added, filtered by the current draft. */
    val skillSuggestions: List<String>
        get() {
            if (!canAddMoreSkills) return emptyList()
            val query = skillDraft.trim()
            return EditProfileRules.SUGGESTED_SKILLS.filter { s ->
                skills.none { it.equals(s, ignoreCase = true) } && (query.isEmpty() || s.contains(query, ignoreCase = true))
            }
        }

    /** Whether the photo shown should be the user's existing one (vs picked / removed). */
    val hasPhoto: Boolean
        get() = when (photo) {
            PhotoEdit.Unchanged -> original?.avatarUrl != null
            PhotoEdit.Removed -> false
            is PhotoEdit.Picked -> true
        }

    private companion object {
        val whitespace = Regex("\\s+")
    }
}

object EditProfileRules {
    const val MIN_NAME = 2
    const val MAX_NAME = 50
    const val MAX_BIO = 280
    const val MAX_TITLE = 80
    const val MAX_SKILLS = 10
    const val MAX_SKILL_LENGTH = 30
    const val MAX_GITHUB = 39
    val SUGGESTED_SKILLS = listOf("Kotlin", "Swift", "Python", "JavaScript", "TypeScript", "Go", "SQL", "React", "Figma", "Java")

    /** Accepts `name`, `@name` or a pasted `https://github.com/name` URL. */
    fun normalizeGithub(raw: String): String = raw.trim()
        .removePrefix("https://").removePrefix("http://").removePrefix("www.")
        .let { if (it.startsWith("github.com/", ignoreCase = true)) it.substring("github.com/".length) else it }
        .removePrefix("@")
        .trimEnd('/')

    fun isValidGithub(name: String): Boolean =
        name.length in 1..MAX_GITHUB &&
            name.all { it.isLetterOrDigit() && it.code < 128 || it == '-' } &&
            !name.startsWith('-') && !name.endsWith('-') && "--" !in name
}

/**
 * Edit Profile (photo, name, bio, skills, GitHub). Snapshot of the session user at creation; on save uploads a
 * new photo (if any), PATCHes the profile and pushes the result into [SessionStore] so every screen updates.
 */
class EditProfileViewModel(
    private val session: SessionStore,
    private val repository: ProfileRepository,
    /** Live GitHub connection's login. When connected it wins over the form field (which is read-only then). */
    private val connectedGitHubLogin: () -> String? = { null },
) : ViewModel() {
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<EditProfileUiState> = _state.asStateFlow()

    /** URL of an already-uploaded [PhotoEdit.Picked] image, so a retry after a failed PATCH doesn't re-upload. */
    private var uploaded: Pair<PickedImage, String>? = null

    private fun initialState(): EditProfileUiState {
        val user = (session.state.value as? SessionState.SignedIn)?.user ?: return EditProfileUiState()
        return EditProfileUiState(
            original = user,
            displayName = user.displayName,
            bio = user.bio,
            skills = user.skills,
            githubUsername = user.githubUsername.orEmpty(),
            title = user.title.orEmpty(),
            department = user.department.orEmpty(),
        )
    }

    private inline fun update(block: EditProfileUiState.() -> EditProfileUiState) {
        _state.value = _state.value.block()
    }

    fun onNameChange(text: String) = update { copy(displayName = text.take(EditProfileRules.MAX_NAME + 10), error = null) }

    /** Bio is capped at [EditProfileRules.MAX_BIO]; extra characters are dropped. */
    fun onBioChange(text: String) = update { copy(bio = text.take(EditProfileRules.MAX_BIO), error = null) }

    fun onGithubChange(text: String) = update { copy(githubUsername = text.take(100), error = null) }

    fun onTitleChange(text: String) = update { copy(title = text.take(EditProfileRules.MAX_TITLE), error = null) }

    fun onDepartmentChange(text: String) = update { copy(department = text.take(EditProfileRules.MAX_TITLE), error = null) }

    fun onSkillDraftChange(text: String) {
        // A typed comma or newline commits the skill (hardware keyboards / pasted lists).
        if (text.any { it == ',' || it == '\n' }) {
            val parts = text.split(',', '\n')
            parts.dropLast(1).forEach { addSkill(it) }
            update { copy(skillDraft = parts.last().take(EditProfileRules.MAX_SKILL_LENGTH)) }
            return
        }
        update { copy(skillDraft = text.take(EditProfileRules.MAX_SKILL_LENGTH), skillError = null) }
    }

    /** Commits the draft (Enter / Done). */
    fun commitSkillDraft() {
        if (addSkill(_state.value.skillDraft)) update { copy(skillDraft = "") }
    }

    /** Adds [raw] (trimmed, case-insensitive dedupe). Returns true when the input was consumed (added or duplicate). */
    fun addSkill(raw: String): Boolean {
        val skill = raw.trim().replace(Regex("\\s+"), " ")
        if (skill.isEmpty()) return false
        val s = _state.value
        if (s.skills.any { it.equals(skill, ignoreCase = true) }) {
            update { copy(skillError = null) }
            return true
        }
        if (skill.length > EditProfileRules.MAX_SKILL_LENGTH) {
            update { copy(skillError = "Keep each skill under ${EditProfileRules.MAX_SKILL_LENGTH} characters.") }
            return false
        }
        if (!s.canAddMoreSkills) {
            update { copy(skillError = "You can list up to ${EditProfileRules.MAX_SKILLS} skills.") }
            return false
        }
        update { copy(skills = skills + skill, skillError = null, error = null) }
        return true
    }

    fun removeSkill(skill: String) = update { copy(skills = skills - skill, skillError = null, error = null) }

    /** Result of the platform picker; null (cancelled or unreadable) leaves the photo as it was. */
    fun onPhotoPicked(image: PickedImage?) {
        if (image == null) return
        update { copy(photo = PhotoEdit.Picked(image), error = null) }
    }

    fun removePhoto() {
        val hadOriginal = _state.value.original?.avatarUrl != null
        update { copy(photo = if (hadOriginal) PhotoEdit.Removed else PhotoEdit.Unchanged, error = null) }
    }

    /** Back / Cancel: closes right away when nothing changed, otherwise asks first. Ignored while saving. */
    fun requestClose() {
        val s = _state.value
        if (s.saving) return
        if (s.isDirty) update { copy(showDiscardConfirm = true) } else update { copy(finished = true) }
    }

    fun keepEditing() = update { copy(showDiscardConfirm = false) }

    fun discardChanges() = update { copy(showDiscardConfirm = false, finished = true) }

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        // Commit a half-typed skill so it isn't silently lost.
        if (s.skillDraft.isNotBlank()) commitSkillDraft()
        val form = _state.value
        update { copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                var avatarUrl: String? = null
                var removeAvatar = false
                when (val photo = form.photo) {
                    PhotoEdit.Unchanged -> Unit
                    PhotoEdit.Removed -> removeAvatar = true
                    is PhotoEdit.Picked -> {
                        val cached = uploaded?.takeIf { it.first === photo.image }?.second
                        avatarUrl = cached ?: repository.uploadAvatar(photo.image.bytes, photo.image.mimeType).also { url ->
                            uploaded = photo.image to url
                            LocalImageStore.put(url, photo.image.bytes) // render immediately, no network round-trip
                        }
                    }
                }
                val updated = repository.updateProfile(
                    ProfileUpdate(
                        displayName = form.normalizedName,
                        bio = form.normalizedBio,
                        skills = form.skills,
                        // Connecting a different account via "Manage" while editing must not be undone by Save.
                        githubUsername = connectedGitHubLogin() ?: form.normalizedGithub,
                        avatarUrl = avatarUrl,
                        removeAvatar = removeAvatar,
                        title = form.title.trim().takeIf { form.isStaff },
                        department = form.department.trim().takeIf { form.isStaff },
                    ),
                )
                session.updateUser(updated)
                update { copy(saving = false, finished = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                update { copy(saving = false, error = "Couldn’t save your profile. Check your connection and try again.") }
            }
        }
    }
}
