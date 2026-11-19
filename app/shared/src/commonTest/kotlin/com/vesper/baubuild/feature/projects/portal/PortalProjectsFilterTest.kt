package com.vesper.baubuild.feature.projects.portal

import com.vesper.baubuild.core.model.Difficulty
import com.vesper.baubuild.core.model.Inquiry
import com.vesper.baubuild.core.model.JoinPolicy
import com.vesper.baubuild.core.model.Project
import com.vesper.baubuild.core.model.Reviewer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortalProjectsFilterTest {
    private fun project(
        id: String,
        difficulty: Difficulty = Difficulty.Easy,
        minScore: Int = 0,
        classCode: String? = null,
        title: String = "Project $id",
        owner: String = "Library",
        joinPolicy: JoinPolicy = JoinPolicy.Open,
        reviewer: Reviewer = Reviewer.AiThenDevLead,
    ) = Project(
        id = id, title = title, owner = owner, summary = "", description = "Desc $id", bountyUsd = 20,
        difficulty = difficulty, minScore = minScore, joinPolicy = joinPolicy, dueInDays = 3,
        reviewer = reviewer, classCode = classCode,
    )

    private val easy = project("easy")
    private val medium = project("medium", Difficulty.Medium, title = "Add dark mode to the events calendar", owner = "Events")
    private val hardGated = project("hard", Difficulty.Hard, minScore = 300, classCode = "CMPS433", joinPolicy = JoinPolicy.ApprovalRequired, reviewer = Reviewer.Professor)
    private val urgentHard = project("urgent", Difficulty.Hard)
    private val all = listOf(easy, medium, hardGated, urgentHard)

    private val urgentInquiry = Inquiry("i1", "Shuttle", "Transport Office", "Transport", 35, Difficulty.Hard, true, 0, "urgent")
    private val unlinkedUrgent = Inquiry("i2", "Wi-Fi", "IT", "IT", 25, Difficulty.Easy, true, 0, null)
    private val context = PortalFilterContext(score = 0, myClassCodes = setOf("CMPS433"), urgentProjectIds = setOf("urgent"))

    private fun ids(filters: PortalProjectFilters, ctx: PortalFilterContext = context) =
        all.filter { it.matches(filters, ctx) }.map { it.id }

    @Test fun allShowsEverything() = assertEquals(all.map { it.id }, ids(PortalProjectFilters()))

    @Test fun difficultyFilters() {
        assertEquals(listOf("easy"), ids(PortalProjectFilters(difficulty = Difficulty.Easy)))
        assertEquals(listOf("medium"), ids(PortalProjectFilters(difficulty = Difficulty.Medium)))
        assertEquals(listOf("hard", "urgent"), ids(PortalProjectFilters(difficulty = Difficulty.Hard)))
    }

    @Test fun myClassesUsesClassCodes() {
        assertEquals(listOf("hard"), ids(PortalProjectFilters(myClasses = true)))
        assertEquals(emptyList(), ids(PortalProjectFilters(myClasses = true), context.copy(myClassCodes = emptySet())))
    }

    @Test fun urgentUsesProjectsFromUrgentInquiries() {
        assertEquals(setOf("urgent"), urgentProjectIdsOf(listOf(urgentInquiry, unlinkedUrgent, urgentInquiry.copy(id = "i3", isUrgent = false, projectId = "easy"))))
        assertEquals(listOf("urgent"), ids(PortalProjectFilters(urgent = true)))
    }

    @Test fun openToMyScoreHidesGatedProjects() {
        assertEquals(listOf("easy", "medium", "urgent"), ids(PortalProjectFilters(openToMyScore = true)))
        assertEquals(all.map { it.id }, ids(PortalProjectFilters(openToMyScore = true), context.copy(score = 300)))
    }

    @Test fun togglesCombineWithDifficulty() {
        assertEquals(listOf("urgent"), ids(PortalProjectFilters(difficulty = Difficulty.Hard, openToMyScore = true)))
        assertEquals(emptyList(), ids(PortalProjectFilters(difficulty = Difficulty.Easy, urgent = true)))
    }

    @Test fun searchMatchesTitleOwnerAndDescriptionIgnoringCase() {
        assertEquals(listOf("medium"), ids(PortalProjectFilters(query = "  DARK mode ")))
        assertEquals(listOf("medium"), ids(PortalProjectFilters(query = "events")))
        assertEquals(listOf("hard"), ids(PortalProjectFilters(query = "cmps433")))
        assertEquals(listOf("easy"), ids(PortalProjectFilters(query = "desc easy")))
    }

    @Test fun heroIsFirstLinkedUrgentProjectAndLeavesTheGrid() {
        val layout = portalProjectsLayout(all, listOf(unlinkedUrgent, urgentInquiry), PortalProjectFilters(), context)
        assertEquals("urgent", layout.hero?.project?.id)
        assertFalse(layout.grid.any { it.id == "urgent" })
        assertEquals(3, layout.grid.size)
    }

    @Test fun heroHiddenWhenFilteredOut() {
        val layout = portalProjectsLayout(all, listOf(urgentInquiry), PortalProjectFilters(difficulty = Difficulty.Easy), context)
        assertNull(layout.hero)
        assertEquals(listOf("easy"), layout.grid.map { it.id })
    }

    @Test fun noHeroWithoutLinkedProject() {
        assertNull(urgentHeroOf(listOf(unlinkedUrgent), all))
        assertNull(urgentHeroOf(listOf(urgentInquiry.copy(projectId = "missing")), all))
    }

    @Test fun emptyLayout() {
        val layout = portalProjectsLayout(all, listOf(urgentInquiry), PortalProjectFilters(query = "nothing matches"), context)
        assertTrue(layout.isEmpty)
        assertEquals("No projects match “nothing matches”.", PortalProjectFilters(query = "nothing matches ").emptyMessage())
        assertTrue(PortalProjectFilters().isDefault)
        assertFalse(PortalProjectFilters(urgent = true).isDefault)
    }

    @Test fun dueChipUsesWeeksForWholeWeeks() {
        assertEquals("Due today", portalDueChip(0))
        assertEquals("Due in 1 day", portalDueChip(1))
        assertEquals("Due in 3 days", portalDueChip(3))
        assertEquals("Due in 1 week", portalDueChip(7))
        assertEquals("Due in 2 weeks", portalDueChip(14))
        assertEquals("Due in 10 days", portalDueChip(10))
    }

    @Test fun joiningLabelMatchesPortalCopy() {
        assertEquals("Everyone welcome", easy.portalJoiningLabel)
        assertEquals("Professor approves", hardGated.portalJoiningLabel)
        assertEquals("Dev lead approves", project("x", joinPolicy = JoinPolicy.ApprovalRequired, reviewer = Reviewer.DevLead).portalJoiningLabel)
    }
}
