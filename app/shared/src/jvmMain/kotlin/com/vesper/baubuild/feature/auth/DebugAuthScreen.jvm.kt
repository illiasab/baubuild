package com.vesper.baubuild.feature.auth

/** Desktop: `-Dbau.authScreen=login|signup|forgot` (visual only; see [debugInitialAuthScreen]). */
internal actual fun debugInitialAuthScreenName(): String? =
    runCatching { System.getProperty("bau.authScreen") }.getOrNull()?.takeIf { it.isNotBlank() }
