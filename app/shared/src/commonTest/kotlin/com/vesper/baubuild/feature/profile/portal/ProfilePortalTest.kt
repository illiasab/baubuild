package com.vesper.baubuild.feature.profile.portal

import com.vesper.baubuild.core.model.Role
import com.vesper.baubuild.data.fake.FakeData
import kotlin.test.*

class ProfilePortalTest {
    @Test fun professionalRowsShowEmptyValuesForNewStudent() {
        val fresh = FakeData.sam.copy(skills = emptyList(), classCodes = emptyList(), role = Role.Student, githubUsername = null)
        val rows = professionalRows(fresh).associateBy { it.label }
        assertEquals(listOf("Skills", "Classes", "GitHub", "Role"), professionalRows(fresh).map { it.label })
        assertEquals("None yet", rows.getValue("Skills").value)
        assertEquals("None yet", rows.getValue("Classes").value)
        assertEquals("Student", rows.getValue("Role").value)
        assertEquals(
            "JavaScript, Python",
            professionalRows(fresh.copy(skills = listOf("JavaScript", "Python"))).first { it.label == "Skills" }.value,
        )
    }

    @Test fun githubRowHasNoActionsWhileGitHubIsOff() {
        val saved = professionalRows(FakeData.sam.copy(githubUsername = "samc"), gitHubLogin = "samc", gitHubEnabled = false)
            .first { it.label == "GitHub" }
        assertEquals("@samc · GitHub isn’t connected for BAU BUILD yet", saved.value)
        assertFalse(saved.connectAction)
        assertFalse(saved.manageAction)
        val none = professionalRows(FakeData.sam.copy(githubUsername = null), gitHubEnabled = false).first { it.label == "GitHub" }
        assertEquals("GitHub isn’t connected for BAU BUILD yet", none.value)
        assertFalse(none.connectAction)
    }

    @Test fun githubRowOffersConnectWhenNotLinked() {
        for (name in listOf(null, "", "  ")) {
            val row = professionalRows(FakeData.sam.copy(githubUsername = name)).first { it.label == "GitHub" }
            assertEquals("Not connected", row.value)
            assertTrue(row.connectAction)
        }
    }

    @Test fun githubRowSaysConnectedOnlyForLiveConnection() {
        val saved = professionalRows(FakeData.sam.copy(githubUsername = "samdev")).first { it.label == "GitHub" }
        assertEquals("@samdev · Not connected", saved.value)
        assertTrue(saved.connectAction)
        val live = professionalRows(FakeData.sam.copy(githubUsername = "samdev"), gitHubLogin = "samdev").first { it.label == "GitHub" }
        assertEquals("Connected · @samdev", live.value)
        assertFalse(live.connectAction)
    }

    @Test fun leadStageTitlesUseSentenceCase() {
        assertEquals("In-person stage", sentenceCase("In-Person Stage"))
        assertEquals("Proctored exam", sentenceCase("Proctored Exam"))
    }
}
