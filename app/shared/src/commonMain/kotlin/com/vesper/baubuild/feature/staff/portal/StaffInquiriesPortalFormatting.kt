package com.vesper.baubuild.feature.staff.portal

import com.vesper.baubuild.core.model.InquiryPriority
import com.vesper.baubuild.core.model.ProjectFor
import com.vesper.baubuild.core.model.ProjectType
import com.vesper.baubuild.core.model.StaffInquiry
import com.vesper.baubuild.core.model.StaffOverview
import com.vesper.baubuild.feature.staff.inquiries.InquiryFilter
import com.vesper.baubuild.feature.staff.inquiries.InquirySection
import com.vesper.baubuild.feature.staff.inquiries.detail.summaryOf
import com.vesper.baubuild.feature.staff.inquiries.formatShortDate

// Pure text helpers for the desktop staff portal tables and detail header (Web portal design).

/** Short platform names used in the portal tables: "iOS · Android · Website". */
internal val ProjectType.portalShortLabel: String
    get() = when (this) {
        ProjectType.IosApp -> "iOS"
        ProjectType.AndroidApp -> "Android"
        else -> label
    }

/** TYPE column: "iOS · Android · Website" in catalogue order; "—" when none. */
internal fun portalTypes(types: Set<ProjectType>): String =
    ProjectType.entries.filter { it in types }.joinToString(" · ") { it.portalShortLabel }.ifEmpty { "—" }

/** PROJECT FOR column: "BAU Project" / "Class Project · CMPS433" (urgency has its own chip). */
internal val StaffInquiry.portalProjectFor: String
    get() = buildList {
        add(draft.projectFor.label)
        if (draft.projectFor == ProjectFor.ClassProject) draft.classCode?.takeIf { it.isNotBlank() }?.let(::add)
    }.joinToString(" · ")

/** REWARD column; "—" when the inquiry has no reward label yet. */
internal val StaffInquiry.portalReward: String
    get() = rewardLabel.ifBlank { "—" }

/** TODAY / LATEST column: the backend's one-liner, else the status summary title ("Waiting for your check"). */
internal val StaffInquiry.portalLatest: String
    get() = latest.ifBlank { summaryOf(this).title }

/** Latest text is shown in warning ink when the team is blocked. */
internal val StaffInquiry.hasOpenBlocker: Boolean
    get() = progress?.blocker?.resolved == false

internal val StaffInquiry.isUrgent: Boolean get() = draft.priority == InquiryPriority.Urgent

/** Mono meta line under the detail title: "BAU Project · iOS · Android · Website · $35 · 3 students · dev lead reviews". */
internal val StaffInquiry.portalDetailMeta: String
    get() = buildList {
        add(portalProjectFor)
        if (draft.types.isNotEmpty()) add(portalTypes(draft.types))
        rewardLabel.takeIf { it.isNotBlank() && it != "—" }?.let(::add)
        val students = progress?.studentNames?.size ?: 0
        if (students > 0) add(if (students == 1) "1 student" else "$students students")
        reviewedBy?.takeIf { it.isNotBlank() }?.let { add("$it reviews") }
    }.joinToString(" · ")

/** "Day 4 of 7 · due Nov 22" next to the Today heading. */
internal fun StaffInquiry.portalDayLine(): String? {
    val p = progress ?: return null
    val due = draft.deadline?.let(::formatShortDate)?.let { "due $it" }
    return listOfNotNull("Day ${p.dayOf} of ${p.totalDays}", due).joinToString(" · ")
}

/** Open inquiries stat card: everything not yet closed (refining, posted and in progress). */
internal val StaffOverview.portalOpenCount: Int get() = openCount + inProgressCount

/**
 * Rows of the Inquiries table: the filter's sections flattened (drafts, refining, posted, closed — newest first
 * inside each group), then narrowed by the header search (title, project-for, class code; case-insensitive).
 */
internal fun portalInquiryRows(sections: List<InquirySection>, query: String): List<StaffInquiry> {
    val rows = sections.flatMap { it.inquiries }
    val q = query.trim()
    if (q.isEmpty()) return rows
    return rows.filter { inquiry ->
        inquiry.title.contains(q, ignoreCase = true) ||
            inquiry.draft.title.contains(q, ignoreCase = true) ||
            inquiry.portalProjectFor.contains(q, ignoreCase = true)
    }
}

/** Empty-table copy for the current filter / search. */
internal fun portalEmptyMessage(filter: InquiryFilter, query: String, hasAny: Boolean): Pair<String, String> = when {
    query.isNotBlank() -> "No matches" to "No inquiry matches “${query.trim()}”."
    !hasAny -> "No inquiries yet" to "Post your first inquiry and follow it here, day by day."
    filter == InquiryFilter.Open -> "Nothing open" to "Every inquiry is closed. Post a new one when you need something built."
    filter == InquiryFilter.Closed -> "Nothing closed yet" to "Delivered and cancelled inquiries show up here."
    else -> "No inquiries yet" to "Post your first inquiry and follow it here, day by day."
}
