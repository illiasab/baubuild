package com.vesper.baubuild.data.repository

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * What a notification is about; drives its icon. Wire values are snake_case strings. Unknown values from a newer
 * backend decode as [General] instead of failing the whole list.
 */
@Serializable(with = NotificationTypeSerializer::class)
enum class NotificationType(val wire: String) {
    /** A project owner approved the user's join request. */
    JoinApproved("join_approved"),
    /** A submission's review moved (AI check done, dev lead approved / asked for changes). */
    ReviewUpdate("review_update"),
    /** BAU staff posted an urgent inquiry. */
    UrgentInquiry("urgent_inquiry"),
    General("general"),
    ;

    companion object {
        fun fromWire(value: String): NotificationType = entries.firstOrNull { it.wire == value } ?: General
    }
}

internal object NotificationTypeSerializer : KSerializer<NotificationType> {
    override val descriptor = PrimitiveSerialDescriptor("NotificationType", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: NotificationType) = encoder.encodeString(value.wire)
    override fun deserialize(decoder: Decoder): NotificationType = NotificationType.fromWire(decoder.decodeString())
}

/** One entry in the user's notification inbox. */
@Serializable
data class AppNotification(
    val id: String,
    val type: NotificationType = NotificationType.General,
    val title: String,
    val body: String = "",
    /** Creation time, epoch milliseconds (UTC). */
    @SerialName("created_at") val createdAtEpochMs: Long,
    val read: Boolean = false,
    /** Optional project the notification is about (for a future deep link). */
    @SerialName("project_id") val projectId: String? = null,
)

/**
 * Contract with the backend for the signed-in user's notifications.
 *
 * Expected endpoints: `GET /notifications` (newest first) and `POST /notifications/{id}/read` (idempotent).
 */
interface NotificationRepository {
    /** The user's notifications, newest first. Empty for a brand-new account. */
    suspend fun notifications(): List<AppNotification>

    /** Marks one notification read. Idempotent: marking an already-read or unknown id is not an error. */
    suspend fun markRead(id: String)
}
