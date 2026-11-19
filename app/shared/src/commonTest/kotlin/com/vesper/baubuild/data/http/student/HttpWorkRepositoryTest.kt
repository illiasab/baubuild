package com.vesper.baubuild.data.http.student

import com.vesper.baubuild.core.model.ChangeStatus
import com.vesper.baubuild.core.model.MembershipStatus
import com.vesper.baubuild.core.model.ReviewStageState
import com.vesper.baubuild.core.model.TaskStatus
import com.vesper.baubuild.core.network.ApiException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpWorkRepositoryTest {
    private val backend = StudentMockBackend()
    private val repo = HttpWorkRepository(backend.client, { TEST_NOW })

    @Test
    fun myWorkMapsBothSections() = runTest {
        backend.on("GET", "/v1/me/work", body = Fixtures.MY_WORK)
        val work = repo.myWork()
        assertEquals(2, work.size)
        assertEquals(MembershipStatus.In, work[0].status)
        assertEquals("Library", work[0].project.owner)
        assertEquals(3, work[0].project.taskCount)
        assertEquals(MembershipStatus.WaitingForApproval, work[1].status)
        assertEquals("dev lead", work[1].reviewingBy)
        assertEquals(35, work[1].project.bountyUsd)
    }

    @Test
    fun workspaceCombinesProjectTasksAndChanges() = runTest {
        backend.on("GET", "/v1/projects/library", body = Fixtures.projectDetail(membership = "joined"))
        backend.on("GET", "/v1/projects/library/tasks", body = Fixtures.TASKS)
        backend.on("GET", "/v1/projects/library/changes", body = Fixtures.CHANGES)

        val ws = repo.workspace("library")

        assertEquals("library", ws.project.id)
        assertEquals(5, ws.project.taskCount)
        assertEquals(
            listOf(TaskStatus.InProgress, TaskStatus.Backlog, TaskStatus.ToDo, TaskStatus.Done, TaskStatus.ToDo),
            ws.tasks.map { it.status },
        )
        assertEquals("2026-11-22", ws.tasks[0].dueDate)
        assertEquals(3, ws.tasks[0].dueInDays)
        assertNull(ws.tasks[2].dueInDays)
        assertTrue(ws.tasks.all { it.assignedToMe })
        assertEquals(
            listOf(ChangeStatus.ChangesRequested, ChangeStatus.Approved, ChangeStatus.WithDevLead, ChangeStatus.Pushed),
            ws.changes.map { it.status },
        )
        assertEquals("fix-date-validation", ws.changes[0].branch)
        assertEquals("Reject past dates", ws.changes[3].title)
    }

    @Test
    fun workspaceForNonMemberThrowsNotAMember() = runTest {
        backend.on("GET", "/v1/projects/library", body = Fixtures.projectDetail())
        backend.on("GET", "/v1/projects/library/tasks", HttpStatusCode.Forbidden, Fixtures.error("not_a_member"))
        backend.on("GET", "/v1/projects/library/changes", HttpStatusCode.Forbidden, Fixtures.error("not_a_member"))
        assertEquals("not_a_member", assertFailsWith<ApiException> { repo.workspace("library") }.code)
    }

    @Test
    fun reviewsBuildTimelineStages() = runTest {
        backend.on(
            "GET", "/v1/me/submissions",
            body = Fixtures.page(
                listOf(
                    Fixtures.submission("s1", "library", lead = "pending", paidAt = null),
                    Fixtures.submission("s2", "library", lead = "approved", paidAt = "2026-11-19T09:00:00Z"),
                    Fixtures.submission("s3", "library", lead = "sent_back", paidAt = null, comment = "Add a test."),
                ),
                next = null,
            ),
        )
        backend.on("GET", "/v1/projects/library", body = Fixtures.projectDetail(membership = "joined"))

        val reviews = repo.reviews()

        assertEquals(1, backend.callsTo("GET", "/v1/projects/library").size) // one read per distinct project
        assertEquals(3, reviews.size)
        val pending = reviews[0]
        assertEquals("fix-date-validation", pending.branch)
        assertEquals("Your fix is with the dev lead.", pending.headline)
        assertEquals(
            listOf(ReviewStageState.Done, ReviewStageState.Done, ReviewStageState.Active, ReviewStageState.Pending),
            pending.stages.map { it.state },
        )
        assertEquals("fix-date-validation", pending.stages[0].detail)
        assertEquals("Worth a look: announce the error to screen readers.", pending.stages[1].note)
        assertEquals("https://github.com/library-booking/compare/fix-date-validation", pending.diffUrl)

        val approved = reviews[1]
        assertTrue(approved.stages.all { it.state == ReviewStageState.Done })
        assertTrue(approved.headline.startsWith("Approved. \$18"))

        val sentBack = reviews[2]
        assertEquals("Changes Requested", sentBack.stages[2].title)
        assertEquals("Add a test.", sentBack.stages[2].note)
    }

    @Test
    fun updateTaskStatusPatchesTask() = runTest {
        backend.on(
            "PATCH", "/v1/tasks/t2",
            body = """{"id": "t2", "project_id": "library", "title": "Add a test", "status": "in_progress", "lane": "backlog", "due_at": null}""",
        )
        val task = repo.updateTaskStatus("library", "t2", TaskStatus.InProgress)
        assertEquals(TaskStatus.InProgress, task.status)
        assertEquals("""{"status":"in_progress"}""", backend.calls.single().body)

        backend.on("PATCH", "/v1/tasks/t9", HttpStatusCode.NotFound, Fixtures.error("not_found", "That task is not yours."))
        val e = assertFailsWith<ApiException> { repo.updateTaskStatus("library", "t9", TaskStatus.Done) }
        assertEquals(404, e.status)
        assertEquals("not_found", e.code)
    }

    @Test
    fun backlogIsSentAsTodo() = runTest {
        backend.on(
            "PATCH", "/v1/tasks/t1",
            body = """{"id": "t1", "title": "x", "status": "todo", "lane": "assigned"}""",
        )
        repo.updateTaskStatus("library", "t1", TaskStatus.Backlog)
        assertEquals("""{"status":"todo"}""", backend.calls.single().body)
    }
}
