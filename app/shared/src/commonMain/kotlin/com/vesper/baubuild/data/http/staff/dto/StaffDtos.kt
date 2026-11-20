package com.vesper.baubuild.data.http.staff.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire shapes for the staff side of the BAU BUILD API (contract: docs/openapi.json + docs/index.html §7).
// Everything optional has a default so a newer/older backend never breaks decoding; unknown keys are ignored.

/**
 * One inquiry as returned by `POST /v1/inquiries`, `GET /v1/inquiries/mine` (inside `items`),
 * `GET|PATCH /v1/inquiries/{id}`.
 *
 * `status` is kept as a raw string (submitted → approved → published → closed today) and mapped leniently in
 * [toStatus]. `due_at` is an ISO-8601 instant (e.g. `2026-12-03T23:59:59Z`).
 */
@Serializable
data class InquiryDto(
    val id: String,
    val title: String = "",
    val description: String = "",
    val department: String = "",
    /** easy | medium | hard */
    val difficulty: String = "medium",
    /** Whole USD, 0..500. */
    val bounty: Int = 0,
    @SerialName("is_urgent") val isUrgent: Boolean = false,
    @SerialName("due_at") val dueAt: String? = null,
    @SerialName("repo_url") val repoUrl: String? = null,
    @SerialName("min_score") val minScore: Int = 0,
    /** everyone | approval */
    val joining: String = "approval",
    /** ai_then_lead | professor */
    @SerialName("reviewer_type") val reviewerType: String = "ai_then_lead",
    val status: String = "submitted",
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    /** Newer backend: `draft | open | in_progress | delivered | cancelled` (derived from the inquiry + its project). */
    val stage: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("cancelled_at") val cancelledAt: String? = null,
    @SerialName("wasted_usd") val wastedUsd: Int = 0,
)

/** `GET /v1/inquiries/mine` → `{ "items": [...] }`. */
@Serializable
data class InquiryListDto(val items: List<InquiryDto> = emptyList())

/**
 * Body of `POST /v1/inquiries`. Every field here is required by the backend except `due_at`/`repo_url`
 * (no defaults on purpose: the client Json does not encode default values).
 */
@Serializable
data class CreateInquiryRequest(
    val title: String,
    val description: String,
    val department: String,
    val difficulty: String,
    val bounty: Int,
    @SerialName("is_urgent") val isUrgent: Boolean,
    @SerialName("due_at") val dueAt: String?,
    @SerialName("min_score") val minScore: Int,
    val joining: String,
    @SerialName("reviewer_type") val reviewerType: String,
)

/** `GET /v1/me` (subset the staff side needs). `classes` are class codes, e.g. `CMPS433`. */
@Serializable
data class MeDto(
    val id: String = "",
    val name: String = "",
    val email: String? = null,
    /** student | staff | lead | admin */
    val role: String = "",
    val department: String? = null,
    val classes: List<String> = emptyList(),
    /** "Professor", "Lecturer"… (staff only). */
    val title: String? = null,
    @SerialName("display_name") val displayName: String? = null,
)

/** `GET /v1/staff/overview`. */
@Serializable
data class StaffOverviewDto(
    @SerialName("raised_usd") val raisedUsd: Int = 0,
    @SerialName("wasted_usd") val wastedUsd: Int = 0,
    val open: Int = 0,
    @SerialName("in_progress") val inProgress: Int = 0,
    val delivered: Int = 0,
)

/** One idea from `GET /v1/world-projects` (`{items:[{id,title,summary,organization,project_types,url}]}`). */
@Serializable
data class WorldProjectDto(
    val id: String = "",
    val title: String = "",
    val summary: String? = null,
    val description: String? = null,
    val organization: String = "",
    @SerialName("project_types") val projectTypes: List<String> = emptyList(),
    /** Older proposal of the same field. */
    val types: List<String> = emptyList(),
    val url: String? = null,
)

@Serializable
data class WorldProjectListDto(val items: List<WorldProjectDto> = emptyList())
