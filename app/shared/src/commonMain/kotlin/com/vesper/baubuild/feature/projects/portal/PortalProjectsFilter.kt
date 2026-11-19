package com.vesper.baubuild.feature.projects.portal

import com.vesper.baubuild.core.model.Difficulty
import com.vesper.baubuild.core.model.Inquiry
import com.vesper.baubuild.core.model.JoinPolicy
import com.vesper.baubuild.core.model.Project
import com.vesper.baubuild.core.model.Reviewer
import com.vesper.baubuild.feature.projects.JoinEligibility
import com.vesper.baubuild.feature.projects.eligibilityFor
import com.vesper.baubuild.feature.projects.joiningDetail

/**
 * Filters of the portal Projects page. The difficulty group is single-select (null = All); the other chips are
 * independent toggles combined with AND, as in the Web portal design.
 */
internal data class PortalProjectFilters(
    val difficulty: Difficulty? = null,
    val myClasses: Boolean = false,
    val urgent: Boolean = false,
    val openToMyScore: Boolean = false,
    val query: String = "",
) {
    val isDefault: Boolean get() = this == PortalProjectFilters()
}

/** What the filters need to know about the viewer and the urgent requests. */
internal data class PortalFilterContext(
    val score: Int = 0,
    val myClassCodes: Set<String> = emptySet(),
    /** Projects created from urgent inquiries. */
    val urgentProjectIds: Set<String> = emptySet(),
)

internal fun Project.matches(filters: PortalProjectFilters, context: PortalFilterContext): Boolean {
    if (filters.difficulty != null && difficulty != filters.difficulty) return false
    if (filters.myClasses && (classCode == null || classCode !in context.myClassCodes)) return false
    if (filters.urgent && id !in context.urgentProjectIds) return false
    if (filters.openToMyScore && eligibilityFor(context.score) != JoinEligibility.Eligible) return false
    val q = filters.query.trim()
    if (q.isNotEmpty()) {
        val haystack = listOfNotNull(title, owner, summary, description, classCode)
        if (haystack.none { it.contains(q, ignoreCase = true) }) return false
    }
    return true
}

/** The urgent inquiry featured in the navy hero, with the project it became. */
internal data class UrgentHero(val inquiry: Inquiry, val project: Project)

/** First urgent inquiry that already has a project in [projects] (inquiries without one can't be opened). */
internal fun urgentHeroOf(inquiries: List<Inquiry>, projects: List<Project>): UrgentHero? =
    inquiries.asSequence().filter { it.isUrgent }.firstNotNullOfOrNull { inquiry ->
        inquiry.projectId?.let { id -> projects.firstOrNull { it.id == id } }?.let { UrgentHero(inquiry, it) }
    }

/** What the page renders: the hero (if its project passes the filters) and the grid without the hero's project. */
internal data class PortalProjectsLayout(val hero: UrgentHero?, val grid: List<Project>) {
    val isEmpty: Boolean get() = hero == null && grid.isEmpty()
}

internal fun portalProjectsLayout(
    projects: List<Project>,
    urgentInquiries: List<Inquiry>,
    filters: PortalProjectFilters,
    context: PortalFilterContext,
): PortalProjectsLayout {
    val visible = projects.filter { it.matches(filters, context) }
    val hero = urgentHeroOf(urgentInquiries, projects)?.takeIf { h -> visible.any { it.id == h.project.id } }
    return PortalProjectsLayout(hero, visible.filterNot { it.id == hero?.project?.id })
}

internal fun urgentProjectIdsOf(inquiries: List<Inquiry>): Set<String> =
    inquiries.filter { it.isUrgent }.mapNotNullTo(mutableSetOf()) { it.projectId }

/** Card chip: "Due today", "Due in 1 day", "Due in 3 days", "Due in 1 week", "Due in 2 weeks", "Due in 10 days". */
internal fun portalDueChip(dueInDays: Int): String = when {
    dueInDays <= 0 -> "Due today"
    dueInDays == 1 -> "Due in 1 day"
    dueInDays % 7 == 0 -> (dueInDays / 7).let { w -> if (w == 1) "Due in 1 week" else "Due in $w weeks" }
    else -> "Due in $dueInDays days"
}

/** Card footer: "Everyone welcome" / "Professor approves" / "Dev lead approves". */
internal val Project.portalJoiningLabel: String
    get() = if (joinPolicy == JoinPolicy.ApprovalRequired && reviewer != Reviewer.Professor) "Dev lead approves" else joiningDetail

/** Empty-grid copy for the active filters. */
internal fun PortalProjectFilters.emptyMessage(): String = when {
    query.isNotBlank() -> "No projects match “${query.trim()}”."
    urgent -> "No urgent projects right now."
    myClasses -> "No open projects from your classes."
    openToMyScore -> "No projects open to your score with these filters."
    difficulty != null -> "No ${difficulty.name.lowercase()} projects open right now."
    else -> "No open projects right now. Check back soon."
}
