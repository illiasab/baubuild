package com.vesper.baubuild.core.session

import com.vesper.baubuild.core.auth.AuthPlatform

internal actual fun devicePlatform(): String = "android"

internal actual fun deviceAppVersion(): String = runCatching {
    val context = AuthPlatform.context
    @Suppress("DEPRECATION")
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull()?.takeIf { it.isNotBlank() } ?: "1.0"
