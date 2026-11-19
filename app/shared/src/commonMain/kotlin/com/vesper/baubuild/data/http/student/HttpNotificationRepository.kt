package com.vesper.baubuild.data.http.student

import com.vesper.baubuild.core.network.apiCall
import com.vesper.baubuild.data.http.student.dto.MarkReadDto
import com.vesper.baubuild.data.http.student.dto.NotificationDto
import com.vesper.baubuild.data.http.student.dto.UnreadDto
import com.vesper.baubuild.data.http.student.dto.Wire
import com.vesper.baubuild.data.repository.AppNotification
import com.vesper.baubuild.data.repository.NotificationRepository
import com.vesper.baubuild.data.repository.NotificationType
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/**
 * [NotificationRepository] on the BAU BUILD backend.
 *
 * - `GET /v1/notifications?cursor=` (newest first, all pages up to a cap) → `{id, text, read, created_at}`.
 *   The backend has a single `text` and no type, so `text` becomes [AppNotification.title] and the type is
 *   inferred from the wording (falls back to [NotificationType.General]).
 * - `POST /v1/notifications/read` `{"ids": [id]}` (or `{"all": true}`) → `{"unread": n}`.
 * - `GET /v1/notifications/unread-count` → `{"unread": n}` ([unreadCount], not on the interface yet).
 */
class HttpNotificationRepository(private val client: HttpClient) : NotificationRepository {

    override suspend fun notifications(): List<AppNotification> =
        client.getAllPages<NotificationDto>("v1/notifications")
            .map { it.toDomain() }
            .sortedByDescending { it.createdAtEpochMs }

    override suspend fun markRead(id: String) {
        markRead(MarkReadDto(ids = listOf(id)))
    }

    /** Marks everything read; returns the new unread count (0). */
    suspend fun markAllRead(): Int = markRead(MarkReadDto(all = true))

    /** Badge count. */
    suspend fun unreadCount(): Int = apiCall { client.get("v1/notifications/unread-count").body<UnreadDto>() }.unread

    private suspend fun markRead(body: MarkReadDto): Int = apiCall {
        client.post("v1/notifications/read") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body<UnreadDto>()
    }.unread
}

internal fun NotificationDto.toDomain() = AppNotification(
    id = id,
    type = inferType(text),
    title = text,
    createdAtEpochMs = Wire.instant(createdAt)?.toEpochMilliseconds() ?: 0L,
    read = read,
)

/** Best-effort type from the backend's wording, only to pick an icon. */
private fun inferType(text: String): NotificationType {
    val t = text.lowercase()
    return when {
        t.startsWith("you’re in") || t.startsWith("you're in") || "approved you" in t -> NotificationType.JoinApproved
        "review" in t || "dev lead" in t || "sent the branch back" in t || "was approved" in t -> NotificationType.ReviewUpdate
        "urgent" in t -> NotificationType.UrgentInquiry
        else -> NotificationType.General
    }
}
