package com.vesper.baubuild.feature.auth

import com.vesper.baubuild.core.auth.FakeMicrosoftSignIn
import com.vesper.baubuild.core.auth.MicrosoftAuthorization
import com.vesper.baubuild.core.auth.MicrosoftSignIn
import com.vesper.baubuild.core.auth.MicrosoftSignInCancelled
import com.vesper.baubuild.core.auth.MicrosoftSignInException
import com.vesper.baubuild.core.model.AccountKind
import com.vesper.baubuild.core.model.AccountPolicy
import com.vesper.baubuild.core.model.User
import com.vesper.baubuild.core.session.InMemoryTokenStore
import com.vesper.baubuild.core.session.SessionState
import com.vesper.baubuild.core.session.SessionStore
import com.vesper.baubuild.data.fake.FakeAuthRepository
import com.vesper.baubuild.data.fake.FakeData
import com.vesper.baubuild.data.repository.AuthExchangeResult
import com.vesper.baubuild.data.repository.AuthRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val tokens = InMemoryTokenStore()
    private val session = SessionStore(tokens)
    private val policy = AccountPolicy()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private fun fakeVm(email: String): AuthViewModel {
        val signIn = FakeMicrosoftSignIn(latencyMillis = 10)
        return AuthViewModel(signIn, FakeAuthRepository(policy), session, policy).also { it.selectDemoAccount(email) }
    }

    @Test
    fun studentSignsInAndTokenIsStored() = test {
        val vm = fakeVm(FakeData.sam.email)
        assertEquals(AuthStatus.Idle, vm.state.value.status)

        vm.signIn()
        runCurrent()
        assertEquals(AuthStatus.SigningIn, vm.state.value.status)

        advanceUntilIdle()
        assertEquals(AuthStatus.Idle, vm.state.value.status)
        val signedIn = assertIs<SessionState.SignedIn>(session.state.value)
        assertEquals(FakeData.sam, signedIn.user)
        assertTrue(tokens.read()!!.isNotBlank())
        assertEquals(tokens.read(), session.token)
    }

    @Test
    fun staffSignsInAsStaff() = test {
        val vm = fakeVm("dr.aydin@bau.edu")
        vm.signIn(); advanceUntilIdle()
        val user = assertIs<SessionState.SignedIn>(session.state.value).user
        assertEquals(AccountKind.BauStaff, user.kind)
    }

    @Test
    fun externalAccountIsBlockedWithoutSession() = test {
        val vm = fakeVm("alex@gmail.com")
        vm.signIn(); advanceUntilIdle()

        val status = assertIs<AuthStatus.BlockedDomain>(vm.state.value.status)
        assertEquals("alex@gmail.com", status.email)
        assertEquals(AccountKind.External, status.kind)
        assertEquals(SessionState.Restoring, session.state.value) // never signed in
        assertEquals(null, tokens.read())

        vm.signOut()
        assertEquals(AuthStatus.Idle, vm.state.value.status)
    }

    @Test
    fun blockedUserCanTryAnotherAccount() = test {
        val vm = fakeVm("alex@gmail.com")
        vm.signIn(); advanceUntilIdle()
        assertIs<AuthStatus.BlockedDomain>(vm.state.value.status)

        vm.selectDemoAccount(FakeData.sam.email)
        vm.useAnotherAccount()
        runCurrent()
        assertEquals(AuthStatus.SigningIn, vm.state.value.status)
        advanceUntilIdle()
        assertIs<SessionState.SignedIn>(session.state.value)
    }

    @Test
    fun externalAccountRequiresPaymentWhenExternalAccessIsOpen() = test {
        val open = AccountPolicy(externalAccessEnabled = true)
        val vm = AuthViewModel(FakeMicrosoftSignIn(latencyMillis = 0).apply { selectedEmail = "alex@gmail.com" },
            FakeAuthRepository(open), session, open)
        vm.signIn(); advanceUntilIdle()
        assertEquals(AuthStatus.RequiresPayment("alex@gmail.com"), vm.state.value.status)
    }

    @Test
    fun backendNotAllowedWithoutAccessFallsBackToPolicy() = test {
        val repo = StubAuthRepository { AuthExchangeResult.NotAllowed("someone@example.com") }
        val vm = AuthViewModel(StubSignIn(), repo, session, policy)
        vm.signIn(); advanceUntilIdle()
        assertIs<AuthStatus.BlockedDomain>(vm.state.value.status)
    }

    @Test
    fun userCancellingTheBrowserReturnsToIdleSilently() = test {
        val vm = AuthViewModel(StubSignIn { throw MicrosoftSignInCancelled() }, StubAuthRepository(), session, policy)
        vm.signIn(); advanceUntilIdle()
        assertEquals(AuthStatus.Idle, vm.state.value.status)
    }

    @Test
    fun microsoftErrorShowsInlineError() = test {
        val vm = AuthViewModel(StubSignIn { throw MicrosoftSignInException("access_denied", "Admin approval required.") },
            StubAuthRepository(), session, policy)
        vm.signIn(); advanceUntilIdle()
        val error = assertIs<AuthStatus.Error>(vm.state.value.status)
        assertTrue("Admin approval required." in error.message)
    }

    @Test
    fun networkFailureShowsErrorAndRetryWorks() = test {
        var fail = true
        val repo = StubAuthRepository {
            if (fail) throw IllegalStateException("502") else AuthExchangeResult.SignedIn(
                com.vesper.baubuild.data.repository.AuthSession("tok", FakeData.sam))
        }
        val vm = AuthViewModel(StubSignIn(), repo, session, policy)
        vm.signIn(); advanceUntilIdle()
        assertIs<AuthStatus.Error>(vm.state.value.status)
        assertEquals(SessionState.Restoring, session.state.value)

        fail = false
        vm.signIn(); advanceUntilIdle()
        assertEquals(AuthStatus.Idle, vm.state.value.status)
        assertEquals("tok", tokens.read())
    }

    @Test
    fun cancelWhileWaitingForBrowserReturnsToIdle() = test {
        val never = CompletableDeferred<MicrosoftAuthorization>()
        val vm = AuthViewModel(StubSignIn { never.await() }, StubAuthRepository(), session, policy)
        vm.signIn(); runCurrent()
        assertEquals(AuthStatus.SigningIn, vm.state.value.status)

        vm.signIn() // double tap is ignored
        vm.cancelSignIn(); advanceUntilIdle()
        assertEquals(AuthStatus.Idle, vm.state.value.status)
        assertEquals(SessionState.Restoring, session.state.value)
    }

    @Test
    fun demoAccountsOnlyExposedForFakeSignIn() = test {
        assertEquals(null, AuthViewModel(StubSignIn(), StubAuthRepository(), session, policy).state.value.demo)
        val demo = fakeVm("dr.aydin@bau.edu").state.value.demo!!
        assertEquals("dr.aydin@bau.edu", demo.selected)
    }

    @Test
    fun signOutHelperRevokesAndClearsSession() = test {
        var revoked: String? = null
        val repo = StubAuthRepository(onSignOut = { revoked = it })
        session.signedIn("tok", FakeData.sam)
        signOut(session, repo)
        assertEquals("tok", revoked)
        assertEquals(SessionState.SignedOut, session.state.value)
        assertEquals(null, tokens.read())
    }

    @Test
    fun signOutHelperClearsLocallyEvenWhenOffline() = test {
        val repo = StubAuthRepository(onSignOut = { throw IllegalStateException("offline") })
        session.signedIn("tok", FakeData.sam)
        signOut(session, repo)
        assertEquals(SessionState.SignedOut, session.state.value)
    }
}

private class StubSignIn(
    private val block: suspend () -> MicrosoftAuthorization = { MicrosoftAuthorization("code", "verifier", "app://auth") },
) : MicrosoftSignIn {
    override suspend fun signIn() = block()
}

private class StubAuthRepository(
    private val onSignOut: (String) -> Unit = {},
    private val exchange: suspend () -> AuthExchangeResult = { error("not used") },
) : AuthRepository {
    constructor(exchange: suspend () -> AuthExchangeResult) : this({}, exchange)
    override suspend fun exchangeMicrosoftCode(code: String, codeVerifier: String, redirectUri: String) = exchange()
    override suspend fun me(token: String): User = FakeData.sam
    override suspend fun signOut(token: String) = onSignOut(token)
}
