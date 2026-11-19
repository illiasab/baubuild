package com.vesper.baubuild.feature.mywork.portal

import com.vesper.baubuild.core.model.Membership
import com.vesper.baubuild.core.model.MembershipStatus
import com.vesper.baubuild.core.model.ReviewStage
import com.vesper.baubuild.core.model.ReviewStageState
import com.vesper.baubuild.data.fake.FakeData
import com.vesper.baubuild.feature.mywork.MyWorkSections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MentorChatViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun startsEmpty() {
        val vm = MentorChatViewModel("p")
        assertEquals(emptyList(), vm.state.value.messages)
        assertFalse(vm.state.value.isReplying)
    }

    @Test fun sendAppendsUserMessageThenPlaceholderReplyAfterDelay() = runTest(dispatcher) {
        val vm = MentorChatViewModel("p", seed = emptyList(), replyDelayMillis = 500)
        assertTrue(vm.send("  How do I test dates?  "))
        runCurrent()
        val afterSend = vm.state.value
        assertEquals(listOf(MentorAuthor.Me), afterSend.messages.map { it.author })
        assertEquals("How do I test dates?", afterSend.messages.single().text)
        assertTrue(afterSend.isReplying)

        advanceTimeBy(499); runCurrent()
        assertEquals(1, vm.state.value.messages.size)

        advanceUntilIdle()
        val done = vm.state.value
        assertEquals(listOf(MentorAuthor.Me, MentorAuthor.Mentor), done.messages.map { it.author })
        assertEquals(MentorChatViewModel.PLACEHOLDER_REPLY, done.messages.last().text)
        assertFalse(done.isReplying)
        assertEquals(done.messages.size, done.messages.map { it.id }.toSet().size, "ids are unique")
    }

    @Test fun blankAndWhileReplyingAreIgnored() = runTest(dispatcher) {
        val vm = MentorChatViewModel("p")
        val seeded = vm.state.value.messages.size
        assertFalse(vm.send("   "))
        assertEquals(seeded, vm.state.value.messages.size)
        assertTrue(vm.send("first"))
        assertFalse(vm.send("second"), "cannot send while the mentor is replying")
        advanceUntilIdle()
        assertEquals(seeded + 2, vm.state.value.messages.size)
        assertTrue(vm.send("third"))
        advanceUntilIdle()
        assertEquals(seeded + 4, vm.state.value.messages.size)
        assertEquals(vm.state.value.messages.size, vm.state.value.messages.map { it.id }.toSet().size, "ids are unique")
    }

    @Test fun backtickSegments() {
        assertEquals(listOf("Try " to false, "a.js" to true, " now" to false), mentorSegments("Try `a.js` now"))
        assertEquals(listOf("plain" to false), mentorSegments("plain"))
        assertEquals(listOf("a" to false, "b" to true, "c`d" to false), mentorSegments("a`b`c`d"))
    }
}

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
