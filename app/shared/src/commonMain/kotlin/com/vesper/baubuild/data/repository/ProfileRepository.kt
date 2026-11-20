package com.vesper.baubuild.data.repository

import com.vesper.baubuild.core.model.User
import kotlinx.serialization.Serializable

/**
 * Body of `PATCH /v1/me`. The client always sends every editable field (classes are read-only: they come
 * from BAU). `AppJson` omits nulls and defaults, so:
 * - [githubUsername] `""` → disconnect GitHub;
 * - [avatarUrl] omitted → keep the current photo; set → a URL returned by [ProfileRepository.uploadAvatar];
 * - [removeAvatar] `true` → delete the current photo.
 */
@Serializable
data class ProfileUpdate(
    val displayName: String,
    val bio: String,
    val skills: List<String>,
    val githubUsername: String,
    val avatarUrl: String? = null,
    val removeAvatar: Boolean = false,
    /** Staff only (`PATCH /v1/me` `title`); null = not sent. `""` clears it. */
    val title: String? = null,
    /** Null = not sent. `""` clears it. */
    val department: String? = null,
)

/**
 * Backend contract for editing the signed-in user's profile (Go):
 *
 * `PATCH /v1/me` (Bearer) [ProfileUpdate] JSON → 200 updated `User`. Validation failures → 422
 * `{"error": "invalid_field", "field": "displayName" | "bio" | "skills" | "githubUsername"}`.
 * Server-side limits mirror the client: display name 2..50 chars, bio ≤ 280, ≤ 10 skills of ≤ 30 chars,
 * GitHub username 1..39 chars `[A-Za-z0-9-]`.
 *
 * `PUT /v1/me/avatar` (Bearer) `multipart/form-data` with one part `file` (`image/jpeg` or `image/png`,
 * ≤ 5 MB; the client uploads a ≤ 1024px JPEG) → 200 `{"url": "https://…"}`. The URL is not applied until it
 * is sent as [ProfileUpdate.avatarUrl], so a cancelled edit leaves the profile unchanged.
 *
 * Both are idempotent (safe to retry). Failures throw; the caller keeps the form and shows an inline error.
 */
interface ProfileRepository {
    suspend fun updateProfile(update: ProfileUpdate): User

    /** Returns the hosted URL of the uploaded image. */
    suspend fun uploadAvatar(bytes: ByteArray, mimeType: String): String
}
