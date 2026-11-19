package com.vesper.baubuild.data.fake

import com.vesper.baubuild.data.repository.AppNotification
import com.vesper.baubuild.data.repository.NotificationRepository
import com.vesper.baubuild.data.repository.NotificationType
import kotlinx.coroutines.delay

/**
 * In-memory notifications. Starts empty: the demo user is a brand-new student with nothing to be told yet.
 * Pass [SampleNotifications.all] to see the non-empty list UI.
 */
class FakeNotificationRepository(
    initial: List<AppNotification> = emptyList(),
) : NotificationRepository {
    private var items = initial

    override suspend fun notifications(): List<AppNotification> {
        delay(350)
        return items.sortedByDescending { it.createdAtEpochMs }
    }

    override suspend fun markRead(id: String) {
        delay(150)
        items = items.map { if (it.id == id) it.copy(read = true) else it }
    }
}

/** Sample inbox for testing the non-empty UI, e.g. `FakeNotificationRepository(SampleNotifications.all(now))`. */
object SampleNotifications {
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    fun all(now: Long): List<AppNotification> = listOf(
        AppNotification(
            id = "n_join", type = NotificationType.JoinApproved,
            title = "You’re in", body = "Library approved you for “Fix form validation on the room-booking page”.",
            createdAtEpochMs = now - 4 * MINUTE, projectId = "p_room_booking",
        ),
        AppNotification(
            id = "n_review", type = NotificationType.ReviewUpdate,
            title = "Review update", body = "The AI check passed. A dev lead will review your submission next.",
            createdAtEpochMs = now - 3 * HOUR,
        ),
        AppNotification(
            id = "n_urgent", type = NotificationType.UrgentInquiry,
            title = "Urgent request from BAU staff", body = "Registrar needs help with a course-catalog export.",
            createdAtEpochMs = now - 2 * DAY, read = true,
        ),
        AppNotification(
            id = "n_welcome", type = NotificationType.General,
            title = "Welcome to BAU BUILD", body = "Join an Easy project to earn your first score.",
            createdAtEpochMs = now - 9 * DAY, read = true,
        ),
    )
}
