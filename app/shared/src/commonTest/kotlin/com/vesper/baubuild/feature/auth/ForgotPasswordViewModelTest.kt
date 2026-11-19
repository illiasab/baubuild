package com.vesper.baubuild.feature.auth

import com.vesper.baubuild.core.model.AccountPolicy
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
class ForgotPasswordViewModelTest : ViewModelTest() {
    private val session = SessionStore(InMemoryTokenStore())
    private val repo = FakeAuthRepository(session = session).apply { addAccount("sam@stu.bau.edu", "oldpass1") }
    private val vm by lazy { ForgotPasswordViewModel(repo, AccountPolicy()) }

    @Test
    fun forgotThenResetSignsIn() = test {
        vm.onEmailChange("sam@stu.bau.edu")
        vm.sendCode(); runCurrent()
        assertEquals(ForgotPasswordStep.Reset, vm.state.value.step)
        assertEquals("If an account exists for sam@stu.bau.edu, we sent a 6-digit code to it.", vm.state.value.notice)
        assertEquals(FakeAuthRepository.RESEND_AFTER, vm.state.value.resendInSeconds)

        vm.onCodeChange("123456")
        vm.onNewPasswordChange("newpass22")
        vm.onConfirmPasswordChange("newpass2")
        assertFalse(vm.state.value.canReset)
        vm.onConfirmPasswordChange("newpass22")
        assertTrue(vm.state.value.canReset)
        vm.resetPassword(); runCurrent()
        assertEquals("sam@stu.bau.edu", assertIs<SessionState.SignedIn>(session.state.value).user.email)
        assertEquals("newpass22", repo.accounts["sam@stu.bau.edu"]!!.password)
        vm.back() // stops the countdown
    }

    @Test
    fun unknownEmailStillProceeds() = test {
        vm.onEmailChange("ghost@stu.bau.edu")
        vm.sendCode(); runCurrent()
        assertEquals(ForgotPasswordStep.Reset, vm.state.value.step)
        vm.back()
    }

    @Test
    fun externalEmailIsRefusedLocally() = test {
        vm.onEmailChange("alex@gmail.com")
        vm.sendCode(); runCurrent()
        assertEquals(MSG_USE_BAU_EMAIL, vm.state.value.error)
        assertEquals(ForgotPasswordStep.EnterEmail, vm.state.value.step)
        assertTrue(repo.calls.isEmpty())
    }

    @Test
    fun rateLimitedSendStaysOnStepOne() = test {
        vm.onEmailChange("sam@stu.bau.edu")
        repo.nextForgot = AuthOutcome.TooManyAttempts(300)
        vm.sendCode(); runCurrent()
        assertEquals(ForgotPasswordStep.EnterEmail, vm.state.value.step)
        assertEquals("Too many attempts. Try again in 5 min.", vm.state.value.error)
    }

    @Test
    fun resetErrorsAreInline() = test {
        vm.onEmailChange("sam@stu.bau.edu")
        vm.sendCode(); runCurrent()
        vm.onCodeChange("000000"); vm.onNewPasswordChange("newpass22"); vm.onConfirmPasswordChange("newpass22")
        vm.resetPassword(); runCurrent()
        assertEquals(MSG_INVALID_CODE, vm.state.value.error)
        repo.nextReset = AuthOutcome.CodeExpired
        vm.resetPassword(); runCurrent()
        assertEquals(MSG_CODE_EXPIRED, vm.state.value.error)
        repo.nextReset = AuthOutcome.WeakPassword
        vm.resetPassword(); runCurrent()
        assertEquals(MSG_WEAK_PASSWORD, vm.state.value.error)
        repo.nextReset = AuthOutcome.TooManyAttempts(null)
        vm.resetPassword(); runCurrent()
        assertEquals(MSG_CODE_LOCKED, vm.state.value.error)
        vm.back()
    }

    @Test
    fun resendUsesResetPurposeAfterCountdown() = test {
        vm.onEmailChange("sam@stu.bau.edu")
        vm.sendCode(); runCurrent()
        vm.resend(); runCurrent()
        assertEquals(listOf("forgot:sam@stu.bau.edu"), repo.calls)
        advanceTimeBy(FakeAuthRepository.RESEND_AFTER * 1000 + 1)
        vm.resend(); runCurrent()
        assertEquals("resend:reset:sam@stu.bau.edu", repo.calls.last())
        vm.back()
    }

    @Test
    fun changeEmailGoesBackToStepOne() = test {
        vm.onEmailChange("sam@stu.bau.edu")
        vm.sendCode(); runCurrent()
        assertTrue(vm.back())
        assertEquals(ForgotPasswordStep.EnterEmail, vm.state.value.step)
        assertEquals("sam@stu.bau.edu", vm.state.value.email)
        assertFalse(vm.back())
    }
}
