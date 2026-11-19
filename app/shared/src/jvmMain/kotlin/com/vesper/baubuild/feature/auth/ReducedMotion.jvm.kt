package com.vesper.baubuild.feature.auth

import androidx.compose.runtime.Composable

/** No portable reduced-motion setting on the JVM desktop; animations stay on. */
@Composable
actual fun rememberPrefersReducedMotion(): Boolean = false
