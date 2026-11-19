package com.vesper.baubuild.feature.auth

import com.vesper.baubuild.core.model.AccountKind
import com.vesper.baubuild.core.model.AccountPolicy
import com.vesper.baubuild.core.session.InMemoryTokenStore
import com.vesper.baubuild.core.session.SessionStore
import com.vesper.baubuild.core.session.SignOutReason
import com.vesper.baubuild.data.fake.FakeData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest : ViewModelTest() {
    private val session = SessionStore(InMemoryTokenStore())
    private val policy = AccountPolicy()

    private fun vm(initial: AuthScreen? = null) = AuthViewModel(session, policy, initial)

    @Test
    fun startsOnWelcomeAndNavigatesWithoutGrowingTheStack() {
        val vm = vm()
        assertEquals(listOf(AuthScreen.Welcome), vm.state.value.backStack)
        vm.navigateTo(AuthScreen.Login)
        vm.navigateTo(AuthScreen.SignUp)
        vm.navigateTo(AuthScreen.Login) // pops back to the existing entry
        assertEquals(listOf(AuthScreen.Welcome, AuthScreen.Login), vm.state.value.backStack)
        assertTrue(vm.back())
        assertFalse(vm.back())
    }

    @Test
    fun openVerifyEmailPushesTheScreenWithAFreshRequest() {
        val vm = vm()
        vm.navigateTo(AuthScreen.SignUp)
        vm.openVerifyEmail(" sam@stu.bau.edu ", 30)
        val first = vm.state.value.verification!!
        assertEquals(AuthScreen.VerifyEmail, vm.state.value.screen)
        assertEquals("sam@stu.bau.edu", first.email)
        assertEquals(30L, first.resendAfterSeconds)
        vm.back()
        vm.openVerifyEmail("sam@stu.bau.edu", null)
        assertTrue(vm.state.value.verification!!.id != first.id)
    }

    @Test
    fun notAllowedBlocksAndUseAnotherAccountGoesToLogIn() {
        val vm = vm()
        vm.showNotAllowed("alex@gmail.com")
        assertEquals(AuthStatus.BlockedDomain("alex@gmail.com", AccountKind.External), vm.state.value.status)
        assertTrue(vm.state.value.isBlocked)
        vm.useAnotherAccount()
        assertEquals(AuthStatus.Idle, vm.state.value.status)
        assertEquals(AuthScreen.Login, vm.state.value.screen)
        vm.showNotAllowed("alex@gmail.com")
        vm.dismissNotAllowed()
        assertEquals(AuthScreen.Welcome, vm.state.value.screen)
        assertFalse(vm.state.value.isBlocked)
    }

    @Test
    fun externalAccessBehindPaymentShowsRequiresPayment() {
        val vm = AuthViewModel(session, AccountPolicy(externalAccessEnabled = true))
        vm.showNotAllowed("alex@gmail.com")
        assertEquals(AuthStatus.RequiresPayment("alex@gmail.com"), vm.state.value.status)
    }

    @Test
    fun domainRefusedOnRefreshOpensNotAllowed() = test {
        session.signedIn("t", FakeData.sam)
        val vm = vm()
        runCurrent()
        session.signOut(SignOutReason.DomainNotAllowed("old@bau.edu"))
        runCurrent()
        assertEquals(AuthStatus.BlockedDomain("old@bau.edu", AccountKind.BauStaff), vm.state.value.status)
        assertNull(session.signOutReason.value) // consumed once
    }

    @Test
    fun debugInitialScreenIsVisualOnlyAndNeverVerify() {
        assertEquals(listOf(AuthScreen.Welcome, AuthScreen.Login), vm(AuthScreen.Login).state.value.backStack)
        assertEquals(listOf(AuthScreen.Welcome), vm(AuthScreen.VerifyEmail).state.value.backStack)
    }
}
