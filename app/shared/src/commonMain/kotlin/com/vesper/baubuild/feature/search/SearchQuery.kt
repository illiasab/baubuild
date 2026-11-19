package com.vesper.baubuild.feature.search

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Single source of truth for the search text.
 *
 * The search field lives in the app shell's tab bar (Apple Music style: tapping Search collapses the tabs into one
 * button on the left and the field takes over the bar) — natively via `.searchable` on iOS 26+, drawn by the shell
 * on iOS 16–25 and in the Compose shell on Android/desktop. Shells write the typed text into [text] and observe it
 * (e.g. tapping a recent search inside [SearchRoot] writes here, and the bar's field must show it).
 * [SearchRoot] only renders results for it.
 */
object SearchQuery {
    val text = MutableStateFlow("")

    fun update(value: String) { text.value = value }
    fun clear() { text.value = "" }
}
