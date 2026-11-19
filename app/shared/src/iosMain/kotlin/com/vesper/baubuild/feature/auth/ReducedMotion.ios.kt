package com.vesper.baubuild.feature.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

/** Settings → Accessibility → Motion → Reduce Motion. */
@Composable
actual fun rememberPrefersReducedMotion(): Boolean = remember { UIAccessibilityIsReduceMotionEnabled() }
