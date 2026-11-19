package com.vesper.baubuild.feature.mywork.portal

import com.vesper.baubuild.core.model.Membership
import com.vesper.baubuild.core.model.MembershipStatus
import com.vesper.baubuild.core.model.ReviewStage
import com.vesper.baubuild.core.model.ReviewStageState
import com.vesper.baubuild.data.fake.FakeData
import com.vesper.baubuild.feature.mywork.MyWorkSections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MyWorkPortalFormattingTest {
    @Test fun summaryFromDesignData() {
        val summary = MyWorkSummary.from(MyWorkSections.from(FakeData.memberships))
        assertEquals(MyWorkSummary(activeProjects = 2, waitingProjects = 2, tasksAssigned = 5), summary)
        assertEquals("2 projects", projectCountLabel(summary.activeProjects))
    }

    @Test fun summaryCountsApprovedAsActiveAndIgnoresWaitingTasks() {
        val sections = MyWorkSections.from(
            listOf(
                Membership(FakeData.roomBooking, MembershipStatus.Approved),
                Membership(FakeData.courseSearch, MembershipStatus.WaitingForApproval),
            ),
        )
        assertEquals(MyWorkSummary(1, 1, FakeData.roomBooking.taskCount), MyWorkSummary.from(sections))
        assertEquals(MyWorkSummary(0, 0, 0), MyWorkSummary.from(MyWorkSections.from(emptyList())))
    }

    @Test fun countLabels() {
        assertEquals("0 projects", projectCountLabel(0))
        assertEquals("1 project", projectCountLabel(1))
    }

    @Test fun whereItStands() {
        assertEquals("3 tasks assigned to you", FakeData.memberships[0].whereItStands)
        assertEquals("1 task assigned to you", Membership(FakeData.roomBooking.copy(taskCount = 1), MembershipStatus.In).whereItStands)
        assertEquals("Reviewing: professor of CMPS433", FakeData.memberships[2].whereItStands)
    }

    @Test fun workspaceMetaLine() {
        assertEquals("library-booking · Easy · \$18 · due in 3 days", FakeData.roomBooking.workspaceMeta)
        assertEquals("Easy · \$18 · due tomorrow", FakeData.roomBooking.copy(repoName = null, dueInDays = 1).workspaceMeta)
        assertEquals("due today", dueLabel(0))
        assertEquals("overdue", dueLabel(-2))
    }

    @Test fun reviewMatchedByProjectId() {
        assertEquals(FakeData.roomBooking.id, FakeData.reviews.forProject(FakeData.roomBooking.id)?.project?.id)
        assertNull(FakeData.reviews.forProject(FakeData.attendanceCsv.id))
    }

    @Test fun stageDetailJoinsNote() {
        assertEquals("a. b.", ReviewStage("t", "a.", ReviewStageState.Done, note = "b.").portalDetail)
        assertEquals("a.", ReviewStage("t", "a.", ReviewStageState.Done).portalDetail)
        assertEquals("", ReviewStage("t", "", ReviewStageState.Pending).portalDetail)
    }
}
