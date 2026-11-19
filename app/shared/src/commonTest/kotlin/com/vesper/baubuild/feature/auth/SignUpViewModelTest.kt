package com.vesper.baubuild.feature.auth

import com.vesper.baubuild.core.model.AccountPolicy
import com.vesper.baubuild.core.model.PasswordCheck
import com.vesper.baubuild.data.fake.FakeAuthRepository
import com.vesper.baubuild.data.repository.AuthOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SignUpViewModelTest : ViewModelTest() {
    private val repo = FakeAuthRepository()
    private val nav = RecordingNavigator()
    private fun vm(policy: AccountPolicy = AccountPolicy()) = SignUpViewModel(repo, policy, nav)

    private fun SignUpViewModel.fillValid(email: String = "sam.carter@stu.bau.edu") {
        onNameChange("Sam Carter")
        onEmailChange(email)
        onPasswordChange("buildit42")
        onConfirmPasswordChange("buildit42")
        onTermsAcceptedChange(true)
    }

    @Test
    fun startsEmptyAndDisabled() {
        val s = vm().state.value
        assertEquals(DomainCheck.None, s.domainCheck)
        assertFalse(s.canSubmit)
    }

    @Test
    fun classifiesStudentStaffAndExternalDomains() {
        val vm = vm()
        vm.onEmailChange("sam.carter@stu.bau.edu")
        assertEquals(DomainCheck.Student, vm.state.value.domainCheck)
        vm.onEmailChange("Dr.Aydin@BAU.EDU ")
        assertEquals(DomainCheck.Staff, vm.state.value.domainCheck)
        vm.onEmailChange("alex@gmail.com")
        assertEquals(DomainCheck.External(requiresPayment = false), vm.state.value.domainCheck)
        vm.onEmailChange("sam@stu")
        assertEquals(DomainCheck.None, vm.state.value.domainCheck)
    }

    @Test
    fun passwordRulesNeedEightCharsALetterAndADigit() {
        assertFalse(PasswordCheck.of("short1").isValid)
        assertFalse(PasswordCheck.of("allletters").isValid)
        assertFalse(PasswordCheck.of("12345678").isValid)
        assertTrue(PasswordCheck.of("buildit42").isValid)
        assertEquals(PasswordCheck(longEnough = true, hasLetter = true, hasDigit = false), PasswordCheck.of("abcdefgh"))
    }

    @Test
    fun createAccountIsGatedOnEveryField() {
        val vm = vm()
        vm.fillValid()
        assertTrue(vm.state.value.canSubmit)
        vm.onConfirmPasswordChange("buildit43")
        assertTrue(vm.state.value.confirmMismatch)
        assertFalse(vm.state.value.canSubmit)
        vm.onConfirmPasswordChange("buildit42")
        vm.onTermsAcceptedChange(false)
        assertFalse(vm.state.value.canSubmit)
        vm.onTermsAcceptedChange(true)
        vm.onNameChange("  ")
        assertFalse(vm.state.value.canSubmit)
        vm.onNameChange("Sam")
        vm.onPasswordChange("weak"); vm.onConfirmPasswordChange("weak")
        assertFalse(vm.state.value.canSubmit)
    }

    @Test
    fun externalEmailNeverReachesTheServer() = test {
        val vm = vm()
        vm.fillValid(email = "alex@gmail.com")
        assertFalse(vm.state.value.canSubmit)
        vm.submit()
        advanceUntilIdle()
        assertEquals(MSG_BAU_ONLY, vm.state.value.error)
        assertTrue(repo.calls.isEmpty())
    }

    @Test
    fun successSignsInDirectly() = test {
        val vm = vm()
        vm.fillValid()
        vm.submit()
        runCurrent()
        assertFalse(vm.state.value.submitting)
        assertNull(vm.state.value.error)
        assertTrue(nav.verifications.isEmpty())
        assertEquals(listOf("register:sam.carter@stu.bau.edu"), repo.calls)
    }

    @Test
    fun legacyVerificationRequiredOpensVerifyEmail() = test {
        val vm = vm()
        vm.fillValid()
        repo.nextRegister = AuthOutcome.VerificationRequired("sam.carter@stu.bau.edu", 30)
        vm.submit()
        runCurrent()
        assertFalse(vm.state.value.submitting)
        assertEquals<List<Pair<String, Long?>>>(listOf("sam.carter@stu.bau.edu" to 30L), nav.verifications)
    }

    @Test
    fun emailTakenShowsLogInInstead() = test {
        repo.addAccount("sam.carter@stu.bau.edu", "buildit42")
        val vm = vm()
        vm.fillValid()
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.state.value.emailTaken)
        assertTrue(nav.verifications.isEmpty())
        vm.onEmailChange("sam.c@stu.bau.edu")
        assertFalse(vm.state.value.emailTaken)
    }

    @Test
    fun serverErrorsStayInlineAndKeepInput() = test {
        val vm = vm()
        vm.fillValid()
        repo.nextRegister = AuthOutcome.WeakPassword
        vm.submit(); advanceUntilIdle()
        assertEquals(MSG_WEAK_PASSWORD, vm.state.value.error)
        repo.nextRegister = AuthOutcome.DomainNotAllowed
        vm.submit(); advanceUntilIdle()
        assertEquals(MSG_BAU_ONLY, vm.state.value.error)
        repo.nextError = RuntimeException("offline")
        vm.submit(); advanceUntilIdle()
        assertEquals(MSG_NETWORK, vm.state.value.error)
        assertEquals("buildit42", vm.state.value.password)
        vm.onNameChange("Sam C")
        assertNull(vm.state.value.error)
    }
}
