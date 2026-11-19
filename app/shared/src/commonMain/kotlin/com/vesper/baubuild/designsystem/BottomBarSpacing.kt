package com.vesper.baubuild.designsystem

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max

/*
 * ONE rule for the bottom of every mobile screen:
 *
 *   bottom padding = (distance from the window bottom to the top edge of the tab bar, or the keyboard if it is
 *                     higher) + BauSpacing.aboveTabBar
 *
 * Scrollable screens use it as their content padding (content still scrolls under the glass bar, but the last item
 * comes to rest 24dp above it). Pinned bottom actions sit on a Paper area whose bottom padding is this value, so the
 * button floats clearly above the bar. Never add another tab-bar constant or imePadding on top of it.
 *
 * Where the tab bar height comes from:
 * - iOS 26+ (native Liquid Glass TabView): UIKit puts the bar in the hosting controller's safe area → safeDrawing.
 * - iOS 16–25 (MaterialTabShell): ComposeHost(extraBottomInset = bar + padding) → additionalSafeAreaInsets → safeDrawing.
 * - Android (shell/AppShell.kt floating glass bar): the shell provides [LocalTabBarInset] (bar top above the window
 *   bottom, system nav inset included) and lets content extend under the bar.
 * The keyboard is part of safeDrawing on both platforms; the max() means an open keyboard replaces the tab-bar gap
 * instead of being added to it.
 */

/** Extra gap between the last content / a pinned action and the top edge of the tab bar (or keyboard). */
val BauSpacing.aboveTabBar: Dp get() = 24.dp

/**
 * Distance from the window bottom to the top edge of a floating tab bar drawn by a Compose shell (Android).
 * 0 when the bar is already part of the safe area (iOS native shells) or there is no bottom bar.
 */
val LocalTabBarInset = compositionLocalOf { 0.dp }

/** Bottom padding for screen content: clears the tab bar (or keyboard) plus [extra]. See the rule above. */
@Composable
fun bottomContentPadding(extra: Dp = BauSpacing.aboveTabBar): Dp {
    val density = LocalDensity.current
    val safeBottom = with(density) { WindowInsets.safeDrawing.getBottom(density).toDp() }
    return max(safeBottom, LocalTabBarInset.current) + extra
}

/** `padding(bottom = bottomContentPadding(extra))` — for pinned bottom action areas and non-lazy scroll columns. */
@Composable
fun Modifier.tabBarContentPadding(extra: Dp = BauSpacing.aboveTabBar): Modifier =
    padding(bottom = bottomContentPadding(extra))
