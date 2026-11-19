package com.vesper.baubuild.feature.auth

import com.vesper.baubuild.core.session.InMemoryTokenStore
import com.vesper.baubuild.core.session.SessionState
import com.vesper.baubuild.core.session.SessionStore
import com.vesper.baubuild.data.fake.FakeAuthRepository
import com.vesper.baubuild.data.repository.AuthOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class VerifyEmailViewModelTest : ViewModelTest() {
    private val session = SessionStore(InMemoryTokenStore())
    private val repo = FakeAuthRepository(session = session).apply {
        addAccount("sam@stu.bau.edu", "buildit42", verified = false)
    }
    private val vm by lazy { VerifyEmailViewModel(repo) }
    private val pending = PendingVerification(1, "sam@stu.bau.edu", 30)

    @Test
    fun sixthDigitAutoSubmitsAndSignsIn() = test {
        vm.start(pending)
        vm.onCodeChange("12345")
        runCurrent()
        assertTrue(repo.calls.isEmpty())
        vm.onCodeChange("123456")
        runCurrent()
        assertEquals(listOf("verify:sam@stu.bau.edu:123456"), repo.calls)
        assertIs<SessionState.SignedIn>(session.state.value)
    }

    @Test
    fun pasteKeepsDigitsOnly() = test {
        vm.start(pending)
        vm.onCodeChange("Code: 12 34")
        assertEquals("1234", vm.state.value.code)
        vm.onCodeChange("9876543210")
        runCurrent()
        assertEquals("987654", vm.state.value.code)
        assertEquals(MSG_INVALID_CODE, vm.state.value.error) // wrong code was auto-submitted
    }

    @Test
    fun expiredAndLockedCodesExplainResend() = test {
        vm.start(pending)
        repo.nextVerify = AuthOutcome.CodeExpired
        vm.onCodeChange("111111"); runCurrent()
        assertEquals(MSG_CODE_EXPIRED, vm.state.value.error)
        repo.nextVerify = AuthOutcome.TooManyAttempts(null)
        vm.onCodeChange("11111"); vm.onCodeChange("111112"); runCurrent()
        assertEquals(MSG_CODE_LOCKED, vm.state.value.error)
    }

    @Test
    fun resendCountsDownFromTheServerValue() = test {
        vm.start(pending)
        assertEquals(30, vm.state.value.resendInSeconds)
        assertFalse(vm.state.value.canResend)
        vm.resend(); runCurrent()
        assertTrue(repo.calls.isEmpty()) // locked during the countdown
        advanceTimeBy(10_001)
        assertEquals(20, vm.state.value.resendInSeconds)
        advanceTimeBy(20_000)
        assertEquals(0, vm.state.value.resendInSeconds)
        assertTrue(vm.state.value.canResend)

        repo.nextResend = AuthOutcome.CodeSent(45)
        vm.resend(); runCurrent()
        assertEquals(listOf("resend:verify:sam@stu.bau.edu"), repo.calls)
        assertEquals("We sent a new code to sam@stu.bau.edu.", vm.state.value.notice)
        assertEquals(45, vm.state.value.resendInSeconds)
        advanceTimeBy(46_000)
        assertEquals(0, vm.state.value.resendInSeconds)
    }

    @Test
    fun resendRateLimitedWaitsRetryAfter() = test {
        vm.start(pending.copy(resendAfterSeconds = 0))
        repo.nextResend = AuthOutcome.TooManyAttempts(120)
        vm.resend(); runCurrent()
        assertEquals("Too many attempts. Try again in 2 min.", vm.state.value.error)
        assertEquals(120, vm.state.value.resendInSeconds)
        advanceTimeBy(121_000)
    }

    @Test
    fun sameRequestDoesNotRestartButANewOneDoes() = test {
        vm.start(pending)
        vm.onCodeChange("12")
        vm.start(pending)
        assertEquals("12", vm.state.value.code)
        vm.start(pending.copy(id = 2, email = "other@stu.bau.edu", resendAfterSeconds = 0))
        assertEquals("", vm.state.value.code)
        assertEquals("other@stu.bau.edu", vm.state.value.email)
        assertEquals(0, vm.state.value.resendInSeconds)
    }
}
