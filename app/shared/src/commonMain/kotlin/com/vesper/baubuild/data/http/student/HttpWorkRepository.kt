package com.vesper.baubuild.data.http.student

import com.vesper.baubuild.core.model.Membership
import com.vesper.baubuild.core.model.MembershipStatus
import com.vesper.baubuild.core.model.Project
import com.vesper.baubuild.core.model.ProjectTask
import com.vesper.baubuild.core.model.ReviewStage
import com.vesper.baubuild.core.model.ReviewStageState
import com.vesper.baubuild.core.model.ReviewStatus
import com.vesper.baubuild.core.model.Reviewer
import com.vesper.baubuild.core.model.TaskStatus
import com.vesper.baubuild.core.model.Workspace
import com.vesper.baubuild.core.network.apiCall
import com.vesper.baubuild.data.http.student.dto.ChangesDto
import com.vesper.baubuild.data.http.student.dto.MyWorkDto
import com.vesper.baubuild.data.http.student.dto.PageDto
import com.vesper.baubuild.data.http.student.dto.SubmissionDto
import com.vesper.baubuild.data.http.student.dto.TaskDto
import com.vesper.baubuild.data.http.student.dto.TaskPatchDto
import com.vesper.baubuild.data.http.student.dto.Wire
import com.vesper.baubuild.data.repository.ProjectRepository
import com.vesper.baubuild.data.repository.WorkRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * [WorkRepository] on the BAU BUILD backend.
 *
 * - [myWork]: `GET /v1/me/work` → `in_progress` (member → [MembershipStatus.In]) and `awaiting_approval`
 *   ([MembershipStatus.WaitingForApproval], reviewing by `reviewer_label`). Projects there are slim (no
 *   difficulty / due date / description); the workspace re-reads the full project.
 * - [workspace]: `GET /v1/projects/{id}` + `GET /v1/projects/{id}/tasks` + `GET /v1/projects/{id}/changes`
 *   in parallel (tasks/changes → 403 `not_a_member` until the user is a member).
 * - [reviews]: `GET /v1/me/submissions` (each item already carries its `steps` timeline, the same shape as
 *   `GET /v1/submissions/{id}`), newest first, plus each distinct project.
 * - [updateTaskStatus]: `PATCH /v1/tasks/{id}` `{"status": "todo" | "in_progress" | "done"}` (404 `not_found`
 *   when the task isn't the caller's, 400 `invalid_body`).
 * - [updateTaskCriteria]: `PATCH /v1/tasks/{id}` `{"criteria_done": [indexes]}` → the full task.
 */
class HttpWorkRepository(
    private val client: HttpClient,
    private val now: NowProvider = systemNow,
    private val projects: ProjectRepository = HttpProjectRepository(client, now),
) : WorkRepository {

    override suspend fun myWork(): List<Membership> {
        val work: MyWorkDto = apiCall { client.get("v1/me/work").body() }
        return work.inProgress.map { Membership(it.toProject(), MembershipStatus.In) } +
            work.awaitingApproval.map {
                Membership(it.toProject(), MembershipStatus.WaitingForApproval, reviewingBy = it.reviewerLabel ?: "pending")
            }
    }

    override suspend fun workspace(projectId: String): Workspace = coroutineScope {
        val id = projectId.encodeURLPathPart()
        val project = async { projects.project(projectId) }
        val tasks = async { apiCall { client.get("v1/projects/$id/tasks").body<PageDto<TaskDto>>() } }
        val changes = async { apiCall { client.get("v1/projects/$id/changes").body<ChangesDto>() } }
        val n = now()
        val taskList = tasks.await().items.map { it.toDomain(n) }
        val changeList = changes.await()
        val p = project.await()
        Workspace(
            project = p.copy(
                taskCount = taskList.size,
                repoFullName = p.repoFullName ?: changeList.repoFullName?.trim()?.trim('/')?.takeIf { '/' in it },
            ),
            tasks = taskList,
            changes = changeList.toDomain(),
        )
    }

    override suspend fun reviews(): List<ReviewStatus> = coroutineScope {
        val submissions = apiCall { client.get("v1/me/submissions").body<PageDto<SubmissionDto>>() }.items
        val byId = submissions.map { it.projectId }.distinct()
            .map { id -> async { id to projects.project(id) } }
            .awaitAll().toMap()
        submissions.mapNotNull { s -> byId[s.projectId]?.let { reviewOf(s, it) } }
    }

    override suspend fun updateTaskStatus(projectId: String, taskId: String, status: TaskStatus): ProjectTask {
        val task: TaskDto = apiCall {
            client.patch("v1/tasks/${taskId.encodeURLPathPart()}") {
                contentType(ContentType.Application.Json)
                setBody(TaskPatchDto(status = Wire.taskStatus(status)))
            }.body()
        }
        return task.toDomain(now())
    }

    override suspend fun updateTaskCriteria(projectId: String, taskId: String, criteriaDone: Set<Int>): ProjectTask {
        val task: TaskDto = apiCall {
            client.patch("v1/tasks/${taskId.encodeURLPathPart()}") {
                contentType(ContentType.Application.Json)
                setBody(TaskPatchDto(criteriaDone = criteriaDone.sorted()))
            }.body()
        }
        return task.toDomain(now())
    }
}

/** Builds the four-stage Review timeline from a submission's `steps`. */
internal fun reviewOf(s: SubmissionDto, project: Project): ReviewStatus {
    val branch = s.step("pushed")?.branch.orEmpty()
    val ai = s.step("ai_review")
    val lead = s.step("lead_decision")
    val bounty = s.step("bounty")
    val decider = if (project.reviewer == Reviewer.Professor) "Professor" else "Dev Lead"
    val deciderLower = decider.lowercase()
    val amount = bounty?.amount ?: project.bountyUsd
    val paid = bounty?.paidAt != null
    val reviewerName = lead?.reviewer?.name

    val passes = ai?.findings.orEmpty().filter { it.kind == "pass" }.map { it.text }
    val warnings = ai?.findings.orEmpty().filter { it.kind != "pass" }.map { it.text }
    val aiDone = ai?.status == "complete" || ai?.status == null && ai != null

    val leadStage = when (lead?.status) {
        "approved" -> ReviewStage(
            "$decider Approved",
            listOfNotNull(reviewerName?.let { "Approved by $it." }, lead.comment).joinToString(" "),
            ReviewStageState.Done,
        )
        "sent_back" -> ReviewStage(
            "Changes Requested",
            listOfNotNull(reviewerName?.let { "$it sent the branch back." }, lead.comment).joinToString(" ")
                .ifBlank { "The $deciderLower sent the branch back with notes." },
            ReviewStageState.Active,
            note = lead.comment,
        )
        else -> ReviewStage(
            "$decider Is Deciding",
            "A vetted $deciderLower is reading the AI summary and your diff.",
            if (aiDone) ReviewStageState.Active else ReviewStageState.Pending,
        )
    }
    val bountyStage = if (paid) {
        ReviewStage("Bounty and Record", "\$$amount was added to your record and your score.", ReviewStageState.Done)
    } else {
        ReviewStage(
            "Bounty and Record",
            "\$$amount is paid and the project joins your verified record once approved.",
            ReviewStageState.Pending,
        )
    }
    val stages = listOf(
        ReviewStage("Pushed to GitHub", branch, ReviewStageState.Done),
        ReviewStage(
            if (aiDone) "AI Review Complete" else "AI Review",
            passes.joinToString(" ").ifBlank { if (aiDone) "No blocking findings." else "The AI review is running." },
            if (aiDone) ReviewStageState.Done else ReviewStageState.Active,
            note = warnings.joinToString(" ").ifBlank { null },
        ),
        leadStage,
        bountyStage,
    )
    val headline = when (lead?.status) {
        "approved" -> if (paid) "Approved. \$$amount was added to your record." else "Approved. The bounty follows once every change is approved."
        "sent_back" -> "Your branch was sent back with notes."
        else -> "Your fix is with the $deciderLower."
    }
    return ReviewStatus(project = project, headline = headline, branch = branch, stages = stages, diffUrl = s.diffUrl)
}
