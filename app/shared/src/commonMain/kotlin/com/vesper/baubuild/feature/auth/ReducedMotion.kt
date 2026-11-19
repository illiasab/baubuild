package com.vesper.baubuild.feature.auth

import androidx.compose.runtime.Composable

/** True when the OS asks apps to minimise motion (iOS Reduce Motion, Android "Remove animations"). */
@Composable
expect fun rememberPrefersReducedMotion(): Boolean
