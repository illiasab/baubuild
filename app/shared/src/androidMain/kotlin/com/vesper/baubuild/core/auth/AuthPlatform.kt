package com.vesper.baubuild.core.auth

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * Android needs a [Context] for secure token storage and to launch the sign-in browser.
 * Call [init] from `MainActivity.onCreate` (or `Application.onCreate`) before touching `AppGraph`.
 */
object AuthPlatform {
    @Volatile private var appContext: Context? = null
    private var resumed: WeakReference<Activity>? = null
    internal val listeners = mutableListOf<(Activity, resumed: Boolean) -> Unit>()

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext as Application
        appContext = app
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumed = WeakReference(activity)
                listeners.toList().forEach { it(activity, true) }
            }
            override fun onActivityPaused(activity: Activity) {
                if (resumed?.get() === activity) resumed = null
                listeners.toList().forEach { it(activity, false) }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    internal val context: Context
        get() = appContext ?: error("Call AuthPlatform.init(context) in MainActivity.onCreate before using AppGraph.")

    internal val resumedActivity: Activity? get() = resumed?.get()
}
