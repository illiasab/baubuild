package com.vesper.baubuild.core.notifications

import androidx.compose.runtime.Composable

/** Desktop shows notifications without a permission prompt. */
private object DesktopNotificationPermission : NotificationPermission {
    override suspend fun status() = NotificationPermissionStatus.Granted
    override suspend fun request() = true
    override fun openSystemSettings() = Unit
}

@Composable
actual fun rememberNotificationPermissionRequester(): NotificationPermission = DesktopNotificationPermission
