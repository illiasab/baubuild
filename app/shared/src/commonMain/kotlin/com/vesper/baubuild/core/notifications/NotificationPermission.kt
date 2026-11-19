package com.vesper.baubuild.core.notifications

import androidx.compose.runtime.Composable

/** OS-level permission to show notifications for BAU BUILD. */
enum class NotificationPermissionStatus { Granted, Denied, NotDetermined }

/**
 * Platform notification permission:
 * - iOS: `UNUserNotificationCenter` (alert, badge, sound). No Info.plist key is needed.
 * - Android: `POST_NOTIFICATIONS` runtime permission on API 33+ (declared in the app manifest); below 33 it is
 *   granted at install, but the user can still turn notifications off in Settings (reported as [Denied]).
 * - Desktop: always granted.
 */
interface NotificationPermission {
    /** Current status, read fresh from the OS (the user may have changed it in Settings). */
    suspend fun status(): NotificationPermissionStatus

    /** Shows the system prompt when the OS still allows it. Returns true when notifications are allowed afterwards. */
    suspend fun request(): Boolean

    /** Opens this app's notification settings in the system Settings app. */
    fun openSystemSettings()
}

/**
 * The platform [NotificationPermission] for the current screen. Composable because Android requests the
 * permission through the hosting activity's result registry.
 */
@Composable
expect fun rememberNotificationPermissionRequester(): NotificationPermission
