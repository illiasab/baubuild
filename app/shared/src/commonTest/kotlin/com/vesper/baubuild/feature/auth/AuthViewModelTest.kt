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
import kotlin.test.assertFalse
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

    private val fakeSignIn = FakeMicrosoftSignIn(latencyMillis = 10)

    private fun fakeVm(email: String): AuthViewModel {
        fakeSignIn.selectedEmail = email
        return AuthViewModel(fakeSignIn, FakeAuthRepository(policy), session, policy)
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
        assertTrue(session.token!!.isNotBlank())
        assertTrue(session.token!! in tokens.read()!!) // persisted with the session
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

        fakeSignIn.selectedEmail = FakeData.sam.email
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
        assertEquals("tok", session.token)
        assertTrue("tok" in tokens.read()!!)
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
    fun unconfiguredMicrosoftAppShowsInlineErrorWithoutBrowser() = test {
        val vm = AuthViewModel(StubSignIn { throw com.vesper.baubuild.core.auth.MicrosoftSignInNotConfigured() },
            StubAuthRepository(), session, policy)
        vm.signIn(); advanceUntilIdle()
        assertEquals(AuthStatus.Error(SIGN_IN_NOT_SET_UP), vm.state.value.status)
    }

    @Test
    fun backendNotConfiguredShowsInlineError() = test {
        val vm = AuthViewModel(StubSignIn(), StubAuthRepository { AuthExchangeResult.NotConfigured }, session, policy)
        vm.signIn(); advanceUntilIdle()
        assertEquals(AuthStatus.Error(SIGN_IN_NOT_CONFIGURED), vm.state.value.status)
    }

    @Test
    fun missingEndpointReadsAsUnreachable() = test {
        val vm = AuthViewModel(StubSignIn(),
            StubAuthRepository { throw com.vesper.baubuild.core.network.ApiException(404, "http_404", "Not found") }, session, policy)
        vm.signIn(); advanceUntilIdle()
        assertEquals(AuthStatus.Error(SIGN_IN_NETWORK_ERROR), vm.state.value.status)
    }

    @Test
    fun startsOnWelcomeWithNoBack() = test {
        val vm = AuthViewModel(StubSignIn(), StubAuthRepository(), session, policy)
        assertEquals(AuthScreen.Welcome, vm.state.value.screen)
        assertFalse(vm.state.value.canGoBack)
        assertFalse(vm.back())
        assertEquals(AuthScreen.Welcome, vm.state.value.screen)
    }

    @Test
    fun navigatesWelcomeSignUpForgotAndBack() = test {
        val vm = AuthViewModel(StubSignIn(), StubAuthRepository(), session, policy)
        vm.navigateTo(AuthScreen.SignUp)
        assertEquals(AuthScreen.SignUp, vm.state.value.screen)
        assertTrue(vm.state.value.canGoBack)
        vm.navigateTo(AuthScreen.SignUp) // same screen twice doesn't stack
        assertEquals(listOf(AuthScreen.Welcome, AuthScreen.SignUp), vm.state.value.backStack)

        assertTrue(vm.back())
        assertEquals(AuthScreen.Welcome, vm.state.value.screen)

        vm.navigateTo(AuthScreen.ForgotPassword)
        assertEquals(AuthScreen.ForgotPassword, vm.state.value.screen)
        vm.backToWelcome()
        assertEquals(listOf(AuthScreen.Welcome), vm.state.value.backStack)
    }

    @Test
    fun backToWelcomeClearsTheStack() = test {
        val vm = AuthViewModel(StubSignIn(), StubAuthRepository(), session, policy)
        vm.navigateTo(AuthScreen.SignUp)
        vm.navigateTo(AuthScreen.ForgotPassword)
        vm.backToWelcome()
        assertEquals(AuthScreen.Welcome, vm.state.value.screen)
        assertFalse(vm.state.value.canGoBack)
    }

    @Test
    fun logInOpensLoginScreenWithoutStartingSignIn() = test {
        val stub = StubSignIn()
        val vm = AuthViewModel(stub, StubAuthRepository(), session, policy)
        vm.navigateTo(AuthScreen.Login)
        runCurrent()
        assertEquals(listOf(AuthScreen.Welcome, AuthScreen.Login), vm.state.value.backStack)
        assertEquals(AuthStatus.Idle, vm.state.value.status)
        assertTrue(stub.hints.isEmpty()) // Microsoft was never asked
    }

    @Test
    fun logInAndSignUpLinksPopInsteadOfStacking() = test {
        val vm = AuthViewModel(StubSignIn(), StubAuthRepository(), session, policy)
        vm.navigateTo(AuthScreen.Login)
        vm.navigateTo(AuthScreen.SignUp)
        assertEquals(listOf(AuthScreen.Welcome, AuthScreen.Login, AuthScreen.SignUp), vm.state.value.backStack)
        vm.navigateTo(AuthScreen.Login) // "Already a member? Log In" on Sign Up
        assertEquals(listOf(AuthScreen.Welcome, AuthScreen.Login), vm.state.value.backStack)

        vm.navigateTo(AuthScreen.ForgotPassword)
        vm.navigateTo(AuthScreen.Login) // "Back to sign in"
        assertEquals(listOf(AuthScreen.Welcome, AuthScreen.Login), vm.state.value.backStack)
        assertTrue(vm.back())
        assertEquals(AuthScreen.Welcome, vm.state.value.screen)
    }

    @Test
    fun notConfiguredShowsFriendlyInlineError() = test {
        val vm = AuthViewModel(StubSignIn(), StubAuthRepository { AuthExchangeResult.NotConfigured }, session, policy)
        vm.signIn(); advanceUntilIdle()
        assertEquals(AuthStatus.Error(SIGN_IN_NOT_CONFIGURED), vm.state.value.status)
        assertEquals(SessionState.Restoring, session.state.value)
    }

    @Test
    fun rejectedCodeShowsRetryError() = test {
        val vm = AuthViewModel(StubSignIn(), StubAuthRepository { AuthExchangeResult.Rejected }, session, policy)
        vm.signIn(); advanceUntilIdle()
        assertEquals(AuthStatus.Error(SIGN_IN_REJECTED), vm.state.value.status)
    }

    @Test
    fun unexpectedApiErrorIsNotReportedAsOffline() = test {
        val repo = StubAuthRepository { throw com.vesper.baubuild.core.network.ApiException(400, "invalid_body", "x") }
        val vm = AuthViewModel(StubSignIn(), repo, session, policy)
        vm.signIn(); advanceUntilIdle()
        assertEquals(AuthStatus.Error(SIGN_IN_FAILED), vm.state.value.status)
    }

    @Test
    fun refusedWithoutEmailFallsBackToLoginHint() = test {
        val repo = StubAuthRepository { AuthExchangeResult.NotAllowed("", AccountPolicy.Access.NotYetAvailable) }
        val vm = AuthViewModel(StubSignIn(), repo, session, policy)
        vm.signIn("alex@gmail.com"); advanceUntilIdle()
        assertEquals("alex@gmail.com", assertIs<AuthStatus.BlockedDomain>(vm.state.value.status).email)
    }

    @Test
    fun signedInStoresTheFullTokenPair() = test {
        val pair = com.vesper.baubuild.core.session.AuthTokens("acc", "ref", 123L)
        val repo = StubAuthRepository { AuthExchangeResult.SignedIn(com.vesper.baubuild.data.repository.AuthSession(pair, FakeData.sam)) }
        val vm = AuthViewModel(StubSignIn(), repo, session, policy)
        vm.signIn(); advanceUntilIdle()
        assertEquals(pair, session.tokens)
        assertEquals("acc", session.token)
    }

    @Test
    fun leavingAScreenClearsItsInlineError() = test {
        val vm = AuthViewModel(StubSignIn { throw MicrosoftSignInException("access_denied") }, StubAuthRepository(), session, policy)
        vm.navigateTo(AuthScreen.SignUp)
        vm.signIn(); advanceUntilIdle()
        assertIs<AuthStatus.Error>(vm.state.value.status)
        vm.back()
        assertEquals(AuthStatus.Idle, vm.state.value.status)
    }

    @Test
    fun signUpPassesLoginHintToMicrosoft() = test {
        val stub = StubSignIn()
        val repo = StubAuthRepository { AuthExchangeResult.SignedIn(com.vesper.baubuild.data.repository.AuthSession("tok", FakeData.sam)) }
        val vm = AuthViewModel(stub, repo, session, policy)
        vm.signIn("sam.carter@stu.bau.edu"); advanceUntilIdle()
        vm.signIn(); advanceUntilIdle()
        assertEquals(listOf("sam.carter@stu.bau.edu", null), stub.hints)
    }

    @Test
    fun signOutHelperRevokesAndClearsSession() = test {
        var revoked = false
        val repo = StubAuthRepository(onSignOut = { revoked = true })
        session.signedIn("tok", FakeData.sam)
        signOut(session, repo)
        assertTrue(revoked)
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
    val hints = mutableListOf<String?>()
    override suspend fun signIn(loginHint: String?): MicrosoftAuthorization {
        hints += loginHint
        return block()
    }
}

private class StubAuthRepository(
    private val onSignOut: () -> Unit = {},
    private val exchange: suspend () -> AuthExchangeResult = { error("not used") },
) : AuthRepository {
    constructor(exchange: suspend () -> AuthExchangeResult) : this({}, exchange)
    override suspend fun exchange(code: String, codeVerifier: String, redirectUri: String) = exchange()
    override suspend fun me(): User = FakeData.sam
    override suspend fun signOut() = onSignOut()
}
