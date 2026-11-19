package com.vesper.baubuild

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.vesper.baubuild.designsystem.BauTheme
import com.vesper.baubuild.feature.appstatus.AppStatusBanner
import com.vesper.baubuild.feature.appstatus.UpdateRequiredGate
import com.vesper.baubuild.shell.AppShell

/** Root for Android, desktop (macOS/Windows/Linux). iOS uses a native SwiftUI shell instead. */
@Composable
fun App() {
    BauTheme {
        // /v1/config: blocks only when this version is below the minimum; otherwise always opens (fail open).
        UpdateRequiredGate {
            Box(Modifier.fillMaxSize()) {
                AppShell()
                // Maintenance notice floats over the top edge (screens already pad their own safe area).
                AppStatusBanner(
                    Modifier.align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
                )
            }
        }
    }
}
