package com.vesper.baubuild.data.http.student.dto

import com.vesper.baubuild.core.model.ChangeStatus
import com.vesper.baubuild.core.model.CodeChange
import com.vesper.baubuild.core.model.Difficulty
import com.vesper.baubuild.core.model.JoinPolicy
import com.vesper.baubuild.core.model.Project
import com.vesper.baubuild.core.model.ProjectTask
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.time.Instant

/** `GET /v1/me/work`. */
@Serializable
internal data class MyWorkDto(
    @SerialName("in_progress") val inProgress: List<WorkItemDto> = emptyList(),
    @SerialName("awaiting_approval") val awaitingApproval: List<PendingWorkItemDto> = emptyList(),
)

@Serializable
internal data class WorkItemDto(
    val id: String,
    val title: String = "",
    val department: String = "",
    val bounty: Int = 0,
    @SerialName("task_count") val taskCount: Int = 0,
    @SerialName("open_task_count") val openTaskCount: Int = 0,
) {
    /** The backend sends a slim project here; fields it lacks keep neutral defaults. */
    fun toProject() = slimProject(id, title, department, bounty, JoinPolicy.Open).copy(taskCount = taskCount)
}

@Serializable
internal data class PendingWorkItemDto(
    val id: String,
    val title: String = "",
    /** `pending_approval`. */
    val status: String? = null,
    @SerialName("reviewer_label") val reviewerLabel: String? = null,
    val bounty: Int = 0,
    val department: String = "",
) {
    fun toProject() = slimProject(id, title, department, bounty, JoinPolicy.ApprovalRequired)
}

private fun slimProject(id: String, title: String, department: String, bounty: Int, join: JoinPolicy) = Project(
    id = id, title = title, owner = department, summary = "", description = "", bountyUsd = bounty,
    difficulty = Difficulty.Easy, minScore = 0, joinPolicy = join, dueInDays = 0, repoFullName = null,
)

/** `GET /v1/projects/{id}/tasks` items and `PATCH /v1/tasks/{id}` response. */
@Serializable
internal data class TaskDto(
    val id: String,
    @SerialName("project_id") val projectId: String? = null,
    val title: String = "",
    /** `todo` | `in_progress` | `done`. */
    val status: String? = null,
    /** `assigned` | `backlog`. */
    val lane: String? = null,
    @SerialName("due_at") val dueAt: String? = null,
) {
    fun toDomain(now: Instant) = ProjectTask(
        id = id,
        title = title,
        status = Wire.taskStatus(status, lane),
        dueInDays = dueAt?.let { Wire.dueInDays(it, now) },
        // The tasks endpoint only returns the caller's own tasks.
        assignedToMe = true,
        dueDate = Wire.isoDate(dueAt),
    )
}

@Serializable
internal data class TaskPatchDto(val status: String)

/** `GET /v1/projects/{id}/changes`. */
@Serializable
internal data class ChangesDto(
    val connected: Boolean = false,
    val repo: String? = null,
    val branches: List<BranchDto> = emptyList(),
    @SerialName("github_commits") val githubCommits: List<GitHubCommitDto> = emptyList(),
) {
    fun toDomain(): List<CodeChange> {
        val submitted = branches.flatMap { branch ->
            branch.commits.map { CodeChange(it.id, it.message ?: branch.name, branch.name, Wire.changeStatus(it.state)) }
        }
        val pushed = githubCommits.mapNotNull { commit ->
            val sha = commit.sha ?: return@mapNotNull null
            // GitHub lists the default branch's commits; the response doesn't name the branch.
            CodeChange(sha, commit.message?.lineSequence()?.firstOrNull().orEmpty().ifBlank { sha.take(7) }, sha.take(7), ChangeStatus.Pushed)
        }
        return submitted + pushed
    }
}

@Serializable
internal data class BranchDto(
    val name: String = "",
    val commits: List<BranchCommitDto> = emptyList(),
)

/** One submission of a branch. */
@Serializable
internal data class BranchCommitDto(
    val id: String,
    val message: String? = null,
    /** `pending` | `approved` | `sent_back`. */
    val state: String? = null,
    @SerialName("diff_url") val diffUrl: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
internal data class GitHubCommitDto(
    val sha: String? = null,
    val message: String? = null,
    val url: String? = null,
)

/** `GET /v1/me/submissions` items and `GET /v1/submissions/{id}`. */
@Serializable
internal data class SubmissionDto(
    val id: String,
    @SerialName("project_id") val projectId: String,
    @SerialName("diff_url") val diffUrl: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val steps: List<SubmissionStepDto> = emptyList(),
) {
    fun step(name: String) = steps.firstOrNull { it.step == name }
    val leadStatus: String? get() = step("lead_decision")?.status
    val paid: Boolean get() = step("bounty")?.paidAt != null
}

/**
 * One timeline step. `step` is `pushed` (branch), `ai_review` (status, findings), `lead_decision`
 * (status `pending | approved | sent_back`, reviewer, comment) or `bounty` (amount, paid_at).
 */
@Serializable
internal data class SubmissionStepDto(
    val step: String = "",
    val branch: String? = null,
    val status: String? = null,
    val findings: List<FindingDto> = emptyList(),
    val reviewer: PersonDto? = null,
    val comment: String? = null,
    val amount: Int? = null,
    @SerialName("paid_at") val paidAt: String? = null,
)

@Serializable
internal data class FindingDto(
    /** `pass` | `warn` | … */
    val kind: String? = null,
    val text: String = "",
)

@Serializable
internal data class PersonDto(
    val id: String? = null,
    val name: String? = null,
    val initials: String? = null,
)

/** `GET /v1/notifications` items. */
@Serializable
internal data class NotificationDto(
    val id: String,
    val text: String = "",
    val read: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
)

/** `GET /v1/notifications/unread-count` and `POST /v1/notifications/read` → `{"unread": n}`. */
@Serializable
internal data class UnreadDto(val unread: Int = 0)

/** Body of `POST /v1/notifications/read`: either `{"ids": [...]}` or `{"all": true}`. */
@Serializable
internal data class MarkReadDto(val ids: List<String>? = null, val all: Boolean? = null)

/** `GET /v1/home`. */
@Serializable
internal data class HomeDto(
    @SerialName("greeting_name") val greetingName: String = "",
    val urgent: List<UrgentCardDto> = emptyList(),
    @SerialName("difficulty_counts") val difficultyCounts: Map<String, Int> = emptyMap(),
    @SerialName("open_projects") val openProjects: List<ProjectDto> = emptyList(),
)

/** `GET /v1/search?q=`. */
@Serializable
internal data class SearchDto(
    val projects: List<ProjectDto> = emptyList(),
    val classes: List<ClassDto> = emptyList(),
    val inquiries: List<InquiryDto> = emptyList(),
)

/** `GET/PATCH /v1/me`. */
@Serializable
internal data class UserDto(
    val id: String,
    val name: String = "",
    val initials: String? = null,
    val avatar: JsonElement? = null,
    val bio: String? = null,
    /** `student` | `staff` | `lead` | `admin`. */
    val role: String? = null,
    val department: String? = null,
    val skills: List<String> = emptyList(),
    val classes: List<String> = emptyList(),
    val email: String? = null,
)

/** Body of `PATCH /v1/me` (the backend accepts only these two; omitted keys are left unchanged). */
@Serializable
internal data class UpdateMeDto(
    val bio: String? = null,
    val skills: List<String>? = null,
)
