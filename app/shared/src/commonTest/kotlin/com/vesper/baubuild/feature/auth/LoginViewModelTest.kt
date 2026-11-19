package com.vesper.baubuild.feature.auth

import com.vesper.baubuild.core.network.ApiException
import com.vesper.baubuild.core.session.InMemoryTokenStore
import com.vesper.baubuild.core.session.SessionState
import com.vesper.baubuild.core.session.SessionStore
import com.vesper.baubuild.data.fake.FakeAuthRepository
import com.vesper.baubuild.core.model.AccountPolicy
import com.vesper.baubuild.data.repository.AuthOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest : ViewModelTest() {
    private val session = SessionStore(InMemoryTokenStore())
    private val repo = FakeAuthRepository(session = session)
    private val nav = RecordingNavigator()
    private val vm by lazy { LoginViewModel(repo, AccountPolicy(), nav) }

    private fun fill(email: String, password: String) {
        vm.onEmailChange(email)
        vm.onPasswordChange(password)
    }

    @Test
    fun needsACompleteEmailAndAPassword() {
        assertFalse(vm.state.value.canSubmit)
        fill("sam@stu", "x")
        assertFalse(vm.state.value.canSubmit)
        fill("sam@stu.bau.edu", "")
        assertFalse(vm.state.value.canSubmit)
        fill("sam@stu.bau.edu", "x")
        assertTrue(vm.state.value.canSubmit)
    }

    @Test
    fun successSignsIn() = test {
        repo.addAccount("dr.aydin@bau.edu", "teach2024", name = "Dr. Aydin")
        fill("dr.aydin@bau.edu", "teach2024")
        vm.submit()
        assertTrue(vm.state.value.submitting)
        advanceUntilIdle()
        assertFalse(vm.state.value.submitting)
        assertEquals("dr.aydin@bau.edu", assertIs<SessionState.SignedIn>(session.state.value).user.email)
    }

    @Test
    fun invalidCredentialsIsOneGenericMessage() = test {
        repo.addAccount("sam@stu.bau.edu", "buildit42")
        fill("sam@stu.bau.edu", "wrong")
        vm.submit(); advanceUntilIdle()
        assertEquals(MSG_INVALID_CREDENTIALS, vm.state.value.error)
        fill("nobody@stu.bau.edu", "buildit42")
        vm.submit(); advanceUntilIdle()
        assertEquals(MSG_INVALID_CREDENTIALS, vm.state.value.error)
        assertEquals("buildit42", vm.state.value.password) // input kept
    }

    @Test
    fun unverifiedAccountGoesToVerifyEmail() = test {
        repo.addAccount("new@stu.bau.edu", "buildit42", verified = false)
        fill("new@stu.bau.edu", "buildit42")
        vm.submit(); advanceUntilIdle()
        assertEquals<List<Pair<String, Long?>>>(listOf("new@stu.bau.edu" to FakeAuthRepository.RESEND_AFTER), nav.verifications)
        assertEquals<String?>(null, vm.state.value.error)
    }

    @Test
    fun lockoutShowsMinutesToWait() = test {
        fill("sam@stu.bau.edu", "x")
        repo.nextLogin = AuthOutcome.TooManyAttempts(retryAfterSeconds = 840)
        vm.submit(); advanceUntilIdle()
        assertEquals("Too many attempts. Try again in 14 min.", vm.state.value.error)
        repo.nextLogin = AuthOutcome.TooManyAttempts(retryAfterSeconds = 20)
        vm.submit(); advanceUntilIdle()
        assertEquals("Too many attempts. Try again in 1 min.", vm.state.value.error)
        repo.nextLogin = AuthOutcome.TooManyAttempts(null)
        vm.submit(); advanceUntilIdle()
        assertEquals("Too many attempts. Try again in a few minutes.", vm.state.value.error)
    }

    @Test
    fun serverDomainRefusalOpensNotAllowed() = test {
        fill("sam@stu.bau.edu", "x")
        repo.nextLogin = AuthOutcome.DomainNotAllowed
        vm.submit(); advanceUntilIdle()
        assertEquals(listOf("sam@stu.bau.edu"), nav.notAllowed)
    }

    @Test
    fun externalEmailIsRefusedLocally() = test {
        fill("alex@gmail.com", "x")
        vm.submit(); advanceUntilIdle()
        assertEquals(MSG_USE_BAU_EMAIL, vm.state.value.error)
        assertTrue(repo.calls.isEmpty())
    }

    @Test
    fun unreachableApiIsANetworkMessage() = test {
        fill("sam@stu.bau.edu", "x")
        repo.nextError = ApiException(503, "http_503", "down")
        vm.submit(); advanceUntilIdle()
        assertEquals(MSG_NETWORK, vm.state.value.error)
        repo.nextError = ApiException(500, "internal", "boom")
        vm.submit(); advanceUntilIdle()
        assertEquals(MSG_GENERIC, vm.state.value.error)
    }
}
