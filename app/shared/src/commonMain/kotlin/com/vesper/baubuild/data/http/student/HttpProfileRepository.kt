package com.vesper.baubuild.data.http.student

import com.vesper.baubuild.core.media.LocalImageStore
import com.vesper.baubuild.core.model.AccountKind
import com.vesper.baubuild.core.model.AccountPolicy
import com.vesper.baubuild.core.model.Role
import com.vesper.baubuild.core.model.User
import com.vesper.baubuild.core.network.apiCall
import com.vesper.baubuild.data.http.student.dto.UpdateMeDto
import com.vesper.baubuild.data.http.student.dto.UserDto
import com.vesper.baubuild.data.repository.ProfileRepository
import com.vesper.baubuild.data.repository.ProfileUpdate
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [ProfileRepository] on the BAU BUILD backend.
 *
 * `PATCH /v1/me` accepts only `bio` (1..280 chars) and `skills` (non-empty list of non-blank strings); omitted
 * keys are left unchanged, so a blank bio / empty skill list is simply not sent (the backend can't clear them).
 * Errors: 400 `invalid_body`.
 *
 * Backend gaps, kept on the device instead of failing the save: display name, GitHub username and avatar have
 * no backend field, and there is no avatar upload endpoint — [uploadAvatar] returns a `memory://` URL
 * registered in [LocalImageStore], so it lasts only for this app run.
 */
class HttpProfileRepository(
    private val client: HttpClient,
    private val currentUser: () -> User?,
    private val policy: AccountPolicy = AccountPolicy(),
) : ProfileRepository {
    private val avatarLock = Mutex()
    private var nextAvatarId = 1

    override suspend fun updateProfile(update: ProfileUpdate): User {
        val body = UpdateMeDto(
            bio = update.bio.trim().takeIf { it.isNotEmpty() }?.take(MAX_BIO),
            skills = update.skills.map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() },
        )
        val saved: UserDto = apiCall {
            client.patch("v1/me") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }.body()
        }
        val base = currentUser()?.takeIf { it.id == saved.id } ?: saved.toDomain(policy)
        val avatarUrl = when {
            update.removeAvatar -> null
            update.avatarUrl != null -> update.avatarUrl
            else -> base.avatarUrl
        }
        return base.copy(
            bio = saved.bio.orEmpty(),
            skills = saved.skills,
            classCodes = saved.classes.ifEmpty { base.classCodes },
            // Local only (no backend field yet).
            displayName = update.displayName.trim().ifBlank { base.displayName },
            githubUsername = update.githubUsername.trim().ifBlank { null },
            avatarUrl = avatarUrl,
        )
    }

    override suspend fun uploadAvatar(bytes: ByteArray, mimeType: String): String {
        require(mimeType == "image/jpeg" || mimeType == "image/png") { "Unsupported image type $mimeType" }
        require(bytes.size <= MAX_AVATAR_BYTES) { "Image too large" }
        val url = avatarLock.withLock { "memory://avatars/${nextAvatarId++}" }
        LocalImageStore.put(url, bytes)
        return url
    }

    private companion object {
        const val MAX_BIO = 280
        const val MAX_AVATAR_BYTES = 5 * 1024 * 1024
    }
}

/** Backend user → [User]. Fields the backend doesn't return yet (score, earnings, GitHub) keep defaults. */
internal fun UserDto.toDomain(policy: AccountPolicy = AccountPolicy()): User {
    val kind = when {
        email != null -> policy.classify(email)
        role == "staff" || role == "admin" -> AccountKind.BauStaff
        else -> AccountKind.BauStudent
    }
    return User(
        id = id,
        displayName = name,
        email = email.orEmpty(),
        kind = kind,
        role = when (role) {
            "lead" -> Role.Lead
            "staff", "admin" -> Role.Staff
            else -> Role.Student
        },
        bio = bio.orEmpty(),
        skills = skills,
        classCodes = classes,
    )
}
