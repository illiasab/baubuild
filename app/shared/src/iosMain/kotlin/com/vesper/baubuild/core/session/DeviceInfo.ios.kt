package com.vesper.baubuild.core.session

import platform.Foundation.NSBundle

internal actual fun devicePlatform(): String = "ios"

internal actual fun deviceAppVersion(): String =
    (NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String)?.takeIf { it.isNotBlank() } ?: "1.0.0"
