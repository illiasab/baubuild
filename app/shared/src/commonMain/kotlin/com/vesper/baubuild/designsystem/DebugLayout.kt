package com.vesper.baubuild.designsystem

/**
 * Debug-only layout hooks for screenshot checks. Set only by DEBUG iOS builds (launch arg `-startScrolledToBottom`,
 * see iosApp/TabShell.swift `DebugLaunch`); always false in release and on other platforms.
 */
object DebugLayout {
    /** Tab roots with long content start scrolled to the end, to check the gap above the tab bar. */
    var startScrolledToBottom: Boolean = false
}
