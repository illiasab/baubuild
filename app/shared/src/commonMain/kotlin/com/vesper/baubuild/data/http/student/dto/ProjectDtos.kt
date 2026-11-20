package com.vesper.baubuild.data.http.student.dto

import com.vesper.baubuild.core.model.BauClass
import com.vesper.baubuild.core.model.Inquiry
import com.vesper.baubuild.core.model.Project
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Project as the backend sends it. The card form (`GET /v1/home`, `GET /v1/search`) has only the top block;
 * the detail form (`GET /v1/projects`, `/v1/projects/{id}`, `/v1/classes/{id}/projects`) adds the rest.
 * Every field is optional so either form decodes.
 */
@Serializable
internal data class ProjectDto(
    val id: String,
    val title: String = "",
    val department: String = "",
    val bounty: Int = 0,
    val difficulty: String? = null,
    @SerialName("min_score") val minScore: Int? = null,
    @SerialName("min_score_label") val minScoreLabel: String? = null,
    /** ISO-8601 UTC, e.g. `2026-11-21T10:00:00Z`. */
    @SerialName("due_at") val dueAt: String? = null,
    val urgent: Boolean = false,
    @SerialName("art_key") val artKey: String? = null,
    /** `open` | … */
    val status: String? = null,
    // Detail-only fields.
    val description: String? = null,
    @SerialName("class_id") val classId: String? = null,
    val repo: RepoDto? = null,
    /** `everyone` | `approval`. */
    val joining: String? = null,
    /** `ai_then_lead` | `professor`. */
    @SerialName("reviewed_by") val reviewedBy: String? = null,
    /** Human label, e.g. "dev lead", "professor of CMPS433". */
    @SerialName("reviewer_label") val reviewerLabel: String? = null,
    @SerialName("is_bookmarked") val isBookmarked: Boolean = false,
    @SerialName("my_membership") val myMembership: MyMembershipDto? = null,
    val eligibility: EligibilityDto? = null,
    // Newer backend fields (all optional).
    val summary: String? = null,
    @SerialName("repo_full_name") val repoFullName: String? = null,
    @SerialName("class_code") val classCode: String? = null,
    /** `none` | `member` | `pending_approval`. */
    @SerialName("membership_status") val membershipStatus: String? = null,
) {
    /** Not joined and not requested (those projects live in My Work). */
    val isOpenToViewer: Boolean
        get() = myMembership == null && (membershipStatus == null || membershipStatus == "none")

    /**
     * [classCodes] maps a backend class id to its display code (from `/v1/me/classes` or the class payload);
     * unknown ids are shown as-is.
     */
    fun toDomain(now: Instant, classCodes: Map<String, String> = emptyMap()): Project {
        val repoName = repo?.name?.takeIf { it.isNotBlank() }
        val fullName = (repoFullName ?: repo?.fullName)?.trim()?.trim('/')?.takeIf { '/' in it }
            // Only a real "owner/name" is usable with GitHub; a bare name has no owner, so no guess here.
            ?: repoName?.takeIf { '/' in it }
        val text = description.orEmpty()
        return Project(
            id = id,
            title = title,
            owner = department,
            summary = summary?.takeIf { it.isNotBlank() } ?: Wire.summaryOf(text),
            description = text,
            bountyUsd = bounty,
            difficulty = Wire.difficulty(difficulty),
            minScore = minScore ?: Wire.minScoreFromLabel(minScoreLabel),
            joinPolicy = Wire.joinPolicy(joining),
            dueInDays = Wire.dueInDays(dueAt, now),
            repoName = (repoName ?: fullName)?.substringAfterLast('/'),
            repoConnected = repo?.viewerGithubLinked ?: (repo?.connected == true),
            repoFullName = fullName,
            reviewer = Wire.reviewer(reviewedBy),
            classCode = classCode?.takeIf { it.isNotBlank() } ?: classId?.let { classCodes[it] ?: it },
            membership = Wire.membership(membershipStatus, myMembership?.status),
        )
    }
}

@Serializable
internal data class RepoDto(
    /** `github`. */
    val provider: String? = null,
    val name: String? = null,
    /** `owner/name`. */
    @SerialName("full_name") val fullName: String? = null,
    /** True when the signed-in user has GitHub connected (not a property of the repository). */
    val connected: Boolean = false,
    @SerialName("viewer_github_linked") val viewerGithubLinked: Boolean? = null,
)

@Serializable
internal data class MyMembershipDto(
    /** `joined` | `pending_approval`. */
    val status: String? = null,
)

@Serializable
internal data class EligibilityDto(
    val eligible: Boolean = true,
    /** `payment_required` | `class_not_enrolled` | `score_too_low` | null. */
    val reason: String? = null,
)

/** Generic cursor page: `{"items": [...], "next_cursor": "20" | null}`. */
@Serializable
internal data class PageDto<T>(
    val items: List<T> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
)

/** `GET /v1/classes/{id}/projects`. */
@Serializable
internal data class ClassProjectsDto(
    @SerialName("class") val bauClass: ClassDto? = null,
    val items: List<ProjectDto> = emptyList(),
)

/** `POST /v1/projects/{id}/join` → `{"result": "joined" | "pending_approval"}`. */
@Serializable
internal data class JoinResultDto(val result: String? = null)

@Serializable
internal data class ClassDto(
    val id: String,
    val code: String = "",
    val name: String = "",
    val term: String = "",
    val instructor: String = "",
    @SerialName("open_project_count") val openProjectCount: Int = 0,
) {
    fun toDomain() = BauClass(
        code = code.ifBlank { id },
        name = name,
        professor = instructor,
        term = term,
        openProjectCount = openProjectCount,
    )
}

/** Urgent card (`GET /v1/inquiries/urgent`, `GET /v1/home` `urgent`). Its id is the published project's id. */
@Serializable
internal data class UrgentCardDto(
    val id: String,
    val title: String = "",
    val bounty: Int = 0,
    val difficulty: String? = null,
    @SerialName("min_score_label") val minScoreLabel: String? = null,
    val department: String = "",
    @SerialName("art_key") val artKey: String? = null,
) {
    fun toDomain() = Inquiry(
        id = id,
        title = title,
        requestedBy = department,
        department = department,
        bountyUsd = bounty,
        difficulty = Wire.difficulty(difficulty),
        isUrgent = true,
        minScore = Wire.minScoreFromLabel(minScoreLabel),
        projectId = id,
    )
}

/** Staff inquiry (`POST /v1/inquiries` response, `GET /v1/search` `inquiries`). */
@Serializable
internal data class InquiryDto(
    val id: String,
    val title: String = "",
    val description: String = "",
    val department: String = "",
    val difficulty: String? = null,
    val bounty: Int = 0,
    @SerialName("is_urgent") val isUrgent: Boolean = false,
    @SerialName("due_at") val dueAt: String? = null,
    @SerialName("repo_url") val repoUrl: String? = null,
    @SerialName("min_score") val minScore: Int = 0,
    val joining: String? = null,
    @SerialName("reviewer_type") val reviewerType: String? = null,
    /** `submitted` | `approved` | `published`. */
    val status: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    fun toDomain(requestedBy: String = department) = Inquiry(
        id = id,
        title = title,
        requestedBy = requestedBy,
        department = department,
        bountyUsd = bounty,
        difficulty = Wire.difficulty(difficulty),
        isUrgent = isUrgent,
        minScore = minScore,
        projectId = projectId,
    )
}

/** Body of `POST /v1/inquiries`. All keys are required by the backend. */
@Serializable
internal data class NewInquiryDto(
    val title: String,
    val description: String,
    val department: String,
    val difficulty: String,
    val bounty: Int,
    @SerialName("is_urgent") val isUrgent: Boolean,
    @SerialName("min_score") val minScore: Int,
    val joining: String,
    @SerialName("reviewer_type") val reviewerType: String,
)
