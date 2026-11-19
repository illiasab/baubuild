package com.vesper.baubuild.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Standard refresh for a ViewModel's `MutableStateFlow<LoadState<T>>`:
 * keeps existing content visible while refreshing and marks it stale on failure (never blanks the screen).
 */
suspend fun <T> MutableStateFlow<LoadState<T>>.load(fetch: suspend () -> T) {
    val previous = value.dataOrNull
    value = if (previous != null) LoadState.Content(previous, refreshing = true) else LoadState.Loading
    value = try {
        LoadState.Content(fetch())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        if (previous != null) LoadState.Content(previous, stale = true)
        else LoadState.Failed("Couldn’t load right now. Check your connection and try again.")
    }
}
