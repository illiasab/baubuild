package com.vesper.baubuild.core.session

/**
 * What the app tells the backend about itself (`device` in `POST /v1/auth/register`, `login`) and what
 * `GET /v1/config`'s `min_supported` is keyed by.
 */
data class DeviceInfo(
    /** One of `ios`, `macos`, `android`, `windows`, `linux`. */
    val platform: String,
    /** Marketing version, e.g. `1.0.0`. */
    val appVersion: String,
) {
    companion object {
        val current: DeviceInfo by lazy { DeviceInfo(devicePlatform(), deviceAppVersion()) }
    }
}

/** `ios` / `android` / desktop: `macos`, `windows` or `linux` from `os.name`. */
internal expect fun devicePlatform(): String

/** iOS `CFBundleShortVersionString`, Android `versionName`, desktop the jpackage version (`-Dbau.appVersion` overrides). */
internal expect fun deviceAppVersion(): String

/** Maps a JVM `os.name` to the backend's platform value. */
internal fun platformForOsName(osName: String): String {
    val name = osName.lowercase()
    return when {
        "mac" in name || "darwin" in name -> "macos"
        "win" in name -> "windows"
        else -> "linux"
    }
}
