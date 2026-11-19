package com.vesper.baubuild

import androidx.compose.runtime.Composable
import com.vesper.baubuild.designsystem.BauTheme
import com.vesper.baubuild.shell.AppShell

/** Root for Android, desktop (macOS/Windows/Linux). iOS uses a native SwiftUI shell instead. */
@Composable
fun App() {
    BauTheme { AppShell() }
}
