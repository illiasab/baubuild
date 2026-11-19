package com.vesper.baubuild.feature.auth

import com.vesper.baubuild.core.auth.MicrosoftAuthorization
import com.vesper.baubuild.core.auth.MicrosoftSignIn
import com.vesper.baubuild.core.model.AccountKind
import com.vesper.baubuild.core.model.AccountPolicy
import com.vesper.baubuild.core.session.InMemoryTokenStore
import com.vesper.baubuild.core.session.SessionState
import com.vesper.baubuild.core.session.SessionStore
import com.vesper.baubuild.data.fake.FakeAuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Log In is Microsoft-only: the form holds the email (login hint); [AuthViewModel] runs the sign-in. */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val session = SessionStore(InMemoryTokenStore())
    private val policy = AccountPolicy()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    @Test
    fun domainHintMatchesSignUp() = test {
        val vm = LoginViewModel(policy)
        vm.onEmailChange("sam.carter@stu")
        assertEquals(DomainCheck.None, vm.state.value.domainCheck)
        assertNull(vm.state.value.loginHint)
        vm.onEmailChange("sam.carter@stu.bau.edu")
        assertEquals(DomainCheck.Student, vm.state.value.domainCheck)
        vm.onEmailChange("dr.aydin@bau.edu")
        assertEquals(DomainCheck.Staff, vm.state.value.domainCheck)
        vm.onEmailChange("alex@gmail.com")
        assertEquals(DomainCheck.External(requiresPayment = false), vm.state.value.domainCheck)
    }

    @Test
    fun loginHintIsTrimmedCompleteAddress() = test {
        val vm = LoginViewModel(policy)
        vm.onEmailChange("  dr.aydin@bau.edu ")
        assertEquals("dr.aydin@bau.edu", vm.state.value.loginHint)
    }

    @Test
    fun continueWithMicrosoftUsesTheEmailAsLoginHintAndSignsIn() = test {
        val hints = mutableListOf<String?>()
        val microsoft = object : MicrosoftSignIn {
            override suspend fun signIn(loginHint: String?): MicrosoftAuthorization {
                hints += loginHint
                return MicrosoftAuthorization("fake-code:$loginHint", "verifier", "app://auth")
            }
        }
        val auth = AuthViewModel(microsoft, FakeAuthRepository(policy), session, policy)
        auth.navigateTo(AuthScreen.Login)
        val login = LoginViewModel(policy)
        login.onEmailChange("dr.aydin@bau.edu")

        auth.signIn(login.state.value.loginHint); advanceUntilIdle() // what AuthRoot wires to the button

        assertEquals(listOf<String?>("dr.aydin@bau.edu"), hints)
        assertEquals(AccountKind.BauStaff, assertIs<SessionState.SignedIn>(session.state.value).user.kind)
    }

    @Test
    fun refusedAccountFromLogInShowsBlockedScreenWithTypedEmail() = test {
        val microsoft = object : MicrosoftSignIn {
            override suspend fun signIn(loginHint: String?) = MicrosoftAuthorization("code", "verifier", "app://auth")
        }
        val repo = StubExchangeRepository { com.vesper.baubuild.data.repository.AuthExchangeResult.NotAllowed("", AccountPolicy.Access.NotYetAvailable) }
        val auth = AuthViewModel(microsoft, repo, session, policy)
        auth.navigateTo(AuthScreen.Login)
        auth.signIn("alex@gmail.com"); advanceUntilIdle()
        val blocked = assertIs<AuthStatus.BlockedDomain>(auth.state.value.status)
        assertEquals("alex@gmail.com", blocked.email)

        auth.useAnotherAccount(); advanceUntilIdle() // back to the Log In form to type a BAU address
        assertEquals(AuthStatus.Idle, auth.state.value.status)
        assertEquals(AuthScreen.Login, auth.state.value.screen)
    }
}

internal class StubExchangeRepository(
    private val exchange: suspend () -> com.vesper.baubuild.data.repository.AuthExchangeResult,
) : com.vesper.baubuild.data.repository.AuthRepository {
    override suspend fun exchange(code: String, codeVerifier: String, redirectUri: String) = exchange()
    override suspend fun me() = com.vesper.baubuild.data.fake.FakeData.sam
    override suspend fun signOut() = Unit
}
