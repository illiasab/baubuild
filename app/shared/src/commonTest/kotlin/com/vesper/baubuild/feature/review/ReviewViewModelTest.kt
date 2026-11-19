package com.vesper.baubuild.feature.review

import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.core.model.Membership
import com.vesper.baubuild.core.model.ReviewStatus
import com.vesper.baubuild.core.model.Workspace
import com.vesper.baubuild.data.fake.FakeData
import com.vesper.baubuild.data.repository.WorkRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.*

private class ScriptedWorkRepository(var reviews: List<ReviewStatus>, var fail: Boolean = false) : WorkRepository {
    var calls = 0
    override suspend fun myWork(): List<Membership> = emptyList()
    override suspend fun workspace(projectId: String): Workspace = error("unused")
    override suspend fun updateTaskStatus(projectId: String, taskId: String, status: com.vesper.baubuild.core.model.TaskStatus): com.vesper.baubuild.core.model.ProjectTask = error("unused")
    override suspend fun reviews(): List<ReviewStatus> {
        calls++
        if (fail) error("offline")
        return reviews
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val one = FakeData.reviews.first()
    private val two = one.copy(project = FakeData.shuttleWidget, headline = "With the dev lead.")

    @Test fun singleReviewOpensDetailDirectlyWithoutBack() = runTest {
        val vm = ReviewViewModel(ScriptedWorkRepository(listOf(one)))
        assertEquals(LoadState.Loading, vm.state.value.reviews)
        advanceUntilIdle()
        assertEquals(one, vm.state.value.detail)
        assertFalse(vm.state.value.canGoBack)
    }

    @Test fun multipleReviewsShowListThenDetailThenBack() = runTest {
        val vm = ReviewViewModel(ScriptedWorkRepository(listOf(one, two)))
        advanceUntilIdle()
        assertNull(vm.state.value.detail)
        vm.open(two.project.id)
        advanceUntilIdle()
        assertEquals(two, vm.state.value.detail)
        assertTrue(vm.state.value.canGoBack)
        vm.back()
        advanceUntilIdle()
        assertNull(vm.state.value.detail)
    }

    @Test fun emptyReviewsIsContentNotError() = runTest {
        val vm = ReviewViewModel(ScriptedWorkRepository(emptyList()))
        advanceUntilIdle()
        assertEquals(LoadState.Content(emptyList()), vm.state.value.reviews)
        assertNull(vm.state.value.detail)
    }

    @Test fun failedRefreshKeepsDataAndMarksStale() = runTest {
        val repo = ScriptedWorkRepository(listOf(one))
        val vm = ReviewViewModel(repo)
        advanceUntilIdle()
        repo.fail = true
        vm.refresh()
        advanceUntilIdle()
        val s = vm.state.value.reviews
        assertTrue(s is LoadState.Content && s.stale && s.data == listOf(one))
    }

    @Test fun firstLoadFailureIsFailedAndRetryRecovers() = runTest {
        val repo = ScriptedWorkRepository(listOf(one), fail = true)
        val vm = ReviewViewModel(repo)
        advanceUntilIdle()
        assertTrue(vm.state.value.reviews is LoadState.Failed)
        repo.fail = false
        vm.refresh()
        advanceUntilIdle()
        assertEquals(one, vm.state.value.detail)
    }
}
