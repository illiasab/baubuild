package com.vesper.baubuild

import androidx.compose.ui.window.ComposeUIViewController

/**
 * Full Compose app (Compose shell incl. tab bar). Not used by the shipping iOS app, which uses the native
 * SwiftUI shell + per-tab controllers in [com.vesper.baubuild.ios]; kept for debugging the Compose shell on iOS.
 */
fun MainViewController() = ComposeUIViewController { App() }
