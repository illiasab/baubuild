package com.vesper.baubuild.feature.profile

import com.vesper.baubuild.core.model.Role
import com.vesper.baubuild.core.model.ScoreTier
import com.vesper.baubuild.core.session.InMemoryTokenStore
import com.vesper.baubuild.core.session.SessionState
import com.vesper.baubuild.core.session.SessionStore
import com.vesper.baubuild.data.fake.FakeData
import com.vesper.baubuild.feature.profile.lead.BecomeLeadViewModel
import com.vesper.baubuild.core.model.ReviewStageState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun showsSignedInUserWithTier() = runTest {
        val session = SessionStore(InMemoryTokenStore()).apply { signedIn("t", FakeData.sam) }
        val vm = ProfileViewModel(session)
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(FakeData.sam, s.user)
        assertEquals(180, s.score)
        assertEquals(ScoreTier.Builder, s.tier)
        assertTrue(s.canBecomeLead)
    }

    @Test fun leadsDoNotSeeBecomeALead() = runTest {
        val session = SessionStore(InMemoryTokenStore()).apply { signedIn("t", FakeData.sam.copy(role = Role.Lead)) }
        val vm = ProfileViewModel(session)
        advanceUntilIdle()
        assertFalse(vm.state.value.canBecomeLead)
    }

    @Test fun scoreIsClampedToGaugeRange() = runTest {
        val session = SessionStore(InMemoryTokenStore()).apply { signedIn("t", FakeData.sam.copy(score = 9000)) }
        val vm = ProfileViewModel(session)
        advanceUntilIdle()
        assertEquals(ScoreTier.MAX_SCORE, vm.state.value.score)
        assertEquals(ScoreTier.Honored, vm.state.value.tier)
    }

    @Test fun followsSessionUpdates() = runTest {
        val session = SessionStore(InMemoryTokenStore()).apply { signedIn("t", FakeData.sam) }
        val vm = ProfileViewModel(session)
        advanceUntilIdle()
        session.updateUser(FakeData.sam.copy(score = 320))
        advanceUntilIdle()
        assertEquals(ScoreTier.Expert, vm.state.value.tier)
    }

    @Test fun becomeLeadNavigation() = runTest {
        val session = SessionStore(InMemoryTokenStore()).apply { signedIn("t", FakeData.sam) }
        val vm = ProfileViewModel(session)
        vm.openBecomeLead()
        advanceUntilIdle()
        assertTrue(vm.state.value.showingLead)
        vm.closeBecomeLead()
        advanceUntilIdle()
        assertFalse(vm.state.value.showingLead)
    }

    @Test fun signOutClearsSession() = runTest {
        val session = SessionStore(InMemoryTokenStore()).apply { signedIn("t", FakeData.sam) }
        val vm = ProfileViewModel(session)
        vm.openBecomeLead()
        vm.signOut()
        advanceUntilIdle()
        assertEquals(SessionState.SignedOut, session.state.value)
        assertNull(vm.state.value.user)
        assertFalse(vm.state.value.signingOut)
        assertFalse(vm.state.value.showingLead)
    }

    @Test fun leadStepperStartsAtExamAndCtaShowsNotice() = runTest {
        val vm = BecomeLeadViewModel()
        val stages = vm.state.value.stages
        assertEquals(listOf("Proctored Exam", "In-Person Stage", "Lead Status"), stages.map { it.title })
        assertEquals(ReviewStageState.Active, stages.first().state)
        assertNull(vm.state.value.notice)
        vm.startExam()
        assertNotNull(vm.state.value.notice)
    }
}
