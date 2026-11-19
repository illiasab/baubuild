package com.vesper.baubuild.feature.search

import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.core.model.Difficulty
import com.vesper.baubuild.core.model.Membership
import com.vesper.baubuild.core.model.Project
import com.vesper.baubuild.data.fake.FakeData
import com.vesper.baubuild.data.repository.ProjectRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.*

private class ScriptedProjectRepository(var fail: Boolean = false) : ProjectRepository {
    var calls = 0
    override suspend fun openProjects(difficulty: Difficulty?): List<Project> {
        calls++
        if (fail) error("offline")
        return FakeData.projects
    }
    override suspend fun project(id: String) = FakeData.projects.first { it.id == id }
    override suspend fun projectsForClass(classCode: String) = emptyList<Project>()
    override suspend fun join(projectId: String): Membership = error("unused")
}

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun filterMatchesTitleOwnerAndClassCaseInsensitive() {
        val all = FakeData.projects
        assertEquals(listOf(FakeData.roomBooking), filterProjects(all, "library"))
        assertEquals(listOf(FakeData.attendanceCsv), filterProjects(all, "CSV"))
        assertEquals(listOf(FakeData.courseSearch), filterProjects(all, "cmps433"))
        assertEquals(listOf(FakeData.attendanceCsv), filterProjects(all, " student  export "))
        assertEquals(all, filterProjects(all, "   "))
        assertTrue(filterProjects(all, "zzz").isEmpty())
    }

    @Test fun queryIsDebouncedBeforeFiltering() = runTest {
        val vm = SearchViewModel(ScriptedProjectRepository())
        advanceUntilIdle()
        assertTrue(vm.state.value.projects is LoadState.Content)
        assertFalse(vm.state.value.isSearching)

        vm.onQueryChange("lib")
        runCurrent()
        assertEquals("lib", vm.state.value.query)
        assertEquals("", vm.state.value.activeQuery)
        assertTrue(vm.state.value.isTyping)

        vm.onQueryChange("library")
        advanceTimeBy(SearchViewModel.DEBOUNCE_MILLIS - 1)
        runCurrent()
        assertEquals("", vm.state.value.activeQuery)

        advanceTimeBy(2)
        runCurrent()
        assertEquals("library", vm.state.value.activeQuery)
        assertEquals(listOf(FakeData.roomBooking), vm.state.value.results)
        assertFalse(vm.state.value.noMatches)
    }

    @Test fun clearingAppliesImmediately() = runTest {
        val vm = SearchViewModel(ScriptedProjectRepository())
        vm.onQueryChange("csv")
        advanceUntilIdle()
        assertTrue(vm.state.value.isSearching)
        vm.clearQuery()
        runCurrent()
        assertFalse(vm.state.value.isSearching)
        assertEquals(FakeData.projects, vm.state.value.results)
    }

    @Test fun noMatchesState() = runTest {
        val vm = SearchViewModel(ScriptedProjectRepository())
        vm.onQueryChange("nothing-like-this")
        advanceUntilIdle()
        assertTrue(vm.state.value.noMatches)
    }

    @Test fun openingResultRecordsRecentSearchDeduplicated() = runTest {
        val vm = SearchViewModel(ScriptedProjectRepository())
        advanceUntilIdle()
        vm.onQueryChange("library"); advanceUntilIdle()
        vm.open(FakeData.roomBooking); advanceUntilIdle()
        assertEquals(FakeData.roomBooking, vm.state.value.selected)
        vm.dismissDetail()
        vm.onQueryChange("csv"); advanceUntilIdle()
        vm.open(FakeData.attendanceCsv)
        vm.onQueryChange("Library"); advanceUntilIdle()
        vm.open(FakeData.roomBooking); advanceUntilIdle()
        assertEquals(listOf("Library", "csv"), vm.state.value.recent)

        vm.clearRecent(); advanceUntilIdle()
        assertTrue(vm.state.value.recent.isEmpty())
    }

    @Test fun openingWithoutQueryDoesNotRecord() = runTest {
        val vm = SearchViewModel(ScriptedProjectRepository())
        advanceUntilIdle()
        vm.open(FakeData.shuttleWidget); advanceUntilIdle()
        assertTrue(vm.state.value.recent.isEmpty())
        assertEquals(FakeData.shuttleWidget, vm.state.value.selected)
    }

    @Test fun loadFailureThenRetry() = runTest {
        val repo = ScriptedProjectRepository(fail = true)
        val vm = SearchViewModel(repo)
        advanceUntilIdle()
        assertTrue(vm.state.value.projects is LoadState.Failed)
        repo.fail = false
        vm.refresh(); advanceUntilIdle()
        assertTrue(vm.state.value.projects is LoadState.Content)
        assertEquals(2, repo.calls)
    }
}
