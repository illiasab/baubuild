package com.vesper.baubuild.core.auth

import android.content.Context

/**
 * Android needs a [Context] for secure token storage, device info and local flags.
 * Call [init] from `MainActivity.onCreate` (or `Application.onCreate`) before touching `AppGraph`.
 */
object AuthPlatform {
    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
    }

    internal val context: Context
        get() = appContext ?: error("Call AuthPlatform.init(context) in MainActivity.onCreate before using AppGraph.")
}
