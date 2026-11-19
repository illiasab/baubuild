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

class SessionStoreResilienceTest {
    private class BrokenTokenStore : com.vesper.baubuild.core.session.TokenStore {
        override suspend fun read(): String? = error("Keychain read failed: -34018")
        override suspend fun write(token: String) = error("Keychain write failed: -34018")
        override suspend fun clear() = error("Keychain delete failed: -34018")
    }

    @Test fun brokenSecureStorageNeverCrashes() = kotlinx.coroutines.test.runTest {
        val store = com.vesper.baubuild.core.session.SessionStore(BrokenTokenStore())
        store.restore { error("should not be called") }
        assertEquals(com.vesper.baubuild.core.session.SessionState.SignedOut, store.state.value)
        store.signedIn("t", com.vesper.baubuild.data.fake.FakeData.sam)
        assertEquals("t", store.token)
        store.signOut()
        assertEquals(com.vesper.baubuild.core.session.SessionState.SignedOut, store.state.value)
    }
}
