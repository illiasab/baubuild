package com.vesper.baubuild.feature.auth

import com.vesper.baubuild.core.model.AccountPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignUpViewModelTest {
    private fun vm(policy: AccountPolicy = AccountPolicy()) = SignUpViewModel(policy)

    @Test
    fun startsEmptyWithNoCheckAndContinueDisabled() {
        val state = vm().state.value
        assertEquals("", state.email)
        assertEquals(DomainCheck.None, state.domainCheck)
        assertFalse(state.canContinue)
        assertNull(state.loginHint)
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
    }

    @Test
    fun externalNeedsPaymentWhenExternalAccessIsOpen() {
        val vm = vm(AccountPolicy(externalAccessEnabled = true))
        vm.onEmailChange("alex@gmail.com")
        assertEquals(DomainCheck.External(requiresPayment = true), vm.state.value.domainCheck)
    }

    @Test
    fun incompleteAddressesShowNothing() {
        val vm = vm()
        listOf("", "   ", "sam", "sam@", "@bau.edu", "sam@bau", "sam@stu.").forEach { input ->
            vm.onEmailChange(input)
            assertEquals(DomainCheck.None, vm.state.value.domainCheck, "for '$input'")
            assertNull(vm.state.value.loginHint, "hint for '$input'")
        }
    }

    @Test
    fun loginHintIsTrimmedCompleteAddress() {
        val vm = vm()
        vm.onEmailChange("  sam.carter@stu.bau.edu ")
        assertEquals("sam.carter@stu.bau.edu", vm.state.value.loginHint)
        assertEquals("  sam.carter@stu.bau.edu ", vm.state.value.email) // field keeps what was typed
    }

    @Test
    fun termsGateContinueAndEmailIsOptional() {
        val vm = vm()
        vm.onTermsAcceptedChange(true)
        assertTrue(vm.state.value.canContinue)
        vm.onEmailChange("alex@gmail.com") // the backend decides; the client only explains
        assertTrue(vm.state.value.canContinue)
        vm.onTermsAcceptedChange(false)
        assertFalse(vm.state.value.canContinue)
    }
}
