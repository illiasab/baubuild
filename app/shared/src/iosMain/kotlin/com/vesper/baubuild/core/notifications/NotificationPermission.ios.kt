package com.vesper.baubuild.core.notifications

import androidx.compose.runtime.Composable
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

/**
 * `UNUserNotificationCenter` authorization (alert, badge, sound). Covers local and remote alerts; registering for
 * remote push (APNs entitlement + `registerForRemoteNotifications`) is a separate step not done here.
 */
private object IosNotificationPermission : NotificationPermission {
    private val center get() = UNUserNotificationCenter.currentNotificationCenter()

    override suspend fun status(): NotificationPermissionStatus = suspendCancellableCoroutine { cont ->
        center.getNotificationSettingsWithCompletionHandler { settings ->
            val status = when (settings?.authorizationStatus) {
                UNAuthorizationStatusAuthorized, UNAuthorizationStatusProvisional, UNAuthorizationStatusEphemeral ->
                    NotificationPermissionStatus.Granted
                UNAuthorizationStatusDenied -> NotificationPermissionStatus.Denied
                UNAuthorizationStatusNotDetermined -> NotificationPermissionStatus.NotDetermined
                else -> NotificationPermissionStatus.NotDetermined
            }
            if (cont.isActive) cont.resume(status)
        }
    }

    override suspend fun request(): Boolean = suspendCancellableCoroutine { cont ->
        center.requestAuthorizationWithOptions(
            UNAuthorizationOptionAlert or UNAuthorizationOptionBadge or UNAuthorizationOptionSound,
        ) { granted, _ ->
            if (cont.isActive) cont.resume(granted)
        }
    }

    override fun openSystemSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any?>(), completionHandler = null)
    }
}

@Composable
actual fun rememberNotificationPermissionRequester(): NotificationPermission = IosNotificationPermission
