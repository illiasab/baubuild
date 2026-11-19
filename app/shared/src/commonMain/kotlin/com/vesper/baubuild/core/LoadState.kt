package com.vesper.baubuild.core

/**
 * UI-facing state for anything loaded from the backend.
 *
 * Zero-downtime rule: once we have data we never go back to a blank/error screen. A failed refresh keeps
 * [Content] and sets [Content.stale] so the UI can show a quiet "offline / showing saved data" hint.
 */
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Content<T>(val data: T, val refreshing: Boolean = false, val stale: Boolean = false) : LoadState<T>
    data class Failed(val message: String) : LoadState<Nothing>
}

val <T> LoadState<T>.dataOrNull: T? get() = (this as? LoadState.Content<T>)?.data
