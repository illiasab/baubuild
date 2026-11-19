package com.vesper.baubuild

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.vesper.baubuild.shell.portal.PortalKeyboard
import java.awt.Dimension

/** Window / taskbar icon (packaged installers use app/desktopApp/icons via nativeDistributions). */
private val appIcon = Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")
    ?.use { BitmapPainter(loadImageBitmap(it)) }

private val isMac = System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

fun main() = application {
    PortalKeyboard.modifierLabel = if (isMac) "⌘" else "Ctrl"
    val state = rememberWindowState(
        size = DpSize(1280.dp, 820.dp),
        position = WindowPosition.Aligned(Alignment.Center),
    )
    Window(
        onCloseRequest = ::exitApplication,
        title = "BAU BUILD",
        state = state,
        icon = appIcon,
        // Portal shortcuts (Ctrl/⌘+1..4, Ctrl/⌘+K, Esc) work regardless of what has focus.
        onPreviewKeyEvent = PortalKeyboard::onPreviewKeyEvent,
        onKeyEvent = PortalKeyboard::onKeyEvent,
    ) {
        // Desktop uses the Web portal layout: 260dp sidebar + page content, so keep the window wide enough for both.
        LaunchedEffect(Unit) { window.minimumSize = Dimension(960, 640) }
        App()
    }
}
