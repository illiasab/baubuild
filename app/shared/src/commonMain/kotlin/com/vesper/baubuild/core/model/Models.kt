package com.vesper.baubuild.core.model

import kotlinx.serialization.Serializable

/** Who the signed-in person is, derived from their email domain (see [AccountPolicy]). */
@Serializable
enum class AccountKind { BauStudent, BauStaff, External }

/** In-app role. Students can become Leads through the Lead track. */
@Serializable
enum class Role { Student, Lead, Staff }

@Serializable
data class User(
    val id: String,
    val displayName: String,
    val email: String,
    val kind: AccountKind,
    val role: Role,
    val bio: String = "",
    val score: Int = 0,
    val earningsUsd: Int = 0,
    val approvedProjects: Int = 0,
    val skills: List<String> = emptyList(),
    val classCodes: List<String> = emptyList(),
    /** Profile photo URL (backend-hosted). Null → initials avatar. */
    val avatarUrl: String? = null,
    val githubUsername: String? = null,
    /** Staff job title ("Professor", "Lecturer"…); null for students or when unset. */
    val title: String? = null,
    val department: String? = null,
    /** Backend roles: primary role plus extras such as `interviewer`, `senior_engineer`. */
    val roles: List<String> = emptyList(),
) {
    val firstName: String get() = displayName.substringBefore(' ')
    val initials: String
        get() = displayName.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
}

/** Score tiers shown on the profile score bar (0..500). */
enum class ScoreTier(val label: String, val min: Int) {
    Rookie("ROOKIE", 0), Builder("BUILDER", 100), Skilled("SKILLED", 200), Expert("EXPERT", 300), Honored("HONORED", 400);

    companion object {
        const val MAX_SCORE = 500
        fun of(score: Int): ScoreTier = entries.last { score >= it.min }
    }
}

@Serializable
enum class Difficulty { Easy, Medium, Hard }

/** How a student gets into a project. */
@Serializable
enum class JoinPolicy {
    /** "Everyone welcome" — tap I'm In and you are in. */
    Open,
    /** Requires approval (professor / lead) — tap Request. */
    ApprovalRequired,
}

@Serializable
enum class Reviewer { AiThenDevLead, Professor, DevLead }

@Serializable
data class Project(
    val id: String,
    val title: String,
    /** Owning unit, e.g. "Library", "Student Affairs", or a class code like "CMPS433". */
    val owner: String,
    val summary: String,
    val description: String,
    val bountyUsd: Int,
    val difficulty: Difficulty,
    val minScore: Int,
    val joinPolicy: JoinPolicy,
    val dueInDays: Int,
    val repoName: String? = null,
    val repoConnected: Boolean = false,
    /** "owner/name" on GitHub (e.g. "bau-build/library-booking"); used by the in-app code workspace. */
    val repoFullName: String? = repoName?.let { "bau-build/$it" },
    val reviewer: Reviewer = Reviewer.AiThenDevLead,
    /** Set when this project belongs to a class the student is enrolled in. */
    val classCode: String? = null,
    val taskCount: Int = 0,
    /** The viewer's membership from the backend (`membership_status`): null = not a member and no request. */
    val membership: MembershipStatus? = null,
) {
    /** `https://github.com/owner/name` when the repo is known. */
    val repoUrl: String? get() = repoFullName?.trim()?.trim('/')?.takeIf { '/' in it }?.let { "https://github.com/$it" }

    /** `https://github.com/owner/name.git`. */
    val cloneUrl: String? get() = repoUrl?.let { "$it.git" }
}

/** A request from BAU staff (@bau.edu) for a project to be built. Urgent ones appear at the top of Projects. */
@Serializable
data class Inquiry(
    val id: String,
    val title: String,
    val requestedBy: String,
    val department: String,
    val bountyUsd: Int,
    val difficulty: Difficulty,
    val isUrgent: Boolean,
    val minScore: Int = 0,
    /** Project created from this inquiry, if staff/admin already turned it into one. */
    val projectId: String? = null,
)

@Serializable
data class NewInquiry(
    val title: String,
    val description: String,
    val department: String,
    val isUrgent: Boolean,
    val suggestedBountyUsd: Int? = null,
)

@Serializable
data class BauClass(
    val code: String,
    val name: String,
    val professor: String,
    val term: String,
    val openProjectCount: Int,
)

/** A project the student is part of (My Work). */
@Serializable
data class Membership(
    val project: Project,
    val status: MembershipStatus,
    val reviewingBy: String? = null,
)

@Serializable
enum class MembershipStatus { In, WaitingForApproval, InReview, Approved }

@Serializable
enum class TaskStatus { Backlog, ToDo, InProgress, Done }

@Serializable
data class ProjectTask(
    val id: String,
    val title: String,
    val status: TaskStatus,
    val dueInDays: Int? = null,
    val assignedToMe: Boolean = true,
    /** What to do and why. */
    val description: String = "",
    /** "Done when…" checklist the reviewer checks against. */
    val acceptanceCriteria: List<String> = emptyList(),
    /** Deadline as an ISO date (yyyy-MM-dd); [dueInDays] is the relative form shown in lists. */
    val dueDate: String? = null,
    val priority: TaskPriority = TaskPriority.Medium,
    val resources: List<TaskResource> = emptyList(),
    /** Indexes into [acceptanceCriteria] the student ticked (persisted on the backend). */
    val criteriaDone: Set<Int> = emptySet(),
)

@Serializable
enum class TaskPriority { Low, Medium, High }

@Serializable
data class TaskResource(val label: String, val url: String)

@Serializable
enum class ChangeStatus { Approved, WithDevLead, ChangesRequested, Pushed }

@Serializable
data class CodeChange(
    val id: String,
    val title: String,
    val branch: String,
    val status: ChangeStatus,
    /** Commit sha, when the client sent one with the submission (or GitHub listed it). */
    val sha: String? = null,
    /** GitHub link for this change (commit or compare view). */
    val url: String? = null,
)

@Serializable
data class Workspace(
    val project: Project,
    val tasks: List<ProjectTask>,
    val changes: List<CodeChange>,
)

@Serializable
enum class ReviewStageState { Done, Active, Pending }

@Serializable
data class ReviewStage(
    val title: String,
    val detail: String,
    val state: ReviewStageState,
    val note: String? = null,
)

@Serializable
data class ReviewStatus(
    val project: Project,
    val headline: String,
    val branch: String,
    val stages: List<ReviewStage>,
    val diffUrl: String?,
)
