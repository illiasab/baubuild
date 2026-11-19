package com.vesper.baubuild.shell.portal

import androidx.compose.ui.input.key.KeyEvent

/**
 * Window-level keyboard hook for the desktop portal. The desktop `Window` forwards its key events here so the
 * shortcuts (Ctrl/⌘+1..4 sections, Ctrl/⌘+K search, Esc leaves search) work wherever focus is — or when nothing
 * in the Compose tree is focused at all. [PortalShell] installs the handlers while it is on screen.
 */
object PortalKeyboard {
    /** Label for the shortcut modifier shown in the UI ("⌘" on macOS, "Ctrl" elsewhere). Set by the desktop app. */
    var modifierLabel: String = "Ctrl"

    internal var previewHandler: ((KeyEvent) -> Boolean)? = null
    internal var fallbackHandler: ((KeyEvent) -> Boolean)? = null

    /** Pass to `Window(onPreviewKeyEvent = …)`: section/search shortcuts, before the focused element sees them. */
    fun onPreviewKeyEvent(event: KeyEvent): Boolean = previewHandler?.invoke(event) ?: false

    /** Pass to `Window(onKeyEvent = …)`: Esc when nothing inside (e.g. a sheet) consumed it first. */
    fun onKeyEvent(event: KeyEvent): Boolean = fallbackHandler?.invoke(event) ?: false
}
