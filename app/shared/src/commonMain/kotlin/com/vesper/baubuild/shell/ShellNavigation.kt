package com.vesper.baubuild.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue

/**
 * Which tab the Compose shell shows, plus the primary tab to return to when leaving search (Apple Music style:
 * the tab pill collapses into one button showing [returnTab]; tapping it — or system back — exits search).
 *
 * The search text itself lives in `SearchQuery.text` and is deliberately kept when leaving search.
 */
@Stable
internal class ShellNavigation(initial: AppTab = AppTab.Projects, returnTab: AppTab = AppTab.Projects) {
    var selected by mutableStateOf(initial)
        private set

    /** Last selected primary (non-search) tab. */
    var returnTab by mutableStateOf(if (initial != AppTab.Search) initial else returnTab.primaryOr(AppTab.Projects))
        private set

    val isSearching: Boolean get() = selected == AppTab.Search

    fun select(tab: AppTab) {
        if (tab != AppTab.Search) returnTab = tab
        selected = tab
    }

    fun enterSearch() = select(AppTab.Search)

    /** Back to [returnTab]; returns false when not searching (nothing to do). */
    fun exitSearch(): Boolean {
        if (!isSearching) return false
        selected = returnTab
        return true
    }

    companion object {
        val Saver: Saver<ShellNavigation, Any> = listSaver(
            save = { listOf(it.selected.name, it.returnTab.name) },
            restore = { ShellNavigation(AppTab.valueOf(it[0]), AppTab.valueOf(it[1])) },
        )

        private fun AppTab.primaryOr(fallback: AppTab) = if (this == AppTab.Search) fallback else this
    }
}
