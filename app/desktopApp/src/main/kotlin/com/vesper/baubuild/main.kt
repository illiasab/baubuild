package com.vesper.baubuild

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension

fun main() = application {
    val state = rememberWindowState(
        size = DpSize(1180.dp, 800.dp),
        position = WindowPosition.Aligned(Alignment.Center),
    )
    Window(
        onCloseRequest = ::exitApplication,
        title = "BAU BUILD",
        state = state,
    ) {
        // Narrow windows get the floating bottom tab bar, wide ones (≥ 840dp) the side rail.
        LaunchedEffect(Unit) { window.minimumSize = Dimension(380, 600) }
        App()
    }
}
