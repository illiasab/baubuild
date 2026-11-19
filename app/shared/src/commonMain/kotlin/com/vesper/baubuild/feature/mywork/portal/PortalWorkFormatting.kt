package com.vesper.baubuild.feature.mywork.portal

import com.vesper.baubuild.core.model.Membership
import com.vesper.baubuild.core.model.Project
import com.vesper.baubuild.core.model.ReviewStage
import com.vesper.baubuild.core.model.ReviewStatus
import com.vesper.baubuild.feature.mywork.MyWorkSections
import com.vesper.baubuild.feature.mywork.isActive
import com.vesper.baubuild.feature.mywork.subtitle
import com.vesper.baubuild.feature.mywork.workspace.label

/** The three stat cards at the top of the portal My Work page. */
internal data class MyWorkSummary(
    val activeProjects: Int,
    val waitingProjects: Int,
    /**
     * Sum of [Project.taskCount] over active projects.
     * TODO(backend): `Membership` has no "tasks assigned to me" count; taskCount is the closest field we get.
     */
    val tasksAssigned: Int,
) {
    companion object {
        fun from(sections: MyWorkSections) = MyWorkSummary(
            activeProjects = sections.active.size,
            waitingProjects = sections.waiting.size,
            tasksAssigned = sections.active.sumOf { it.project.taskCount },
        )
    }
}

/** "1 project" / "2 projects". */
internal fun projectCountLabel(count: Int): String = if (count == 1) "1 project" else "$count projects"

/** WHERE IT STANDS column: "3 tasks assigned to you" for active projects, "Reviewing: dev lead" for pending ones. */
internal val Membership.whereItStands: String
    get() = if (status.isActive) {
        val n = project.taskCount
        "$n ${if (n == 1) "task" else "tasks"} assigned to you"
    } else {
        subtitle
    }

/** Lower-case due phrase used in the workspace meta line. */
internal fun dueLabel(days: Int): String = when {
    days < 0 -> "overdue"
    days == 0 -> "due today"
    days == 1 -> "due tomorrow"
    else -> "due in $days days"
}

/** "library-booking · Easy · $18 · due in 3 days". */
internal val Project.workspaceMeta: String
    get() = listOfNotNull(repoName, difficulty.label, "$$bountyUsd", dueLabel(dueInDays)).joinToString(" · ")

/** The review that belongs to the open workspace, if any. */
internal fun List<ReviewStatus>.forProject(projectId: String): ReviewStatus? = firstOrNull { it.project.id == projectId }

/** The portal timeline shows detail and note as one paragraph. */
internal val ReviewStage.portalDetail: String
    get() = listOfNotNull(detail.takeIf { it.isNotBlank() }, note?.takeIf { it.isNotBlank() }).joinToString(" ")
