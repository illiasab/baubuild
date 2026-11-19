@file:OptIn(ExperimentalNativeApi::class)

package com.vesper.baubuild.core.network

import platform.Foundation.NSProcessInfo
import kotlin.experimental.ExperimentalNativeApi

/**
 * iOS (Debug builds only): launch argument `-apiBaseUrl <url>` (Xcode → Edit Scheme → Run → Arguments Passed On
 * Launch, or `xcrun simctl launch … -apiBaseUrl http://127.0.0.1:8000/`). Ignored in release binaries.
 */
internal actual fun debugApiBaseUrlOverride(): String? {
    if (!kotlin.native.Platform.isDebugBinary) return null
    val args = NSProcessInfo.processInfo.arguments.map { it.toString() }
    val i = args.indexOf("-apiBaseUrl")
    return args.getOrNull(i + 1)?.takeIf { i >= 0 && it.isNotBlank() }
}
