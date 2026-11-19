package com.vesper.baubuild.ios

import com.vesper.baubuild.feature.search.SearchQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/*
 * Two-way binding between the native tab-bar search field (SwiftUI `.searchable` on iOS 26+, the hand-built
 * material field on iOS 16–25) and Kotlin [SearchQuery.text], which [com.vesper.baubuild.feature.search.SearchRoot]
 * renders results for.
 *
 * Feedback loops: Swift only calls [setSearchQuery] when its text differs from [currentSearchQuery], and on every
 * [observeSearchQuery] callback it re-reads [currentSearchQuery] (not the possibly stale callback value) and only
 * updates its field when that differs. [SearchQuery.text] is a StateFlow, so equal writes never emit.
 */

/** Handle returned by [observeSearchQuery]; call [close] to stop receiving updates. */
interface SearchQueryObservation {
    fun close()
}

/** Calls [onChange] on the main thread with the current query immediately, then on every change. */
fun observeSearchQuery(onChange: (String) -> Unit): SearchQueryObservation {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    scope.launch { SearchQuery.text.collect { onChange(it) } }
    return object : SearchQueryObservation {
        override fun close() = scope.cancel()
    }
}

/** The query right now (synchronous). */
fun currentSearchQuery(): String = SearchQuery.text.value

/** Writes the field's text into [SearchQuery]. No-op when unchanged. */
fun setSearchQuery(text: String) {
    if (SearchQuery.text.value != text) SearchQuery.update(text)
}
