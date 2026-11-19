package com.vesper.baubuild.feature.projects

import com.vesper.baubuild.core.model.MembershipStatus
import com.vesper.baubuild.data.fake.FakeData
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JoinGitHubPromptTest : MainDispatcherTest() {
    private val withRepo = FakeData.roomBooking // bau-build/library-booking
    private val noRepo = FakeData.roomBooking.copy(repoName = null, repoFullName = null)

    @Test fun promptOnlyWhenInWithRepoAndNotConnected() {
        assertEquals("bau-build/library-booking", gitHubPromptRepoAfterJoin(MembershipStatus.In, withRepo, gitHubConnected = false))
        assertNull(gitHubPromptRepoAfterJoin(MembershipStatus.In, withRepo, gitHubConnected = true))
        assertNull(gitHubPromptRepoAfterJoin(MembershipStatus.WaitingForApproval, withRepo, gitHubConnected = false))
        assertNull(gitHubPromptRepoAfterJoin(MembershipStatus.In, noRepo, gitHubConnected = false))
        assertNull(gitHubPromptRepoAfterJoin(MembershipStatus.In, withRepo.copy(repoFullName = " "), gitHubConnected = false))
        assertNull(gitHubPromptRepoAfterJoin(MembershipStatus.In, null, gitHubConnected = false))
    }

    @Test fun joinInWithoutGitHubCarriesRepo() = test {
        val vm = ProjectDetailViewModel(withRepo.id, TestProjectRepository(), signedIn(), isGitHubConnected = { false })
        advanceUntilIdle()
        vm.join()
        advanceUntilIdle()
        assertEquals(JoinState.Joined(MembershipStatus.In, "bau-build/library-booking"), vm.state.value.join)
    }

    @Test fun joinInWithGitHubConnectedHasNoPrompt() = test {
        val vm = ProjectDetailViewModel(withRepo.id, TestProjectRepository(), signedIn(), isGitHubConnected = { true })
        advanceUntilIdle()
        vm.join()
        advanceUntilIdle()
        assertEquals(JoinState.Joined(MembershipStatus.In, null), vm.state.value.join)
    }

    @Test fun waitingForApprovalHasNoPrompt() = test {
        val vm = ProjectDetailViewModel(
            FakeData.courseSearch.id, TestProjectRepository(), signedIn(FakeData.sam.copy(score = 320)),
            isGitHubConnected = { false },
        )
        advanceUntilIdle()
        vm.join()
        advanceUntilIdle()
        assertEquals(JoinState.Joined(MembershipStatus.WaitingForApproval, null), vm.state.value.join)
    }

    @Test fun defaultNeverPrompts() = test {
        val vm = ProjectDetailViewModel(withRepo.id, TestProjectRepository(), signedIn())
        advanceUntilIdle()
        vm.join()
        advanceUntilIdle()
        assertEquals(JoinState.Joined(MembershipStatus.In), vm.state.value.join)
    }
}
