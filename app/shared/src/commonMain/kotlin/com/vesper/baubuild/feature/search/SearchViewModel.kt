package com.vesper.baubuild.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.core.dataOrNull
import com.vesper.baubuild.core.load
import com.vesper.baubuild.core.model.Project
import com.vesper.baubuild.data.repository.ProjectRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SearchUiState(
    /** What is in the text field (updates on every keystroke). */
    val query: String = "",
    /** The debounced, trimmed query that [results] are filtered by. */
    val activeQuery: String = "",
    val projects: LoadState<List<Project>> = LoadState.Loading,
    val results: List<Project> = emptyList(),
    val recent: List<String> = emptyList(),
    /** Project shown in the feature-local detail sheet. */
    val selected: Project? = null,
) {
    val isSearching: Boolean get() = activeQuery.isNotEmpty()
    /** Typed but not yet applied (debounce pending). */
    val isTyping: Boolean get() = query.trim() != activeQuery
    val noMatches: Boolean get() = isSearching && !isTyping && projects is LoadState.Content && results.isEmpty()
}

/** Client-side match on title, owner and class code (case-insensitive, every word must match). */
fun filterProjects(projects: List<Project>, query: String): List<Project> {
    val words = query.trim().split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return projects
    return projects.filter { p ->
        val haystack = listOfNotNull(p.title, p.owner, p.classCode).joinToString(" ")
        words.all { haystack.contains(it, ignoreCase = true) }
    }
}

@OptIn(FlowPreview::class)
class SearchViewModel(
    private val repository: ProjectRepository,
    debounceMillis: Long = DEBOUNCE_MILLIS,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val projects = MutableStateFlow<LoadState<List<Project>>>(LoadState.Loading)
    private val recent = MutableStateFlow<List<String>>(emptyList())
    private val selected = MutableStateFlow<Project?>(null)
    private var refreshJob: Job? = null

    /** Clearing the field applies immediately; typing waits [debounceMillis]. */
    private val activeQuery: Flow<String> = query
        .debounce { if (it.isBlank()) 0L else debounceMillis }
        .map { it.trim() }
        .distinctUntilChanged()
        .onStart { emit("") }

    val state: StateFlow<SearchUiState> = combine(query, activeQuery, projects, recent, selected) { q, active, p, r, s ->
        SearchUiState(
            query = q,
            activeQuery = active,
            projects = p,
            results = filterProjects(p.dataOrNull.orEmpty(), active),
            recent = r,
            selected = s,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SearchUiState())

    init { refresh() }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch { projects.load { repository.openProjects() } }
    }

    fun onQueryChange(value: String) { query.value = value }

    fun clearQuery() { query.value = "" }

    fun useRecent(value: String) { query.value = value }

    fun clearRecent() { recent.value = emptyList() }

    fun open(project: Project) {
        val q = query.value.trim()
        if (q.isNotEmpty()) recent.value = (listOf(q) + recent.value.filterNot { it.equals(q, ignoreCase = true) }).take(MAX_RECENT)
        selected.value = project
    }

    fun dismissDetail() { selected.value = null }

    companion object {
        const val DEBOUNCE_MILLIS = 300L
        const val MAX_RECENT = 5
    }
}
