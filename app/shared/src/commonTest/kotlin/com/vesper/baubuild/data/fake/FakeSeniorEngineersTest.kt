package com.vesper.baubuild.data.fake

import com.vesper.baubuild.core.model.Role
import com.vesper.baubuild.core.session.InMemoryTokenStore
import com.vesper.baubuild.core.session.SessionStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeSeniorEngineersTest {
    @Test fun iliasAndArianAreInterviewersWithBios() {
        val (ilias, arian) = FakeStaffData.seniorEngineers
        assertEquals("Ilias", ilias.name)
        assertEquals("Tech Lead Engineer", ilias.title)
        assertEquals("Arian", arian.name)
        assertEquals("Senior Engineer", arian.title)
        FakeStaffData.seniorEngineers.forEach {
            assertTrue(it.isInterviewer)
            assertTrue(it.bio.isNotBlank())
            assertTrue(it.skills.isNotEmpty())
        }
        assertEquals("I", ilias.initials)
        assertEquals(FakeStaffData.seniorEngineers, FakeStaffData.noraProfile.seniorEngineers)
    }

    @Test fun repositoryReturnsProfileAndDetail() = runTest {
        val session = SessionStore(InMemoryTokenStore()).apply {
            signedIn("t", FakeData.sam.copy(email = FakeStaffData.DEMO_EMAIL, role = Role.Staff))
        }
        val repo = FakeStaffRepository(session)
        assertEquals(listOf("Ilias", "Arian"), repo.profile().seniorEngineers.map { it.name })
        assertEquals(FakeStaffData.arian, repo.seniorEngineer("eng-arian"))
    }
}
