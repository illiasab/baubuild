package com.vesper.baubuild.data.fake

import com.vesper.baubuild.core.media.LocalImageStore
import com.vesper.baubuild.core.model.User
import com.vesper.baubuild.data.repository.ProfileRepository
import com.vesper.baubuild.data.repository.ProfileUpdate
import kotlinx.coroutines.delay

/**
 * In-memory profile backend. Applies updates to the current session user (supplied by [currentUser]) and
 * keeps uploaded avatars in memory under fake `memory://` URLs, registered in [LocalImageStore] so
 * [com.vesper.baubuild.designsystem.UserAvatar] can render them.
 */
class FakeProfileRepository(private val currentUser: () -> User?) : ProfileRepository {
    private val avatars = mutableMapOf<String, ByteArray>()
    private var nextAvatarId = 1

    override suspend fun updateProfile(update: ProfileUpdate): User {
        delay(LATENCY_MS)
        val user = currentUser() ?: throw IllegalStateException("Not signed in")
        val avatarUrl = when {
            update.removeAvatar -> null
            update.avatarUrl != null -> update.avatarUrl
            else -> user.avatarUrl
        }
        return user.copy(
            displayName = update.displayName,
            bio = update.bio,
            skills = update.skills,
            githubUsername = update.githubUsername.ifBlank { null },
            avatarUrl = avatarUrl,
        )
    }

    override suspend fun uploadAvatar(bytes: ByteArray, mimeType: String): String {
        delay(LATENCY_MS)
        require(mimeType == "image/jpeg" || mimeType == "image/png") { "Unsupported image type $mimeType" }
        require(bytes.size <= MAX_AVATAR_BYTES) { "Image too large" }
        val url = "memory://avatars/${nextAvatarId++}"
        avatars[url] = bytes
        LocalImageStore.put(url, bytes)
        return url
    }

    private companion object {
        const val LATENCY_MS = 450L
        const val MAX_AVATAR_BYTES = 5 * 1024 * 1024
    }
}
