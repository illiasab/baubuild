package com.vesper.baubuild.core.notifications

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred

/**
 * `POST_NOTIFICATIONS` (API 33+) requested through the Compose activity-result registry of the hosting activity.
 * Below 33 there is no runtime permission; the app-level "notifications enabled" switch decides.
 */
@Composable
actual fun rememberNotificationPermissionRequester(): NotificationPermission {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        PendingRequest.complete(granted)
    }
    return remember(context, launcher) {
        AndroidNotificationPermission(context, launch = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }, PendingRequest)
    }
}

/**
 * The one in-flight request, process-wide: if the activity is recreated while the system dialog is up, the result
 * arrives at the new composition's launcher and still completes the caller that is awaiting it.
 */
private object PendingRequest {
    private var deferred: CompletableDeferred<Boolean>? = null

    fun start(): CompletableDeferred<Boolean> =
        deferred?.takeIf { it.isActive } ?: CompletableDeferred<Boolean>().also { deferred = it }

    fun complete(granted: Boolean) {
        deferred?.complete(granted)
        deferred = null
    }
}

private class AndroidNotificationPermission(
    private val context: Context,
    private val launch: () -> Unit,
    private val pending: PendingRequest,
) : NotificationPermission {
    private val prefs get() = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override suspend fun status(): NotificationPermissionStatus {
        if (Build.VERSION.SDK_INT < 33) {
            return if (appNotificationsEnabled()) NotificationPermissionStatus.Granted else NotificationPermissionStatus.Denied
        }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            return if (appNotificationsEnabled()) NotificationPermissionStatus.Granted else NotificationPermissionStatus.Denied
        }
        // Not granted: still askable if we never asked, or the system would show the dialog again (rationale).
        val asked = prefs.getBoolean(KEY_ASKED, false)
        val canAskAgain = context.findActivity()
            ?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.POST_NOTIFICATIONS) } ?: false
        return if (!asked || canAskAgain) NotificationPermissionStatus.NotDetermined else NotificationPermissionStatus.Denied
    }

    override suspend fun request(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return appNotificationsEnabled()
        if (status() == NotificationPermissionStatus.Granted) return true
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        val result = pending.start()
        launch()
        return result.await() && appNotificationsEnabled()
    }

    override fun openSystemSettings() {
        val intent = if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        }
        if (context.findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    private fun appNotificationsEnabled() = NotificationManagerCompat.from(context).areNotificationsEnabled()

    private companion object {
        const val PREFS = "bau_notification_permission"
        const val KEY_ASKED = "post_notifications_asked"
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
