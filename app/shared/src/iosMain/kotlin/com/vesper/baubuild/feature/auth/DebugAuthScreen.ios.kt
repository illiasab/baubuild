@file:OptIn(ExperimentalNativeApi::class)

package com.vesper.baubuild.feature.auth

import platform.Foundation.NSProcessInfo
import kotlin.experimental.ExperimentalNativeApi

/**
 * iOS (Debug binaries only): launch argument `-authScreen login|signup|forgot`, e.g.
 * `xcrun simctl launch <udid> com.vesper.baubuild -authScreen login`. Visual only; ignored in release builds.
 */
internal actual fun debugInitialAuthScreenName(): String? {
    if (!kotlin.native.Platform.isDebugBinary) return null
    val args = NSProcessInfo.processInfo.arguments.map { it.toString() }
    val i = args.indexOf("-authScreen")
    return args.getOrNull(i + 1)?.takeIf { i >= 0 && it.isNotBlank() }
}
