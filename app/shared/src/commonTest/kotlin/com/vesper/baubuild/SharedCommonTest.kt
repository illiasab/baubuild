package com.vesper.baubuild

import com.vesper.baubuild.core.model.AccountKind
import com.vesper.baubuild.core.model.AccountPolicy
import com.vesper.baubuild.core.model.AccountPolicy.Access
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountPolicyTest {
    private val policy = AccountPolicy()

    @Test fun studentDomainIsFreeStudent() {
        assertEquals(AccountKind.BauStudent, policy.classify("Sam.Carter@STU.bau.edu"))
        assertEquals(Access.Free, policy.access("sam@stu.bau.edu"))
    }

    @Test fun staffDomainIsFreeStaff() {
        assertEquals(AccountKind.BauStaff, policy.classify("prof@bau.edu"))
        assertEquals(Access.Free, policy.access("prof@bau.edu"))
    }

    @Test fun externalBlockedUntilEnabledThenPaid() {
        assertEquals(Access.NotYetAvailable, policy.access("x@gmail.com"))
        assertEquals(Access.RequiresPayment, policy.copy(externalAccessEnabled = true).access("x@gmail.com"))
    }

    @Test fun lookalikeDomainsAreExternal() {
        assertEquals(AccountKind.External, policy.classify("x@evilbau.edu"))
        assertEquals(AccountKind.External, policy.classify("x@stu.bau.edu.attacker.com"))
    }
}
