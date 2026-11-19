package com.vesper.baubuild.core.session

internal actual fun devicePlatform(): String = platformForOsName(System.getProperty("os.name").orEmpty())

internal actual fun deviceAppVersion(): String =
    System.getProperty("bau.appVersion")?.takeIf { it.isNotBlank() }
        ?: System.getProperty("jpackage.app-version")?.takeIf { it.isNotBlank() } // set by the packaged launcher
        ?: DeviceInfo::class.java.`package`?.implementationVersion?.takeIf { it.isNotBlank() }
        ?: DEFAULT_DESKTOP_VERSION

/** Keep in sync with `packageVersion` in app/desktopApp/build.gradle.kts (used when running from Gradle). */
private const val DEFAULT_DESKTOP_VERSION = "1.0.0"
